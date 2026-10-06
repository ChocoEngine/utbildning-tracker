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
import com.utbildning.tracker.data.PendingSessionActionResult
import com.utbildning.tracker.data.local.SessionEntity
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.domain.SessionTime
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One exact alarm for the next start or result question, potentially shared by several courses. */
object ReminderScheduler {
    private const val CHANNEL = "course_starts"
    private const val ACTION_START = "com.utbildning.tracker.START"
    const val ACTION_OPEN_SESSION = "com.utbildning.tracker.OPEN_SESSION"
    const val ACTION_COMPLETE_SESSION = "com.utbildning.tracker.COMPLETE_SESSION"
    const val ACTION_SKIP_SESSION = "com.utbildning.tracker.SKIP_SESSION"
    const val EXTRA_SESSION_ID = "sessionId"
    private const val START_NOTIFICATION = 1
    private const val QUESTION_NOTIFICATION = 2
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observing = false

    @Synchronized fun startObserving(context: Context, repository: TrackerRepository) {
        if (observing) return
        observing = true
        val app = context.applicationContext
        scope.launch {
            repository.observeReminderChanges().collect {
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

    private suspend fun reconcileLocked(context: Context, repository: TrackerRepository) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        alarm.cancel(pending(context))
        val sessions = repository.getReminderCandidates()
        val ids = sessions.map { it.id }.toSet()
        notifications.activeNotifications.filter { it.tag != null && it.tag !in ids }.forEach { notifications.cancel(it.tag, it.id) }
        val allowedKeys = sessions.flatMap { listOf(it.id, questionKey(it)) }.toSet()
        val preferences = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        val savedDelivered = preferences.getStringSet("delivered", emptySet()).orEmpty()
        val delivered = savedDelivered.intersect(allowedKeys)
        if (delivered != savedDelivered) {
            preferences.edit().putStringSet("delivered", delivered).apply()
        }
        if (!allowed(context)) return
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        // A concurrent refresh at the exact event time must not cancel a due, not-yet-delivered alarm.
        val next = sessions.flatMap { session ->
            buildList {
                // Session ids are the legacy keys used by already installed versions for start reminders.
                if (session.id !in delivered) add(start(session, zone))
                if (questionKey(session) !in delivered) add(question(session, zone))
            }
        }
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
                val atStart = start(session, ZoneId.systemDefault()) == trigger
                val atQuestion = question(session, ZoneId.systemDefault()) == trigger
                val keys = buildList {
                    if (atStart) add(session.id)
                    if (atQuestion) {
                        // Once the result question is due, an omitted start reminder must never be posted late.
                        add(session.id)
                        add(questionKey(session))
                    }
                }.filterNot { it in delivered }
                if (keys.isEmpty()) return@withCurrentReminders
                val open = sessionActivityIntent(context, session.id, ACTION_OPEN_SESSION)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val content = PendingIntent.getActivity(context, requestCode(session.id, ACTION_OPEN_SESSION), open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                try {
                    // The question must alert again even while the start notification is still visible.
                    if (atQuestion) manager.cancel(session.id, START_NOTIFICATION)
                    val notification = Notification.Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.ic_book).setContentTitle(session.courseName)
                        .setContentText(context.getString(if (atQuestion) R.string.reminder_question else R.string.reminder_start)).setContentIntent(content)
                        .setAutoCancel(true).setOnlyAlertOnce(true)
                    if (atQuestion) {
                        notification.addAction(0, context.getString(R.string.session_done), completeIntent(context, session.id))
                        notification.addAction(0, context.getString(R.string.session_skipped), skipIntent(context, session.id))
                    }
                    manager.notify(session.id, if (atQuestion) QUESTION_NOTIFICATION else START_NOTIFICATION, notification.build())
                    delivered.addAll(keys)
                    preferences.edit().putStringSet("delivered", delivered).commit()
                } catch (_: SecurityException) { /* Permission may have been revoked during delivery. */ }
            }
        }
        repository.synchronize()
        reconcileLocked(context, repository)
    }

    internal fun sessionActivityIntent(context: Context, sessionId: String, action: String) =
        Intent(context, MainActivity::class.java).setAction(action)
            .setData(Uri.parse("tracker://session/$sessionId/${action.substringAfterLast('.').lowercase()}"))
            .putExtra(EXTRA_SESSION_ID, sessionId)

    private fun completeIntent(context: Context, sessionId: String) = PendingIntent.getActivity(
        context, requestCode(sessionId, ACTION_COMPLETE_SESSION),
        sessionActivityIntent(context, sessionId, ACTION_COMPLETE_SESSION)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun skipIntent(context: Context, sessionId: String) = PendingIntent.getBroadcast(
        context, requestCode(sessionId, ACTION_SKIP_SESSION),
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_SKIP_SESSION)
            .setData(Uri.parse("tracker://session/$sessionId/skip")).putExtra(EXTRA_SESSION_ID, sessionId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun requestCode(sessionId: String, action: String) = 31 * sessionId.hashCode() + action.hashCode()

    suspend fun applyPendingAction(
        context: Context,
        sessionId: String,
        result: SessionResult,
        repository: TrackerRepository = AppContainer.repository(context),
    ): PendingSessionActionResult {
        val outcome = repository.applyPendingSessionAction(sessionId, result)
        if (outcome != PendingSessionActionResult.NEEDS_TOPICS) {
            context.getSystemService(NotificationManager::class.java).cancel(sessionId, QUESTION_NOTIFICATION)
            reconcile(context, repository)
        }
        return outcome
    }

    suspend fun skipPending(context: Context, sessionId: String, repository: TrackerRepository = AppContainer.repository(context)) {
        applyPendingAction(context, sessionId, SessionResult.SKIPPED, repository)
    }

    private fun start(session: SessionEntity, zone: ZoneId) = SessionTime.start(LocalDate.ofEpochDay(session.date), session.startMinute, zone).toEpochMilli()
    private fun question(session: SessionEntity, zone: ZoneId) =
        SessionTime.question(LocalDate.ofEpochDay(session.date), session.startMinute, session.endMinute, zone).toEpochMilli()
    private fun questionKey(session: SessionEntity) = "question:${session.id}"
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (intent.action == ReminderScheduler.ACTION_SKIP_SESSION) {
                    intent.getStringExtra(ReminderScheduler.EXTRA_SESSION_ID)?.let {
                        ReminderScheduler.skipPending(context.applicationContext, it)
                    }
                } else {
                    val trigger = if (intent.hasExtra("trigger")) intent.getLongExtra("trigger", 0) else null
                    ReminderScheduler.receive(context.applicationContext, trigger)
                }
            } catch (e: Exception) { android.util.Log.e("Tracker", "Reminder processing failed", e) }
            finally { result.finish() }
        }
    }
}
