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
import com.utbildning.tracker.data.PendingSessionActionResult
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.TrackerDatabase
import com.utbildning.tracker.data.local.SessionEntity
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.domain.WeeklyRule
import java.time.LocalDate
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

    @Test fun futureSessionRegistersAlarmBeforeItsDateBecomesEditable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        val local = TrackerRepository(db)
        val tomorrow = LocalDate.now().plusDays(1)
        try {
            val course = local.createCourse("Future C reminder", 0, topics = listOf("One"))
            local.saveInitialSchedule(course.id, listOf(WeeklyRule(tomorrow.dayOfWeek.value, 13 * 60)))
            val session = db.trackerDao().getSessions(course.id).first { it.date == tomorrow.toEpochDay() }
            assertFalse("Future result stays read-only", local.getSessionDetails(session.id)!!.canEdit)
            ReminderScheduler.reconcile(context, local)
            val expected = tomorrow.atTime(13, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            fun registeredStart(): Boolean {
                val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys alarm"),
                ).bufferedReader().use { it.readText() }
                return Regex("RTC_WAKEUP #\\d+: Alarm\\{[^\\n}]*origWhen $expected [^\\n}]* ${Regex.escape(context.packageName)}\\}")
                    .containsMatchIn(dump)
            }
            assertTrue("Tomorrow's start must already have a registered RTC_WAKEUP alarm", registeredStart())
            repeat(2) {
                ReminderScheduler.reconcile(context, local)
                assertTrue(registeredStart())
                assertTrue("Reconciliation does not post a future notification",
                    manager.activeNotifications.none { it.tag == session.id })
            }
            val dao = db.trackerDao()
            // Bound this fixture to one session so a later occurrence cannot mask cancellation.
            dao.getSessions(course.id).filter { it.id != session.id }.forEach { dao.deleteSession(it.id) }
            for (result in listOf(SessionResult.DONE, SessionResult.SKIPPED)) {
                dao.updateSession(session.copy(result = result))
                ReminderScheduler.reconcile(context, local)
                assertFalse("$result cancels future planning", registeredStart())
                dao.updateSession(session)
            }
            for (inactive in listOf(course.copy(isPaused = true), course.copy(isCompleted = true, colorId = null, completedAt = System.currentTimeMillis()))) {
                dao.updateCourse(inactive)
                ReminderScheduler.reconcile(context, local)
                assertFalse("Inactive course cancels future planning", registeredStart())
                dao.updateCourse(course)
            }
            val topic = dao.getTopics(course.id).single()
            dao.updateTopic(topic.copy(isCompleted = true))
            ReminderScheduler.reconcile(context, local)
            assertFalse("Exhaustion cancels future planning", registeredStart())
            dao.updateTopic(topic)
            ReminderScheduler.reconcile(context, local)
            assertTrue("Restored active course registers future alarm", registeredStart())
            dao.deleteSchedule(course.id)
            ReminderScheduler.reconcile(context, local)
            assertFalse("Disabled schedule cancels future planning", registeredStart())
        } finally {
            db.close()
            ReminderScheduler.reconcile(context, repository)
        }
    }

    @Test fun reconciliationKeepsYesterdayQuestionAndCancelsIneligibleQuestionsWithoutWrites() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        val today = LocalDate.now()
        val local = TrackerRepository(db)
        val dao = db.trackerDao()
        try {
            val course = local.createCourse("Candidate questions", 0, topics = listOf("One"))
            local.saveInitialSchedule(course.id, listOf(WeeklyRule(today.plusDays(1).dayOfWeek.value, 13 * 60)))
            for ((id, date, result) in listOf(
                Triple("candidate_yesterday", today.minusDays(1), SessionResult.PENDING),
                Triple("candidate_old", today.minusDays(2), SessionResult.PENDING),
                Triple("candidate_done", today, SessionResult.DONE),
                Triple("candidate_skipped", today.plusDays(1), SessionResult.SKIPPED),
            )) {
                dao.insertSession(SessionEntity(id, course.id, date.toEpochDay(), 600,
                    course.name, course.colorId, 0, 0, result = result))
                postQuestion(id)
            }
            fun questionIds() = manager.activeNotifications.mapNotNull { it.tag }
                .filter { it.startsWith("candidate_") }.toSet()
            awaitCondition("All fixture questions are posted") { questionIds().size == 4 }
            val before = dao.getAllSessions()
            val beforeTopics = dao.getTopics(course.id)
            repeat(2) {
                ReminderScheduler.reconcile(context, local)
                awaitCondition("Only yesterday's pending question remains") {
                    questionIds() == setOf("candidate_yesterday")
                }
                assertEquals(setOf("candidate_yesterday"), questionIds())
                assertEquals(before, dao.getAllSessions())
                assertEquals(beforeTopics, dao.getTopics(course.id))
            }
            for (inactive in listOf(course.copy(isPaused = true), course.copy(isCompleted = true, colorId = null, completedAt = System.currentTimeMillis()))) {
                dao.updateCourse(inactive)
                ReminderScheduler.reconcile(context, local)
                awaitCondition("Inactive questions are cancelled") { questionIds().isEmpty() }
                assertTrue(local.getReminderCandidates().isEmpty())
                dao.updateCourse(course)
                postQuestion("candidate_yesterday")
                awaitCondition("Restored fixture question is posted") { "candidate_yesterday" in questionIds() }
            }
            dao.updateTopic(beforeTopics.single().copy(isCompleted = true))
            ReminderScheduler.reconcile(context, local)
            awaitCondition("Inactive questions are cancelled") { questionIds().isEmpty() }
            assertTrue(local.getReminderCandidates().isEmpty())
            dao.updateTopic(beforeTopics.single())
            postQuestion("candidate_yesterday")
            awaitCondition("Fixture question is posted before schedule deletion") { "candidate_yesterday" in questionIds() }
            dao.deleteSchedule(course.id)
            ReminderScheduler.reconcile(context, local)
            awaitCondition("Inactive questions are cancelled") { questionIds().isEmpty() }
            assertTrue(local.getReminderCandidates().isEmpty())
            assertEquals(before, dao.getAllSessions())
        } finally {
            db.close()
            ReminderScheduler.reconcile(context, repository)
        }
    }

    private suspend fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) delay(50)
        assertTrue(message, condition())
    }

    private fun postQuestion(sessionId: String, title: String = "Question") {
        manager.createNotificationChannel(android.app.NotificationChannel(
            "course_starts", context.getString(R.string.reminder_channel), android.app.NotificationManager.IMPORTANCE_DEFAULT,
        ))
        manager.notify(sessionId, 2, android.app.Notification.Builder(context, "course_starts")
            .setSmallIcon(R.drawable.ic_book).setContentTitle(title).build())
    }

    @Test fun staleRepeatedDeletedAndCompletedActionsClearQuestionsWithoutChangingData() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        val today = LocalDate.now()
        val now = today.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val local = TrackerRepository(db, now = { now })
        val dao = db.trackerDao()
        var nextColor = 0
        try {
            suspend fun fixture(id: String, date: LocalDate): Pair<String, SessionEntity> {
                val course = local.createCourse(id, nextColor++)
                local.saveInitialSchedule(course.id, listOf(WeeklyRule(today.plusDays(1).dayOfWeek.value, 13 * 60)))
                val session = SessionEntity(id, course.id, date.toEpochDay(), 10 * 60, course.name, course.colorId, now, now)
                dao.insertSession(session)
                return course.id to session
            }
            for (result in listOf(SessionResult.DONE, SessionResult.SKIPPED)) {
                val (_, stale) = fixture("stale_$result", today.minusDays(2))
                postQuestion(stale.id)
                assertEquals(PendingSessionActionResult.IGNORED,
                    ReminderScheduler.applyPendingAction(context, stale.id, result, local))
                assertEquals(SessionResult.PENDING, dao.getSession(stale.id)!!.result)
                assertTrue(manager.activeNotifications.none { it.tag == stale.id })
            }

            val (_, synchronized) = fixture("stale_after_sync", today.minusDays(2))
            local.synchronize()
            postQuestion(synchronized.id)
            assertEquals(PendingSessionActionResult.IGNORED,
                ReminderScheduler.applyPendingAction(context, synchronized.id, SessionResult.SKIPPED, local))
            assertEquals(SessionResult.SKIPPED, dao.getSession(synchronized.id)!!.result)
            assertTrue(manager.activeNotifications.none { it.tag == synchronized.id })

            val (_, repeated) = fixture("repeated", today)
            assertEquals(PendingSessionActionResult.APPLIED,
                ReminderScheduler.applyPendingAction(context, repeated.id, SessionResult.SKIPPED, local))
            postQuestion(repeated.id)
            assertEquals(PendingSessionActionResult.IGNORED,
                ReminderScheduler.applyPendingAction(context, repeated.id, SessionResult.DONE, local))
            assertEquals(SessionResult.SKIPPED, dao.getSession(repeated.id)!!.result)
            assertTrue(manager.activeNotifications.none { it.tag == repeated.id })

            val (deletedCourse, deleted) = fixture("deleted", today)
            local.deleteCourse(deletedCourse)
            postQuestion(deleted.id)
            assertEquals(PendingSessionActionResult.IGNORED,
                ReminderScheduler.applyPendingAction(context, deleted.id, SessionResult.SKIPPED, local))
            assertTrue(manager.activeNotifications.none { it.tag == deleted.id })

            val (completedCourse, completed) = fixture("completed", today)
            local.completeCourse(completedCourse)
            postQuestion(completed.id)
            assertEquals(PendingSessionActionResult.IGNORED,
                ReminderScheduler.applyPendingAction(context, completed.id, SessionResult.DONE, local))
            assertTrue(manager.activeNotifications.none { it.tag == completed.id })
        } finally {
            db.close()
            manager.cancelAll()
            ReminderScheduler.reconcile(context, repository)
        }
    }

    @Test fun writeFailureDoesNotClearQuestionAndRetryCanStillApply() = runBlocking {
        val name = "reminder-write-failure.db"
        context.deleteDatabase(name)
        val today = LocalDate.now()
        val now = today.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        var db = TrackerDatabase.open(context, name)
        var local = TrackerRepository(db, now = { now })
        val course = local.createCourse("Retry action", 0)
        local.saveInitialSchedule(course.id, listOf(WeeklyRule(today.plusDays(1).dayOfWeek.value, 13 * 60)))
        db.trackerDao().insertSession(SessionEntity("retry_action", course.id, today.toEpochDay(), 10 * 60,
            course.name, course.colorId, now, now))
        postQuestion("retry_action")
        db.close()

        val failure = runCatching {
            ReminderScheduler.applyPendingAction(context, "retry_action", SessionResult.SKIPPED, local)
        }.exceptionOrNull()
        assertNotNull("Closed storage must surface as a write failure", failure)
        assertTrue("A failed write must not look acknowledged", manager.activeNotifications.any { it.tag == "retry_action" })

        db = TrackerDatabase.open(context, name)
        local = TrackerRepository(db, now = { now })
        try {
            assertEquals(PendingSessionActionResult.APPLIED,
                ReminderScheduler.applyPendingAction(context, "retry_action", SessionResult.SKIPPED, local))
            assertEquals(SessionResult.SKIPPED, db.trackerDao().getSession("retry_action")!!.result)
            assertTrue(manager.activeNotifications.none { it.tag == "retry_action" })
        } finally {
            db.close()
            context.deleteDatabase(name)
            manager.cancelAll()
            ReminderScheduler.reconcile(context, repository)
        }
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
        val local = TrackerRepository(db, now = { trigger - 60_000 })
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
