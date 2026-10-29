package com.utbildning.tracker.notifications

import android.app.LocaleManager
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.utbildning.tracker.data.AppContainer
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.SessionEntity
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.data.local.TrackerDatabase
import com.utbildning.tracker.domain.WeeklyRule
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** SystemUI accessibility clicks, rather than invoking action PendingIntents directly. */
class NotificationShadeResultTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }

    private suspend fun clickSystemText(text: String, reopenNotifications: Boolean = false, notificationTitle: String? = null) {
        val deadline = System.currentTimeMillis() + 10_000
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString().equals(text, ignoreCase = true) || node.contentDescription?.toString() == text) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        fun containsTitle(node: AccessibilityNodeInfo): Boolean =
            node.text?.toString() == notificationTitle ||
                (0 until node.childCount).any { index -> node.getChild(index)?.let(::containsTitle) == true }
        fun notificationRow(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.viewIdResourceName == "com.android.systemui:id/expandableNotificationRow" && containsTitle(node)) return node
            for (index in 0 until node.childCount) notificationRow(node.getChild(index))?.let { return it }
            return null
        }
        while (System.currentTimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val searchRoot = if (notificationTitle == null) root else notificationRow(root)
            find(searchRoot)?.let { node ->
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty && node.isVisibleToUser) {
                    var target: AccessibilityNodeInfo? = node
                    while (target != null && !target.isClickable) target = target.parent
                    // SystemUI may replace the action node while expanding or updating.
                    // Retry only a rejected click; never dispatch a second accepted action.
                    if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
                }
            }
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/expand_button")
                ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (reopenNotifications) shell("cmd statusbar expand-notifications")
            delay(100)
        }
        fun tree(node: AccessibilityNodeInfo?): String = if (node == null) "null" else
            "${node.viewIdResourceName}:${node.text}:${node.contentDescription}\n" +
                (0 until node.childCount).joinToString("") { tree(node.getChild(it)) }
        fail("System UI text not clickable: $text\n${tree(automation.rootInActiveWindow)}")
    }

    private suspend fun waitForCheckedTopic(title: String) {
        fun containsTitle(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.text?.toString() == title || node.contentDescription?.toString() == title) return true
            return (0 until node.childCount).any { containsTitle(node.getChild(it)) }
        }
        fun checked(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isCheckable && node.isChecked && containsTitle(node)) return true
            return (0 until node.childCount).any { checked(node.getChild(it)) }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (!checked(automation.rootInActiveWindow) && System.currentTimeMillis() < deadline) delay(50)
        fun tree(node: AccessibilityNodeInfo?): String = if (node == null) "null" else
            "${node.text}:${node.contentDescription}:checkable=${node.isCheckable}:checked=${node.isChecked}\n" +
                (0 until node.childCount).joinToString("") { tree(node.getChild(it)) }
        assertTrue("Topic must be visibly selected before saving: $title\n${tree(automation.rootInActiveWindow)}", checked(automation.rootInActiveWindow))
    }

    @Test fun bothShadeActionsForTodayAndYesterdayWithAndWithoutTopicsInRussianAndEnglish() = runBlocking {
        val time = ZonedDateTime.now()
        assumeTrue("Matrix needs a stable non-midnight result minute", time.hour * 60 + time.minute in 2..1437)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val locales = context.getSystemService(LocaleManager::class.java)
        val original = locales.applicationLocales
        val manager = context.getSystemService(NotificationManager::class.java)
        val repo = AppContainer.repository(context)
        val db = TrackerRepository::class.java.getDeclaredField("database").let { it.isAccessible = true; it.get(repo) as TrackerDatabase }
        val dao = db.trackerDao()
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set --uid ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        try {
            for (language in listOf("ru", "en")) {
                locales.applicationLocales = LocaleList.forLanguageTags(language)
                assertEquals(language, locales.applicationLocales.toLanguageTags())
                // This matrix posts a question directly to test SystemUI actions. Give
                // that fixture explicit resources; real AlarmManager/receiver language
                // propagation is independently covered by LocalizedReminderDeliveryTest.
                // A cached Application Context can be reconfigured when topic Activities
                // open/close, so it is not a stable source for this fixture's language.
                val deliveryContext = context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply {
                        setLocales(LocaleList.forLanguageTags(language))
                    },
                )
                for (yesterday in listOf(false, true)) for (withTopics in listOf(false, true)) {
                    for (result in listOf(SessionResult.DONE, SessionResult.SKIPPED)) {
                        val course = repo.createCourse("Shade $language $yesterday $withTopics $result", repo.availableColors().first(),
                            topics = if (withTopics) listOf("First topic", "Second topic") else emptyList())
                        try {
                            val now = ZonedDateTime.now().withSecond(0).withNano(0)
                            repo.saveInitialSchedule(course.id, listOf(WeeklyRule(now.plusDays(2).dayOfWeek.value, 720)))
                            val date = now.toLocalDate().minusDays(if (yesterday) 1 else 0)
                            val minute = now.hour * 60 + now.minute
                            val id = "shade_${UUID.randomUUID()}"
                            // Yesterday's question uses an overnight end; today's starts one minute earlier.
                            val start = if (yesterday) minute + 1 else minute - 1
                            assertTrue("Run away from midnight", minute in 2..1437)
                            val created = System.currentTimeMillis()
                            dao.insertSession(SessionEntity(id, course.id, date.toEpochDay(), start,
                                course.name, course.colorId, created, created, endMinute = minute))
                            assertEquals("App language before $result ${course.name}", language, locales.applicationLocales[0].language)
                            val actionId = id
                            ReminderScheduler.receive(deliveryContext, now.toInstant().toEpochMilli(), repo)
                            val postedDeadline = System.currentTimeMillis() + 10_000
                            while (manager.activeNotifications.none { it.tag == actionId } && System.currentTimeMillis() < postedDeadline) delay(50)
                            assertTrue("Notification posted: ${course.name} $actionId", manager.activeNotifications.any { it.tag == actionId })
                            shell("cmd statusbar expand-notifications")
                            automation.waitForIdle(300, 5_000)
                            clickSystemText(if (language == "ru") {
                                if (result == SessionResult.DONE) "Пройдено" else "Пропущено"
                            } else if (result == SessionResult.DONE) "Done" else "Skipped", reopenNotifications = true, notificationTitle = course.name)
                            shell("cmd statusbar collapse")
                            if (result == SessionResult.DONE && withTopics) {
                                assertEquals(SessionResult.PENDING, dao.getSession(actionId)!!.result)
                                clickSystemText("First topic")
                                waitForCheckedTopic("First topic")
                                clickSystemText(if (language == "ru") "Пройдено" else "Done")
                            }
                            val resultDeadline = System.currentTimeMillis() + 10_000
                            while (dao.getSession(actionId)!!.result != result && System.currentTimeMillis() < resultDeadline) delay(50)
                            assertEquals("${course.name} $actionId", result, dao.getSession(actionId)!!.result)
                            val cancelDeadline = System.currentTimeMillis() + 5_000
                            while (manager.activeNotifications.any { it.tag == actionId } && System.currentTimeMillis() < cancelDeadline) delay(50)
                            assertTrue(manager.activeNotifications.none { it.tag == actionId })
                            if (withTopics) {
                                assertEquals("${course.name}: selected topic after $result", result == SessionResult.DONE, dao.getTopics(course.id)[0].isCompleted)
                                assertEquals(if (result == SessionResult.DONE) date.toEpochDay() else null,
                                    dao.getTopics(course.id)[0].completionDate)
                                assertFalse(dao.getTopics(course.id)[1].isCompleted)
                            }
                        } finally {
                            repo.deleteCourse(course.id)
                            instrumentation.runOnMainSync {
                                listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).flatMap {
                                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
                                }.distinct().forEach { it.finishAndRemoveTask() }
                            }
                        }
                    }
                }
            }
        } finally {
            shell("cmd statusbar collapse")
            locales.applicationLocales = original
            ReminderScheduler.reconcile(context, repo)
        }
    }
}
