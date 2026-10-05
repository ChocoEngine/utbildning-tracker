package com.utbildning.tracker.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.R
import com.utbildning.tracker.data.AppContainer
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.TrackerDatabase
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.domain.WeeklyRule
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = AppContainer.repository(context)
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before fun permissions() {
        // UTP reinstalls the APK for each run. Set up this emulator after installation;
        // revoking POST_NOTIFICATIONS during instrumentation would kill the test process.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(
            "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
            "appops set --uid ${context.packageName} SCHEDULE_EXACT_ALARM allow",
        ).forEach { command ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        assertTrue("Emulator notification and exact alarm grants", ReminderScheduler.allowed(context))
    }

    @After fun cleanup() = runBlocking {
        ReminderScheduler.reconcile(context, repository)
        manager.cancelAll()
    }

    private suspend fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) delay(50)
        assertTrue(message, condition())
    }

    @Test fun dueRemindersRespectPauseCompletionDeletionExhaustionAndEarlyResult() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        val due = ZonedDateTime.now().withSecond(0).withNano(0)
        val trigger = due.toInstant().toEpochMilli()
        var testNow = trigger - 1
        val local = TrackerRepository(db, now = { testNow })
        try {
            for (case in listOf("control", "pause", "disable_schedule", "complete", "delete", "exhausted", "result")) {
                testNow = trigger - 1
                val course = local.createCourse(case, 0, topics = listOf("Массивы"))
                local.saveInitialSchedule(course.id, listOf(WeeklyRule(due.dayOfWeek.value, due.hour * 60 + due.minute)), due.toLocalDate().toEpochDay())
                val session = local.observeSessions().first().single { it.courseId == course.id }
                testNow = System.currentTimeMillis()
                when (case) {
                    "pause" -> local.pauseCourse(course.id)
                    "disable_schedule" -> local.disableSchedule(course.id)
                    "complete" -> {
                        manager.notify(session.id, 1, android.app.Notification.Builder(context, "course_starts")
                            .setSmallIcon(R.drawable.ic_book).setContentTitle(course.name).build())
                        awaitCondition("Completion fixture has an active notification") {
                            manager.activeNotifications.any { it.tag == session.id }
                        }
                        local.completeCourse(course.id)
                        ReminderScheduler.reconcile(context, local)
                        awaitCondition("Completion reconciliation cancels the active notification") {
                            manager.activeNotifications.none { it.tag == session.id }
                        }
                    }
                    "delete" -> local.deleteCourse(course.id)
                    "exhausted" -> local.toggleTopicCompletion(course.id, local.getCourseDetails(course.id)!!.topics.single().id)
                    "result" -> local.setSessionResult(session.id, SessionResult.SKIPPED)
                }
                assertTrue("Trigger must already be due", System.currentTimeMillis() >= trigger)
                assertFalse(context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
                    .getStringSet("delivered", emptySet()).orEmpty().contains(session.id))
                ReminderScheduler.receive(context, trigger, local)
                if (case == "control") {
                    awaitCondition("Due positive control delivers") { manager.activeNotifications.any { it.tag == session.id } }
                    assertEquals("Start reminders have no result actions", 0,
                        manager.activeNotifications.single { it.tag == session.id }.notification.actions?.size ?: 0)
                } else {
                    assertTrue("$case must not post", manager.activeNotifications.none { it.tag == session.id })
                    assertFalse("$case must not record delivery", context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
                        .getStringSet("delivered", emptySet()).orEmpty().contains(session.id))
                }
                if (case != "delete") local.deleteCourse(course.id)
                manager.cancelAll()
                awaitCondition("Clear control notification") { manager.activeNotifications.none { it.tag == session.id } }
            }
        } finally {
            db.close()
            ReminderScheduler.reconcile(context, repository)
        }
    }

    @Test fun endReminderAsksForResultAndRecordsBothEvents() = runBlocking {
        val due = ZonedDateTime.now().withSecond(0).withNano(0)
        assumeTrue("Needs a same-day start minute", due.hour * 60 + due.minute > 0)
        val trigger = due.toInstant().toEpochMilli()
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        val local = TrackerRepository(db, now = { trigger - 2 * 60_000 })
        val course = local.createCourse("End reminder", 0)
        try {
            local.saveInitialSchedule(course.id, listOf(WeeklyRule(
                due.dayOfWeek.value,
                due.hour * 60 + due.minute - 1,
                due.hour * 60 + due.minute,
            )), due.toLocalDate().toEpochDay())
            val session = local.observeSessions().first().single()

            ReminderScheduler.receive(context, trigger, local)

            awaitCondition("End reminder is posted") { manager.activeNotifications.any { it.tag == session.id } }
            val posted = manager.activeNotifications.single { it.tag == session.id }
            assertEquals("End reminder replaces the start notification with a fresh alert", 2, posted.id)
            assertEquals(context.getString(R.string.reminder_question), posted.notification.extras.getString("android.text"))
            val actions = posted.notification.actions
            assertNotNull(actions)
            assertEquals(2, actions!!.size)
            assertEquals(context.getString(R.string.session_done), actions[0].title.toString())
            assertEquals(context.getString(R.string.session_skipped), actions[1].title.toString())
            assertTrue("Done opens Activity directly", actions[0].actionIntent.isActivity)
            assertTrue("Skipped is handled in the receiver", actions[1].actionIntent.isBroadcast)
            assertNotEquals("Buttons need distinct PendingIntent identities", actions[0].actionIntent, actions[1].actionIntent)
            assertNotEquals("Body and Done need distinct PendingIntent identities", posted.notification.contentIntent, actions[0].actionIntent)
            val delivered = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
                .getStringSet("delivered", emptySet()).orEmpty()
            assertTrue("A late start reminder is suppressed", session.id in delivered)
            assertTrue("The result reminder is recorded", "question:${session.id}" in delivered)
        } finally {
            db.close()
            manager.cancelAll()
            ReminderScheduler.reconcile(context, repository)
        }
    }
}
