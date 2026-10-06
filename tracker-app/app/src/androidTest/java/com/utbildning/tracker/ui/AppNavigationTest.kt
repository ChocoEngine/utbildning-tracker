package com.utbildning.tracker.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.MainActivity
import com.utbildning.tracker.data.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private var scenario: ActivityScenario<MainActivity>? = null
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @After fun closeActivity() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun settingsReturnsToEachOriginTab() {
        launch()
        for (tab in listOf("today", "calendar", "courses")) {
            compose.onNodeWithTag("nav_$tab").performClick()
            compose.onNodeWithTag("screen_$tab").assertIsDisplayed()
            compose.onNodeWithTag("nav_$tab").assertIsSelected()
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("screen_settings").assertIsDisplayed()
            compose.onNodeWithTag("back").performClick()
            compose.onNodeWithTag("screen_$tab").assertIsDisplayed()
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("screen_settings").assertIsDisplayed()
            pressBack()
            compose.onNodeWithTag("screen_$tab").assertIsDisplayed()
        }
    }

    @Test
    fun repeatedTabSelectionDoesNotTrapBackAndRecreationKeepsDestination() {
        launch()
        compose.onNodeWithTag("nav_courses").performClick()
        repeat(3) { compose.onNodeWithTag("nav_courses").performClick() }
        compose.onNodeWithTag("settings").performClick()
        scenario!!.recreate()
        compose.onNodeWithTag("screen_settings").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("screen_courses").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("screen_today").assertIsDisplayed()
    }

    @Test
    fun newScheduledCourseCanBeConfiguredThroughCourseEditorAndCreatesCalendarSessions() {
        launch()
        val repository = AppContainer.repository(context)
        val name = "Schedule flow ${System.currentTimeMillis()}"
        compose.onNodeWithTag("nav_courses").performClick()
        compose.onNodeWithTag("course_add").performClick()
        compose.onNodeWithTag("course_name").performTextInput(name)
        compose.onNodeWithTag("course_continue").performClick()
        compose.waitUntil(10_000) { runBlocking { repository.observeCourses().first().any { it.name == name } } }
        val course = runBlocking { repository.observeCourses().first().single { it.name == name } }
        try {
            waitForTag("course_schedule")
            openSchedule()
            compose.onNodeWithTag("schedule_day_1").performScrollTo().performClick()
            compose.onNodeWithTag("back").performClick()
            assert(runBlocking { repository.getSchedule(course.id) == null })
            openSchedule()
            compose.onNodeWithTag("schedule_day_1").performScrollTo().performClick()
            compose.onNodeWithTag("schedule_save").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.getSchedule(course.id) != null } }
            val countBeforeRestart = runBlocking { repository.observeSessions().first().count { it.courseId == course.id } }
            assert(countBeforeRestart > 0)
            scenario!!.recreate()
            compose.waitUntil(10_000) { runCatching { runBlocking { repository.getSchedule(course.id) != null } }.getOrDefault(false) }
            runBlocking { repository.synchronize() }
            assertEquals(countBeforeRestart, runBlocking { repository.observeSessions().first().count { it.courseId == course.id } })
        } finally {
            closeActivity()
            runBlocking { repository.deleteCourse(course.id) }
        }
    }

    @Test fun pausedCourseCanResumeThroughScheduleWithoutLosingProgress() {
        val repository = AppContainer.repository(context)
        val course = runBlocking {
            repository.createCourse("Resume flow", repository.availableColors().first(), topics = listOf("C basics", "Pointers"))
        }
        val topics = runBlocking { repository.getCourseDetails(course.id)!!.topics }
        runBlocking {
            repository.toggleTopicCompletion(course.id, topics.first().id)
            repository.saveInitialSchedule(course.id, listOf(com.utbildning.tracker.domain.WeeklyRule(2, 1200)))
            repository.pauseCourse(course.id)
        }
        launch()
        try {
            compose.onNodeWithTag("nav_courses").performClick()
            compose.waitForIdle()
            openCourse(course.id)
            compose.waitForIdle()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("course_resume", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("course_schedule").assertDoesNotExist()
            clickCourseAction("course_resume")
            compose.waitUntil(10_000) { runBlocking { !repository.getCourse(course.id)!!.isPaused } }
            waitForTag("course_schedule")
            openSchedule()
            compose.onNodeWithTag("schedule_day_3").performScrollTo().performClick()
            compose.waitForIdle()
            pressBack()
            org.junit.Assert.assertFalse(runBlocking { repository.getCourse(course.id)!!.isPaused })
            waitForTag("course_schedule")
            openSchedule()
            compose.onNodeWithTag("schedule_day_3").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("schedule_save").performClick()
            compose.waitForIdle()
            compose.waitUntil(10_000) { runBlocking { !repository.getCourse(course.id)!!.isPaused } }
            val details = runBlocking { repository.getCourseDetails(course.id)!! }
            assertEquals(course.colorId, details.course.colorId)
            assertEquals(1, details.topics.count { it.isCompleted })
            assertEquals(listOf(3), runBlocking { repository.getScheduleRules(course.id).map { it.dayOfWeek } })
            org.junit.Assert.assertTrue(runBlocking { repository.observeSessions().first().any { it.courseId == course.id } })
        } finally {
            closeActivity()
            runBlocking { repository.deleteCourse(course.id) }
        }
    }

    @Test fun courseEditorHidesTabsAndReturningToListRestoresThem() {
        val repository = AppContainer.repository(context)
        val course = runBlocking {
            repository.createCourse("Navigation flow", repository.availableColors().first())
        }
        fun assertTabsHidden() {
            for (tab in listOf("today", "calendar", "courses")) {
                compose.onNodeWithTag("nav_$tab").assertDoesNotExist()
            }
        }
        launch()
        try {
            compose.onNodeWithTag("nav_courses").performClick()
            openCourse(course.id)
            waitForTag("course_schedule")
            assertTabsHidden()
            openSchedule()
            assertTabsHidden()
            pressBack()
            waitForTag("course_schedule")
            assertTabsHidden()
            scenario!!.recreate()
            waitForTag("course_schedule")
            assertTabsHidden()
            pressBack()
            waitForTag("nav_courses")
            for (tab in listOf("today", "calendar", "courses")) {
                compose.onNodeWithTag("nav_$tab").assertIsDisplayed()
            }
        } finally {
            closeActivity()
            runBlocking { repository.deleteCourse(course.id) }
        }
    }

    @Test
    fun existingScheduledCourseWithoutScheduleCanBeConfigured() {
        val repository = AppContainer.repository(context)
        val course = runBlocking {
            repository.createCourse("Existing schedule flow ${System.currentTimeMillis()}", repository.availableColors().first())
        }
        launch()
        try {
            compose.onNodeWithTag("nav_courses").performClick()
            openCourse(course.id)
            waitForTag("course_schedule")
            openSchedule()
            compose.onNodeWithTag("schedule_day_2").performScrollTo().performClick()
            compose.onNodeWithTag("schedule_save").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.getSchedule(course.id) != null } }
            assert(runBlocking { repository.observeSessions().first().any { it.courseId == course.id } })
        } finally {
            closeActivity()
            runBlocking { repository.deleteCourse(course.id) }
        }
    }

    @Test fun unscheduledCourseCanCancelEnableAndEditThroughPencil() {
        val repository = AppContainer.repository(context)
        val course = runBlocking {
            repository.createCourse("Mode flow ${System.currentTimeMillis()}", repository.availableColors().first(), topics = listOf("C basics"))
        }
        launch()
        try {
            compose.onNodeWithTag("nav_courses").performClick()
            openCourse(course.id)
            waitForTag("course_schedule")
            openSchedule()
            compose.onNodeWithTag("schedule_day_2").performScrollTo().performClick()
            compose.onNodeWithTag("back").performClick()
            assertNotNull(runBlocking { repository.getCourse(course.id) })
            waitForTag("course_schedule")
            openSchedule()
            compose.onNodeWithTag("schedule_day_2").performScrollTo().performClick()
            compose.onNodeWithTag("schedule_save").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.getSchedule(course.id) != null } }
            waitForTag("course_schedule")
            compose.onNodeWithTag("course_disable_schedule").assertDoesNotExist()
            scenario!!.recreate()
            waitForTag("course_schedule")
            openSchedule()
            compose.onNodeWithTag("schedule_day_2").performScrollTo().performClick()
            compose.onNodeWithTag("schedule_day_3").performScrollTo().performClick()
            compose.onNodeWithTag("schedule_save").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.getSchedule(course.id) != null } }
            compose.waitUntil(10_000) { runBlocking { repository.getScheduleRules(course.id).map { it.dayOfWeek } == listOf(3) } }
            waitForTag("course_schedule")
            openSchedule()
            compose.onNodeWithTag("schedule_day_3").performScrollTo().performClick()
            compose.onNodeWithTag("schedule_save").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.getSchedule(course.id) == null } }
            waitForTag("course_cancel")
            compose.onNodeWithTag("course_cancel").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("course_cancel").fetchSemanticsNodes().isEmpty() }
        } finally {
            closeActivity()
            runBlocking { repository.deleteCourse(course.id) }
        }
    }

    private fun openCourse(id: String) {
        waitForDisplayedTag("screen_courses")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("course_row_$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("course_row_$id", useUnmergedTree = true).performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun clickCourseAction(tag: String) {
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().assertIsEnabled()
            }.isSuccess
        }
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsEnabled().performClick()
        compose.waitForIdle()
    }

    private fun openSchedule() {
        clickCourseAction("course_schedule")
        waitForDisplayedTag("screen_schedule")
    }

    private fun waitForDisplayedTag(tag: String) {
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun waitForTag(tag: String) {
        try { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
        catch (failure: Throwable) { throw AssertionError(compose.onRoot().printToString(), failure) }
    }

    private fun pressBack() {
        compose.waitForIdle()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
