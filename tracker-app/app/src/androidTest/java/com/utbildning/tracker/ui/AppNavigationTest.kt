package com.utbildning.tracker.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.MainActivity
import com.utbildning.tracker.data.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsReturnsToEachOriginTab() {
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
        compose.onNodeWithTag("nav_courses").performClick()
        repeat(3) { compose.onNodeWithTag("nav_courses").performClick() }
        compose.onNodeWithTag("settings").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("screen_settings").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("screen_courses").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("screen_today").assertIsDisplayed()
    }

    @Test
    fun newScheduledCourseCanBeConfiguredThroughCourseEditorAndCreatesCalendarSessions() {
        val repository = AppContainer.repository(compose.activity)
        val name = "Schedule flow ${System.currentTimeMillis()}"
        compose.onNodeWithTag("nav_courses").performClick()
        compose.onNodeWithTag("course_add").performClick()
        compose.onNodeWithTag("course_name").performTextInput(name)
        compose.onNodeWithTag("course_continue").performClick()
        compose.waitUntil(10_000) { runBlocking { repository.observeCourses().first().any { it.name == name } } }
        val course = runBlocking { repository.observeCourses().first().single { it.name == name } }
        try {
            waitForTag("course_schedule")
            compose.onNodeWithTag("course_schedule", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("screen_schedule").assertIsDisplayed()
            compose.onNodeWithTag("schedule_day_1").performScrollTo().performClick()
            compose.onNodeWithTag("back").performClick()
            assert(runBlocking { repository.getSchedule(course.id) == null })
            compose.onNodeWithTag("course_schedule", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("schedule_day_1").performScrollTo().performClick()
            compose.onNodeWithTag("schedule_save").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.getSchedule(course.id) != null } }
            val countBeforeRestart = runBlocking { repository.observeSessions().first().count { it.courseId == course.id } }
            assert(countBeforeRestart > 0)
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { runCatching { runBlocking { repository.getSchedule(course.id) != null } }.getOrDefault(false) }
            runBlocking { repository.synchronize() }
            assertEquals(countBeforeRestart, runBlocking { repository.observeSessions().first().count { it.courseId == course.id } })
        } finally { runBlocking { repository.deleteCourse(course.id) } }
    }

    @Test fun pausedCourseCanResumeThroughScheduleWithoutLosingProgress() {
        val repository = AppContainer.repository(compose.activity)
        val course = runBlocking {
            repository.createCourse("Resume flow", repository.availableColors().first(), topics = listOf("C basics", "Pointers"))
        }
        val topics = runBlocking { repository.getCourseDetails(course.id)!!.topics }
        runBlocking {
            repository.toggleTopicCompletion(course.id, topics.first().id)
            repository.saveInitialSchedule(course.id, listOf(com.utbildning.tracker.domain.WeeklyRule(2, 1200)))
            repository.pauseCourse(course.id)
        }
        try {
            compose.onNodeWithTag("nav_courses").performClick()
            compose.onNodeWithTag("course_row_${course.id}").performScrollTo().performClick()
            compose.onNodeWithTag("course_schedule").assertDoesNotExist()
            compose.onNodeWithTag("course_resume").performClick()
            compose.waitUntil(10_000) { runBlocking { !repository.getCourse(course.id)!!.isPaused } }
            waitForTag("course_schedule")
            compose.onNodeWithTag("course_schedule").assertIsDisplayed().performClick()
            compose.onNodeWithTag("schedule_day_3").performScrollTo().performClick()
            pressBack()
            org.junit.Assert.assertFalse(runBlocking { repository.getCourse(course.id)!!.isPaused })
            waitForTag("course_schedule")
            compose.onNodeWithTag("course_schedule").performClick()
            compose.onNodeWithTag("schedule_day_3").performScrollTo().performClick()
            compose.onNodeWithTag("schedule_save").performClick()
            compose.waitUntil(10_000) { runBlocking { !repository.getCourse(course.id)!!.isPaused } }
            val details = runBlocking { repository.getCourseDetails(course.id)!! }
            assertEquals(course.colorId, details.course.colorId)
            assertEquals(1, details.topics.count { it.isCompleted })
            assertEquals(listOf(3), runBlocking { repository.getScheduleRules(course.id).map { it.dayOfWeek } })
            org.junit.Assert.assertTrue(runBlocking { repository.observeSessions().first().any { it.courseId == course.id } })
        } finally { runBlocking { repository.deleteCourse(course.id) } }
    }

    @Test fun courseEditorHidesTabsAndReturningToListRestoresThem() {
        val repository = AppContainer.repository(compose.activity)
        val course = runBlocking {
            repository.createCourse("Navigation flow", repository.availableColors().first())
        }
        fun assertTabsHidden() {
            for (tab in listOf("today", "calendar", "courses")) {
                compose.onNodeWithTag("nav_$tab").assertDoesNotExist()
            }
        }
        try {
            compose.onNodeWithTag("nav_courses").performClick()
            compose.onNodeWithTag("course_row_${course.id}").performScrollTo().performClick()
            waitForTag("course_schedule")
            assertTabsHidden()
            compose.onNodeWithTag("course_schedule").performClick()
            compose.onNodeWithTag("screen_schedule").assertIsDisplayed()
            assertTabsHidden()
            pressBack()
            waitForTag("course_schedule")
            assertTabsHidden()
            compose.activityRule.scenario.recreate()
            waitForTag("course_schedule")
            assertTabsHidden()
            pressBack()
            waitForTag("nav_courses")
            for (tab in listOf("today", "calendar", "courses")) {
                compose.onNodeWithTag("nav_$tab").assertIsDisplayed()
            }
        } finally { runBlocking { repository.deleteCourse(course.id) } }
    }

    @Test
    fun existingScheduledCourseWithoutScheduleCanBeConfigured() {
        val repository = AppContainer.repository(compose.activity)
        val course = runBlocking {
            repository.createCourse("Existing schedule flow ${System.currentTimeMillis()}", repository.availableColors().first())
        }
        try {
            compose.onNodeWithTag("nav_courses").performClick()
            compose.onNodeWithTag("course_row_${course.id}").performScrollTo().assertIsDisplayed().performClick()
            waitForTag("course_schedule")
            compose.onNodeWithTag("course_schedule", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("screen_schedule").assertIsDisplayed()
            compose.onNodeWithTag("schedule_day_2").performScrollTo().performClick()
            compose.onNodeWithTag("schedule_save").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.getSchedule(course.id) != null } }
            assert(runBlocking { repository.observeSessions().first().any { it.courseId == course.id } })
        } finally { runBlocking { repository.deleteCourse(course.id) } }
    }

    @Test fun unscheduledCourseCanCancelEnableAndEditThroughPencil() {
        val repository = AppContainer.repository(compose.activity)
        val course = runBlocking {
            repository.createCourse("Mode flow ${System.currentTimeMillis()}", repository.availableColors().first(), topics = listOf("C basics"))
        }
        try {
            compose.onNodeWithTag("nav_courses").performClick()
            compose.onNodeWithTag("course_row_${course.id}").performScrollTo().performClick()
            waitForTag("course_schedule")
            compose.onNodeWithTag("course_schedule").performClick()
            compose.onNodeWithTag("schedule_day_2").performScrollTo().performClick()
            compose.onNodeWithTag("back").performClick()
            assertNotNull(runBlocking { repository.getCourse(course.id) })
            waitForTag("course_schedule")
            compose.onNodeWithTag("course_schedule").performClick()
            compose.onNodeWithTag("schedule_day_2").performScrollTo().performClick()
            compose.onNodeWithTag("schedule_save").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.getSchedule(course.id) != null } }
            waitForTag("course_schedule")
            compose.onNodeWithTag("course_disable_schedule").assertDoesNotExist()
            compose.activityRule.scenario.recreate()
            waitForTag("course_schedule")
            compose.onNodeWithTag("course_schedule").performClick()
            compose.onNodeWithTag("schedule_day_2").performScrollTo().performClick()
            compose.onNodeWithTag("schedule_day_3").performScrollTo().performClick()
            compose.onNodeWithTag("schedule_save").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.getSchedule(course.id) != null } }
            compose.waitUntil(10_000) { runBlocking { repository.getScheduleRules(course.id).map { it.dayOfWeek } == listOf(3) } }
            waitForTag("course_schedule")
            compose.onNodeWithTag("course_schedule").performClick()
            compose.onNodeWithTag("schedule_day_3").performScrollTo().performClick()
            compose.onNodeWithTag("schedule_save").performClick()
            compose.waitUntil(10_000) { runBlocking { repository.getSchedule(course.id) == null } }
            waitForTag("course_cancel")
            compose.onNodeWithTag("course_cancel").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("course_cancel").fetchSemanticsNodes().isEmpty() }
        } finally { runBlocking { repository.deleteCourse(course.id) } }
    }

    private fun waitForTag(tag: String) {
        try { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
        catch (failure: Throwable) { throw AssertionError(compose.onRoot().printToString(), failure) }
    }

    private fun pressBack() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
