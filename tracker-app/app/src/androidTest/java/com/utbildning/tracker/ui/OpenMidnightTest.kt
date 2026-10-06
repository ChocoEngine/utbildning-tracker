package com.utbildning.tracker.ui

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.utbildning.tracker.MainActivity
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

/** Run near 23:59 on a dedicated RTC-shifted AVD; no synthetic TIME_SET broadcast. */
class OpenMidnightTest {
    private fun texts(node: AccessibilityNodeInfo?): List<String> = if (node == null) emptyList() else
        listOfNotNull(node.text?.toString()) + (0 until node.childCount).flatMap { texts(node.getChild(it)) }
    private fun screenTexts() = texts(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
    @Test fun openActivityRollsIntoNextDayWithoutResumeOrRestart() = runBlocking {
        val now = ZonedDateTime.now()
        assumeTrue("Dedicated AVD near midnight", now.hour == 23 && now.minute == 59 && now.second < 45)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = AppContainer.repository(context)
        val db = TrackerRepository::class.java.getDeclaredField("database").let { it.isAccessible = true; it.get(repo) as TrackerDatabase }
        val dao = db.trackerDao()
        val today = now.toLocalDate()
        val course = repo.createCourse("Midnight rollover", repo.availableColors().first())
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            repo.saveInitialSchedule(course.id, listOf(WeeklyRule(today.plusDays(1).dayOfWeek.value, 720)))
            val id = "midnight_${UUID.randomUUID()}"
            val oldId = "midnight_old_${UUID.randomUUID()}"
            val stamp = System.currentTimeMillis()
            dao.insertSession(SessionEntity(id, course.id, today.toEpochDay(), 0, course.name, course.colorId, stamp, stamp))
            dao.insertSession(SessionEntity(oldId, course.id, today.minusDays(1).toEpochDay(), 0, course.name, course.colorId, stamp, stamp))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val headerDeadline = System.currentTimeMillis() + 10_000
            var header: String? = null
            while (header == null && System.currentTimeMillis() < headerDeadline) {
                header = screenTexts().firstOrNull { it.startsWith("TODAY ·") || it.startsWith("СЕГОДНЯ ·") }
                delay(100)
            }
            assertNotNull("Real Activity today's header", header)
            assertEquals(SessionResult.PENDING, dao.getSession(oldId)!!.result)
            val midnight = today.plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
            while (System.currentTimeMillis() < midnight + 2_000) delay(100)
            val uiDeadline = System.currentTimeMillis() + 15_000
            fun rolled() = screenTexts().any { (it.startsWith("YESTERDAY ·") || it.startsWith("ВЧЕРА ·")) &&
                it.substringAfter('·').trim() == header!!.substringAfter('·').trim() }
            while (!rolled() && System.currentTimeMillis() < uiDeadline) delay(100)
            assertTrue("Yesterday header after real midnight: ${screenTexts()}", rolled())
            val deadline = System.currentTimeMillis() + 10_000
            while (dao.getSession(oldId)!!.result != SessionResult.SKIPPED && System.currentTimeMillis() < deadline) delay(100)
            assertEquals(SessionResult.SKIPPED, dao.getSession(oldId)!!.result)
            assertEquals(SessionResult.PENDING, dao.getSession(id)!!.result)
            assertTrue(dao.getSessions(course.id).groupBy { it.date }.values.all { it.size == 1 })
            assertFalse(dao.getCourse(course.id)!!.isCompleted)
        } finally {
            scenario?.close()
            repo.deleteCourse(course.id)
        }
    }
}
