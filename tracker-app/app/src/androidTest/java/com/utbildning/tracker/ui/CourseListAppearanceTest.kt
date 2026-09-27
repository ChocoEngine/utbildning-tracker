package com.utbildning.tracker.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.R
import com.utbildning.tracker.data.local.CourseEntity
import com.utbildning.tracker.data.local.ScheduleRuleEntity
import com.utbildning.tracker.ui.courses.CourseListContent
import com.utbildning.tracker.ui.theme.TrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseListAppearanceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun pausedAndFinishedHaveDistinctMarkersAndProgress() {
        val courses = listOf(
            CourseEntity("paused", "Paused C", 0, 1, 1, isPaused = true),
            CourseEntity("finished", "Finished C", null, 1, 1, isCompleted = true, completedAt = 1),
        )
        compose.setContent { TrackerTheme { Surface { CourseListContent(courses, emptyList(), mapOf("paused" to (1 to 4), "finished" to (4 to 4)), true, {}, {}) } } }
        compose.onNodeWithText(context.getString(R.string.course_topics_progress, 1, 4) + " · " + context.getString(R.string.course_paused_status)).assertExists()
        compose.onNodeWithText("25%").assertExists()
        compose.onNodeWithTag("course_progress_paused", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("course_progress_finished").assertDoesNotExist()
        compose.onNodeWithText("✓").assertExists()
        compose.onNodeWithText(context.getString(R.string.course_finished_status)).assertExists()
    }

    @Test fun courseWithoutTopicsShowsScheduleAndNoSessionCount() {
        val course = CourseEntity("practice", "Practice C", 1, 1, 1)
        compose.setContent { TrackerTheme { Surface { CourseListContent(listOf(course), emptyList(), mapOf(course.id to (12 to 0)), false, {}, {}, mapOf(course.id to listOf(ScheduleRuleEntity(course.id, 2, 19 * 60)))) } } }
        compose.onNodeWithText("19:00", substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.course_sessions, 12)).assertDoesNotExist()
        compose.onNodeWithTag("course_progress_practice").assertDoesNotExist()
        compose.onNodeWithTag("courses_filter").assertDoesNotExist()
    }
    @Test fun scrollingCoursesKeepsAddAndSummaryInPlace() {
        val courses = (0..15).map { index ->
            if (index < 8) CourseEntity("course-$index", "C $index", index, 1, 1)
            else CourseEntity("course-$index", "C $index", null, 1, 1, isCompleted = true, completedAt = 1)
        }
        compose.setContent { TrackerTheme { Surface { CourseListContent(courses, emptyList(), emptyMap(), true, {}, {}) } } }
        val addBounds = compose.onNodeWithTag("course_add").fetchSemanticsNode().boundsInRoot
        val filterBounds = compose.onNodeWithTag("courses_filter").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("course_row_course-15").performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertEquals(addBounds, compose.onNodeWithTag("course_add").fetchSemanticsNode().boundsInRoot)
        org.junit.Assert.assertEquals(filterBounds, compose.onNodeWithTag("courses_filter").fetchSemanticsNode().boundsInRoot)
    }

}
