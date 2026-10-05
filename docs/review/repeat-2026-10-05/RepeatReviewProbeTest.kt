package com.utbildning.tracker.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.R
import com.utbildning.tracker.data.AppContainer
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.WeeklyRule
import com.utbildning.tracker.notifications.ReminderScheduler
import com.utbildning.tracker.ui.courses.CourseDraft
import com.utbildning.tracker.ui.courses.CourseEditorContent
import com.utbildning.tracker.ui.theme.TrackerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

/** Diagnostic evidence, deliberately asserting the observed defects, not intended as permanent tests. */
@RunWith(AndroidJUnit4::class)
class RepeatReviewProbeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun observesBlockedIncrementalReductionOfLegacyName() {
        val draft = mutableStateOf(CourseDraft(id = "legacy", name = "A".repeat(60)))
        compose.setContent {
            TrackerTheme { Surface {
                CourseEditorContent(draft.value, emptyList(), listOf(0), false, null,
                    onChange = { draft.value = it(draft.value) }, onSave = {}, onCancel = {},
                    onApply = {}, onDeleteCategory = {}, onToggle = {})
            } }
        }
        compose.onNodeWithTag("course_title").performTouchInput { longClick() }
        compose.onNodeWithTag("course_name").performTextReplacement("A".repeat(59))
        assertEquals("A".repeat(60), compose.onNodeWithTag("course_name").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("course_name_length_error", useUnmergedTree = true).assertExists()
        compose.runOnIdle { assertEquals(60, draft.value.name.length) }
        compose.onNodeWithTag("course_name").performTextReplacement("A".repeat(50))
        assertEquals("A".repeat(50), compose.onNodeWithTag("course_name").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        println("REPEAT_PROBE legacy60 -> candidate59 rejected; candidate50 accepted")
    }

    @Test fun observesSnapshotWaitMissingLateNavigationGraph() {
        val graphReady = mutableStateOf(false)
        val snapshotSeen = mutableStateOf(false)
        val flowSeen = mutableStateOf(false)
        lateinit var controller: androidx.navigation.NavHostController
        compose.setContent {
            val nav = rememberNavController()
            controller = nav
            LaunchedEffect(Unit) {
                snapshotFlow { nav.currentBackStackEntry }.filterNotNull().first()
                snapshotSeen.value = true
            }
            LaunchedEffect(Unit) {
                nav.currentBackStackEntryFlow.first()
                flowSeen.value = true
            }
            if (graphReady.value) NavHost(nav, startDestination = "review") {
                composable("review") { androidx.compose.material3.Text("Review") }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNull(controller.currentBackStackEntry)
            graphReady.value = true
        }
        compose.waitUntil(5_000) { flowSeen.value }
        compose.runOnIdle {
            assertNotNull(controller.currentBackStackEntry)
            assertFalse(snapshotSeen.value)
        }
        println("REPEAT_PROBE late graph: currentBackStackEntryFlow emits; snapshotFlow wait remains suspended")
    }

    @Test fun observesExpiredNotificationSurvivingSkipAndReconcile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("repeat_review", "Review", NotificationManager.IMPORTANCE_DEFAULT))
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        val now = ZonedDateTime.now()
        val local = TrackerRepository(db, now = { now.toInstant().toEpochMilli() })
        val id = "repeat_review_expired"
        try {
            val course = local.createCourse("Review C", 0)
            val minute = (now.hour * 60 + now.minute + 1).coerceAtMost(1439)
            local.saveInitialSchedule(course.id, listOf(WeeklyRule(now.dayOfWeek.value, minute)))
            db.trackerDao().insertSession(SessionEntity(id, course.id, now.toLocalDate().minusDays(2).toEpochDay(),
                600, course.name, course.colorId, 1, 1))
            manager.notify(id, 2, Notification.Builder(context, "repeat_review").setSmallIcon(R.drawable.ic_book)
                .setContentTitle(course.name).setContentText("Expired result question").build())
            val deadline = System.currentTimeMillis() + 5_000
            while (manager.activeNotifications.none { it.tag == id } && System.currentTimeMillis() < deadline) delay(50)
            assertTrue(manager.activeNotifications.any { it.tag == id })
            ReminderScheduler.skipPending(context, id, local)
            assertEquals(SessionResult.PENDING, db.trackerDao().getSession(id)!!.result)
            assertTrue("Observed defect: expired question survives ignored action", manager.activeNotifications.any { it.tag == id })
            ReminderScheduler.reconcile(context, local)
            assertTrue("Observed defect: eligibility ignores closed edit window", manager.activeNotifications.any { it.tag == id })
            println("REPEAT_PROBE expired PENDING action ignored; notification remains after skip and reconcile")
        } finally {
            manager.cancel(id, 2)
            db.close()
            ReminderScheduler.reconcile(context, AppContainer.repository(context))
        }
    }
}
