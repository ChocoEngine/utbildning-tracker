package com.utbildning.tracker.notifications

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import com.utbildning.tracker.MainActivity
import com.utbildning.tracker.R
import com.utbildning.tracker.data.AppContainer
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.SessionEntity
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.domain.SessionTime
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One exact alarm for the next start, potentially shared by several courses. */
object ReminderScheduler {
    private const val CHANNEL = "course_starts"
    private const val ACTION_START = "com.utbildning.tracker.START"
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observing = false

    @Synchronized fun startObserving(context: Context, repository: TrackerRepository) {
        if (observing) return
        observing = true
        val app = context.applicationContext
        scope.launch {
            combine(repository.observeSessions(), repository.observeCourses()) { _, _ -> Unit }.collect {
                try { reconcile(app, repository) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.e("Tracker", "Reminder reconciliation failed", e) }
            }
        }
    }

    fun allowed(context: Context): Boolean = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pending(context: Context, trigger: Long = 0): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION_START)
            .setData(Uri.parse("tracker://alarm/next")).putExtra("trigger", trigger),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    suspend fun reconcile(context: Context, repository: TrackerRepository) = mutex.withLock {
        reconcileLocked(context, repository)
    }

    private suspend fun eligible(repository: TrackerRepository): List<SessionEntity> {
        val courses = repository.observeCourses().first().filter { !it.isCompleted && !it.isPaused && it.exhaustedAt == null }.map { it.id }.toSet()
        return repository.observeSessions().first().filter { it.courseId in courses && it.result == SessionResult.PENDING }
            .filter { repository.getSchedule(it.courseId) != null }
    }

    private suspend fun reconcileLocked(context: Context, repository: TrackerRepository) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        alarm.cancel(pending(context))
        val sessions = eligible(repository)
        val ids = sessions.map { it.id }.toSet()
        notifications.activeNotifications.filter { it.tag != null && it.tag !in ids }.forEach { notifications.cancel(it.tag, it.id) }
        if (!allowed(context)) return
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val delivered = context.getSharedPreferences("reminders", Context.MODE_PRIVATE).getStringSet("delivered", emptySet()).orEmpty()
        // A concurrent refresh at the exact start must not cancel a due, not-yet-delivered alarm.
        val next = sessions.filter { it.id !in delivered }.map { start(it, zone) }
            .filter { it >= now - 5 * 60_000 }.minOrNull() ?: return
        try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(context, next)) }
        catch (_: SecurityException) { /* Special access was revoked between check and registration. */ }
    }

    suspend fun receive(context: Context, trigger: Long?, repository: TrackerRepository = AppContainer.repository(context)) = mutex.withLock {
        val now = System.currentTimeMillis()
        if (trigger != null && allowed(context) && now >= trigger && now - trigger <= 5 * 60_000) {
            val preferences = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
            val delivered = preferences.getStringSet("delivered", emptySet()).orEmpty().toMutableSet()
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
            repository.withCurrentReminders(trigger) { session ->
                if (session.id in delivered) return@withCurrentReminders
                val open = Intent(context, MainActivity::class.java).setAction("OPEN_SESSION")
                    .setData(Uri.parse("tracker://session/${session.id}")).putExtra("sessionId", session.id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                try {
                    manager.notify(session.id, 1, Notification.Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.ic_book).setContentTitle(session.courseNameSnapshot)
                        .setContentText(context.getString(R.string.reminder_start)).setContentIntent(content)
                        .setAutoCancel(true).setOnlyAlertOnce(true).build())
                    delivered.add(session.id)
                    preferences.edit().putStringSet("delivered", delivered).commit()
                } catch (_: SecurityException) { /* Permission may have been revoked during delivery. */ }
            }
        }
        repository.synchronize()
        reconcileLocked(context, repository)
    }

    private fun start(session: SessionEntity, zone: ZoneId) = SessionTime.start(LocalDate.ofEpochDay(session.date), session.startMinute, zone).toEpochMilli()
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val trigger = if (intent.hasExtra("trigger")) intent.getLongExtra("trigger", 0) else null
                ReminderScheduler.receive(context.applicationContext, trigger)
            } catch (e: Exception) { android.util.Log.e("Tracker", "Reminder processing failed", e) }
            finally { result.finish() }
        }
    }
}
