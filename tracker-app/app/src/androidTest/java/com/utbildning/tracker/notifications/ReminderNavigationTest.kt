package com.utbildning.tracker.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.utbildning.tracker.MainActivity
import com.utbildning.tracker.data.AppContainer
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.data.local.SessionEntity
import com.utbildning.tracker.data.local.TrackerDatabase
import com.utbildning.tracker.domain.WeeklyRule
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue

@RunWith(AndroidJUnit4::class)
class ReminderNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = AppContainer.repository(context)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var courseId: String? = null

    @Before fun permissions() {
        // Emulator setup after UTP installs the app. Revocation would kill instrumentation.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
            "appops set --uid ${context.packageName} SCHEDULE_EXACT_ALARM allow").forEach {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(it)).use { stream -> stream.readBytes() }
        }
        assertTrue(ReminderScheduler.allowed(context))
        context.startActivity(android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    @After fun cleanup(): Unit = runBlocking {
        courseId?.let { repository.deleteCourse(it) }
        ReminderScheduler.reconcile(context, repository)
        manager.cancelAll()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).flatMap {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
            }.distinct().forEach { it.finishAndRemoveTask() }
        }
    }

    @Test fun realExactAlarmDeliversOnceAndTapRendersSelectedSession() = runBlocking {
        compose.waitForIdle()
        val course = repository.createCourse("Reminder navigation C", repository.availableColors().first())
        courseId = course.id
        val next = ZonedDateTime.now().plusMinutes(1).withSecond(0).withNano(0)
        repository.saveInitialSchedule(course.id, listOf(WeeklyRule(next.dayOfWeek.value, next.hour * 60 + next.minute)))
        val session = repository.observeSessions().first().first { it.courseId == course.id }
        val originalDetails = repository.getCourseDetails(course.id)
        val originalSchedule = repository.getSchedule(course.id)
        val trigger = next.toInstant().toEpochMilli()
        ReminderScheduler.reconcile(context, repository)
        val deadline = trigger + 25_000
        while (manager.activeNotifications.none { it.tag == session.id } && System.currentTimeMillis() < deadline) delay(250)
        val notification = manager.activeNotifications.singleOrNull { it.tag == session.id }
        assertNotNull("Actual AlarmManager delivery before deadline", notification)
        assertEquals(course.name, notification!!.notification.extras.getString("android.title"))
        assertEquals(originalDetails, repository.getCourseDetails(course.id))
        assertEquals(originalSchedule, repository.getSchedule(course.id))
        ReminderScheduler.receive(context, trigger, repository)
        assertEquals(1, manager.activeNotifications.count { it.tag == session.id })

        notification.notification.contentIntent.send()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("session_save").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("session_save").assertIsDisplayed()
        compose.onNodeWithText(course.name).assertIsDisplayed()
        compose.waitForIdle()

        repository.setSessionResult(session.id, SessionResult.DONE, emptySet())
        ReminderScheduler.reconcile(context, repository)
        val cancellationDeadline = System.currentTimeMillis() + 5_000
        while (manager.activeNotifications.any { it.tag == session.id } && System.currentTimeMillis() < cancellationDeadline) delay(50)
        assertTrue("Result cancels existing notification", manager.activeNotifications.none { it.tag == session.id })
        compose.waitForIdle()
    }

    @Test fun questionActionIntentsRunThroughReceiverAndColdActivityIntoRepository() = runBlocking {
        val now = ZonedDateTime.now().withSecond(0).withNano(0)
        assumeTrue("Needs a non-midnight result minute", now.hour * 60 + now.minute > 0)
        val trigger = now.toInstant().toEpochMilli()
        val direct = TrackerRepository::class.java.getDeclaredField("database").let { field ->
            field.isAccessible = true
            field.get(repository) as TrackerDatabase
        }
        val dao = direct.trackerDao()
        val skipCourse = repository.createCourse("Action skip C", repository.availableColors().first())
        courseId = skipCourse.id
        val doneCourse = repository.createCourse("Action done C", repository.availableColors().first())
        try {
            repository.saveInitialSchedule(skipCourse.id, listOf(WeeklyRule(now.plusDays(1).dayOfWeek.value, 720)))
            repository.saveInitialSchedule(doneCourse.id, listOf(WeeklyRule(now.plusDays(1).dayOfWeek.value, 780)))
            val startMinute = (now.hour * 60 + now.minute - 1).coerceAtLeast(0)
            val endMinute = now.hour * 60 + now.minute
            val created = System.currentTimeMillis()
            val skip = SessionEntity("action_skip", skipCourse.id, LocalDate.now().toEpochDay(), startMinute,
                skipCourse.name, skipCourse.colorId, created, created, endMinute = endMinute)
            val done = SessionEntity("action_done", doneCourse.id, LocalDate.now().toEpochDay(), startMinute,
                doneCourse.name, doneCourse.colorId, created, created, endMinute = endMinute)
            dao.insertSession(skip)
            dao.insertSession(done)

            ReminderScheduler.receive(context, trigger, repository)
            compose.waitUntil(5_000) { manager.activeNotifications.count { it.tag in setOf(skip.id, done.id) } == 2 }
            val skipIntent = manager.activeNotifications.single { it.tag == skip.id }.notification.actions[1].actionIntent
            skipIntent.send()
            compose.waitUntil(5_000) { runBlocking { repository.getSessionDetails(skip.id)?.session?.result == SessionResult.SKIPPED } }
            skipIntent.send()
            compose.waitForIdle()
            assertEquals(SessionResult.SKIPPED, repository.getSessionDetails(skip.id)!!.session.result)

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).flatMap {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
                }.distinct().forEach { it.finishAndRemoveTask() }
            }
            val doneIntent = manager.activeNotifications.single { it.tag == done.id }.notification.actions[0].actionIntent
            doneIntent.send()
            compose.waitUntil(10_000) { runBlocking { repository.getSessionDetails(done.id)?.session?.result == SessionResult.DONE } }
            assertEquals(SessionResult.DONE, repository.getSessionDetails(done.id)!!.session.result)
            assertTrue(manager.activeNotifications.none { it.tag == done.id })
        } finally {
            repository.deleteCourse(doneCourse.id)
        }
    }
}
