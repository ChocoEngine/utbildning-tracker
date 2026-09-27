package com.utbildning.tracker.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.ui.courses.CoursesScreen
import com.utbildning.tracker.ui.courses.CoursesViewModel
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoursesScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: TrackerDatabase
    private lateinit var repository: TrackerRepository
    private val modelOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    private var modelJob: Job? = null
    private val dao get() = database.trackerDao()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        repository = TrackerRepository(database)
    }

    @After fun tearDown() {
        // ActivityScenario closes after @After. Stop and join our observers before closing Room.
        compose.runOnIdle { modelOwner.viewModelStore.clear() }
        runBlocking { modelJob?.join() }
        database.close()
    }

    @Test fun creationDialogValidatesNameAndCreatesCourseWithoutMode() {
        show("ru")
        click("course_add")
        click("course_continue")
        waitFor { compose.onAllNodesWithTag("course_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("course_error").assertExists()
        assertTrue(courses().isEmpty())
        text("course_name", "A".repeat(51))
        click("course_continue")
        assertTrue(courses().isEmpty())
        text("course_name", "Лекции C")
        text("course_category", "Программирование")
        compose.onNodeWithTag("category_menu").assertDoesNotExist()
        click("course_continue")
        compose.onNodeWithTag("course_title").assertTextEquals("Лекции C")
        compose.onNodeWithTag("settings").assertDoesNotExist()
        compose.onNodeWithTag("course_save").assertDoesNotExist()
        waitFor { courses().size == 1 }
        assertEquals("Программирование", categories().single().name)
    }

    @Test fun courseCanBeCreatedWithoutTopicsOrScheduleAndTopicsAddedLater() {
        show()
        click("course_add")
        text("course_name", "C practice")
        click("course_continue")
        waitFor { courses().size == 1 }
        val saved = courses().single()
        assertTrue(runBlocking { dao.getTopics(saved.id) }.isEmpty())
        assertNull(runBlocking { dao.getSchedule(saved.id) })
        compose.onNodeWithTag("course_schedule").assertExists()
        compose.onNodeWithTag("mode_unscheduled").assertDoesNotExist()
        click("topics_edit")
        click("topics_apply")
        assertTrue(runBlocking { dao.getTopics(saved.id) }.isEmpty())
        click("topics_edit")
        text("topics_input", "Pointers\nArrays")
        click("topics_apply")
        waitFor { runBlocking { dao.getTopics(saved.id) }.size == 2 }
    }

    @Test fun cancelInitialDialogDoesNotCreateCourseOrCategory() {
        show()
        click("course_add")
        text("course_name", "Discarded")
        text("course_category", "Discarded category")
        click("course_cancel")
        assertTrue(courses().isEmpty()); assertTrue(categories().isEmpty())
    }

    @Test fun nameCommitsOnFocusLossAndLeavingAndRejectsInvalidValue() {
        val course = seed()
        show()
        click("course_row_${course.id}")
        rename("On blur")
        click("course_category")
        waitFor { runBlocking { dao.getCourse(course.id) }?.name == "On blur" }
        rename("On exit")
        click("course_cancel")
        waitFor { runBlocking { dao.getCourse(course.id) }?.name == "On exit" }
        click("course_row_${course.id}")
        rename("A".repeat(51))
        click("course_cancel")
        waitFor { compose.onAllNodesWithTag("course_error").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("On exit", runBlocking { dao.getCourse(course.id) }?.name)
        compose.onNodeWithTag("course_title").assertTextEquals("A".repeat(51))
        rename("Corrected")
        click("course_cancel")
        waitFor { runBlocking { dao.getCourse(course.id) }?.name == "Corrected" }
    }

    @Test fun nameSavesOnOutsideTouchWithoutSwallowingColorAction() {
        val course = seed()
        show(); click("course_row_${course.id}")
        rename("Saved on background")
        compose.onNodeWithTag("course_editor").performTouchInput { click(androidx.compose.ui.geometry.Offset(2f, 2f)) }
        waitFor { runBlocking { dao.getCourse(course.id) }?.name == "Saved on background" }
        compose.onNodeWithTag("course_title").assertTextEquals("Saved on background")
        rename("Saved with color")
        compose.onNodeWithTag("color_3").performTouchInput { click() }
        waitFor { runBlocking { dao.getCourse(course.id) }?.let { it.name == "Saved with color" && it.colorId == 3 } == true }
    }

    @Test fun appliedTopicsPersistImmediatelyAndCancelOnlyDiscardsTextarea() {
        val original = seed(topics = listOf("Pointers", "Arrays"))
        val before = runBlocking { dao.getTopics(original.id) }.associateBy { it.title }
        show(); click("course_row_${original.id}")
        click("topics_edit"); text("topics_input", "Discarded"); click("topics_cancel")
        assertEquals(before.values.toList(), runBlocking { dao.getTopics(original.id) })
        click("topics_edit"); text("topics_input", "Arrays\nPointers\nFunctions"); click("topics_apply")
        waitFor { runBlocking { dao.getTopics(original.id) }.size == 3 }
        val after = runBlocking { dao.getTopics(original.id) }
        assertEquals(listOf("Arrays", "Pointers", "Functions"), after.map { it.title })
        assertEquals(before.getValue("Arrays").id, after[0].id)
        click("course_cancel"); click("course_row_${original.id}")
        compose.onNodeWithTag("topic_${after[2].id}").assertExists()
    }

    @Test fun categorySuggestionsKeepFocusAndDoNotSavePartialInput() {
        val course = seed()
        runBlocking { dao.insertCategory(CategoryEntity("existing", "Programming")) }
        show(); click("course_row_${course.id}")
        click("course_category")
        val field = compose.onNodeWithTag("course_category")
        field.assertIsFocused()
        for (letter in "Programming new") {
            field.performTextInput(letter.toString())
            field.assertIsFocused()
            assertNull(runBlocking { dao.getCourse(course.id) }?.categoryId)
            assertEquals(listOf("Programming"), categories().map { it.name })
        }
        field.assertTextContains("Programming new")
        compose.onNodeWithTag("category_menu").assertDoesNotExist()
        click("color_3")
        waitFor { runBlocking { repository.getCourseDetails(course.id) }?.category?.name == "Programming new" }
    }

    @Test fun categoryAndColorSaveIndependentlyWithoutLosingRapidChanges() {
        val course = seed()
        runBlocking { dao.insertCategory(CategoryEntity("existing", "Existing")) }
        show(); click("course_row_${course.id}")
        click("course_category"); click("category_option_existing")
        waitFor { runBlocking { dao.getCourse(course.id) }?.categoryId == "existing" }
        text("course_category", "New category")
        click("color_3")
        click("color_6")
        waitFor { runBlocking { dao.getCourse(course.id) }?.colorId == 6 }
        waitFor { runBlocking { repository.getCourseDetails(course.id) }?.category?.name == "New category" }
        click("course_cancel"); click("course_row_${course.id}")
        compose.onNodeWithTag("color_6").assertIsSelected()
        compose.onNodeWithTag("course_category").assertTextContains("New category")
    }

    @Test fun completedTopicConflictDisablesApplyWithoutChangingProgress() {
        val course = seed(topics = listOf("Pointers", "Arrays"))
        val topic = runBlocking { dao.getTopics(course.id) }.first()
        runBlocking { repository.toggleTopicCompletion(course.id, topic.id) }
        show(); click("course_row_${course.id}"); click("topics_edit")
        text("topics_input", "Arrays\n pointers ")
        compose.onNodeWithTag("topics_apply").assertIsNotEnabled()
        compose.onNodeWithTag("course_save").assertDoesNotExist()
        click("topics_cancel"); click("course_cancel")
        assertNotNull(runBlocking { dao.getTopic(topic.id)?.takeIf { it.isCompleted } })
    }

    @Test fun categoryDeletionDoesNotRecreateItOnExit() {
        val course = seed(category = "Shared", topics = listOf("Pointers"))
        show(); click("course_row_${course.id}"); click("course_category")
        click("category_delete_${course.categoryId}"); click("category_delete_cancel")
        assertEquals(1, categories().size)
        click("course_category"); click("category_delete_${course.categoryId}"); click("category_delete_confirm")
        waitFor { categories().isEmpty() }
        click("course_cancel")
        waitFor { compose.onAllNodesWithTag("course_row_${course.id}").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(categories().isEmpty())
        assertNull(runBlocking { dao.getCourse(course.id) }?.categoryId)
    }

    @Test fun emptyCategoriesDoNotOpenMenuAndFilterRequiresCompletedCourses() {
        seed()
        show()
        compose.onNodeWithTag("courses_filter").assertDoesNotExist()
        click("course_add"); click("course_category")
        compose.onNodeWithTag("category_menu").assertDoesNotExist()
        click("course_cancel")
    }

    @Test fun completedFilterAndTenCourseLimitKeepExistingRowsEditable() {
        val active = (0..9).map { seed(name = "C $it", color = it) }
        runBlocking { dao.insertCourse(CourseEntity("completed", "Completed", null, 1, 2, isCompleted = true, completedAt = 2)) }
        show()
        compose.onNodeWithTag("course_add").assertDoesNotExist()
        click("courses_filter"); scrollTo("course_row_completed")
        compose.onNodeWithTag("course_row_completed").assertIsDisplayed()
        click("courses_filter")
        compose.onNodeWithTag("course_row_completed").assertDoesNotExist()
        click("course_row_${active.first().id}"); rename("Edited at limit"); click("course_cancel")
        waitFor { runBlocking { dao.getCourse(active.first().id) }?.name == "Edited at limit" }
        assertEquals(11, courses().size)
    }

    @Test fun topicOrderAndNumbersStayFixedAfterLongPressInBothModes() {
        val scheduled = seed(topics = (1..20).map { "C topic $it" })
        val unscheduled = seed(name = "Practice", color = 1, topics = (1..20).map { "Practice topic $it" })
        show()
        for (course in listOf(scheduled, unscheduled)) {
            click("course_row_${course.id}")
            val topics = runBlocking { dao.getTopics(course.id) }
            val topic = topics[1]
            val tag = "topic_${topic.id}"
            click(tag)
            assertNull(runBlocking { dao.getTopic(topic.id)?.takeIf { it.isCompleted } })
            compose.onNodeWithTag(tag).performTouchInput { swipeUp() }
            assertNull(runBlocking { dao.getTopic(topic.id)?.takeIf { it.isCompleted } })
            scrollTo(tag); compose.onNodeWithTag(tag).performTouchInput { longClick() }
            waitFor { runBlocking { dao.getTopic(topic.id)?.takeIf { it.isCompleted } } != null }
            compose.onNodeWithTag("topic_number_${topic.id}", useUnmergedTree = true).assertTextEquals("2.")
            compose.onNodeWithTag("topic_number_${topics[2].id}", useUnmergedTree = true).assertTextEquals("3.")
            compose.onNodeWithTag(tag).performTouchInput { longClick() }
            waitFor { runBlocking { dao.getTopic(topic.id)?.takeIf { it.isCompleted } } == null }
            assertTrue(runBlocking { dao.getSessions(course.id) }.isEmpty())
            click("course_cancel")
        }
    }

    @Test fun entireProgressBlockOpensCourse() {
        val course = seed(topics = listOf("A", "B"))
        show()
        waitFor { compose.onAllNodesWithTag("course_progress_${course.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("course_progress_${course.id}", useUnmergedTree = true).performTouchInput { click() }
        waitFor { compose.onAllNodesWithTag("course_title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("course_title").assertTextEquals(course.name)
    }

    @Test fun pauseAndResumeStayInEditorAndAllowPausingAgain() {
        val course = seed(topics = listOf("Pointers", "Arrays"))
        runBlocking {
            repository.saveInitialSchedule(course.id, listOf(com.utbildning.tracker.domain.WeeklyRule(1, 1200)))
            repository.toggleTopicCompletion(course.id, dao.getTopics(course.id).first().id)
        }
        val before = runBlocking { dao.getTopics(course.id) }
        show()
        click("course_row_${course.id}")
        click("course_pause"); click("course_action_confirm")
        waitFor { runBlocking { dao.getCourse(course.id) }!!.isPaused }
        compose.onNodeWithTag("course_editor").assertIsDisplayed()
        click("course_resume")
        waitFor { !runBlocking { dao.getCourse(course.id) }!!.isPaused }
        compose.onNodeWithTag("course_editor").assertIsDisplayed()
        waitFor { runCatching { compose.onNodeWithTag("course_pause").isDisplayed() }.getOrDefault(false) }
        compose.onNodeWithTag("course_pause").assertIsDisplayed()
        assertNull(runBlocking { repository.getSchedule(course.id) })
        assertEquals(before, runBlocking { dao.getTopics(course.id) })
        click("course_pause"); click("course_action_confirm")
        waitFor { runBlocking { dao.getCourse(course.id) }!!.isPaused }
        compose.onNodeWithTag("course_editor").assertIsDisplayed()
        compose.onNodeWithTag("course_resume").assertIsDisplayed()
    }

    @Test fun completionStaysInEditorWithCompletedStatus() {
        val course = seed(topics = listOf("Pointers", "Arrays"))
        show()
        click("course_row_${course.id}")
        click("course_complete"); click("course_action_confirm")
        waitFor { runCatching { compose.onNodeWithTag("course_status").isDisplayed() }.getOrDefault(false) }
        compose.onNodeWithTag("course_editor").assertIsDisplayed()
        compose.onNodeWithText("Completed").assertIsDisplayed()
        compose.onNodeWithTag("course_delete").assertIsDisplayed()
        compose.onNodeWithTag("course_complete").assertDoesNotExist()
        compose.onNodeWithTag("course_schedule_row").assertDoesNotExist()
        assertTrue(runBlocking { dao.getCourse(course.id) }!!.isCompleted)
        assertTrue(runBlocking { dao.getTopics(course.id) }.all { it.isCompleted })
    }

    private fun rename(value: String) {
        compose.onNodeWithTag("course_title").performScrollTo().performTouchInput { longClick() }
        text("course_name", value)
    }

    private fun show(language: String = "en") {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val localized = context.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalViewModelStoreOwner provides modelOwner) {
                TrackerTheme { Surface { CoursesScreen(onSettings = {}, repository = repository) } }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            modelJob = ViewModelProvider(modelOwner)[CoursesViewModel::class.java].viewModelScope.coroutineContext[Job]
        }
        waitFor { compose.onAllNodesWithTag("screen_courses").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun seed(name: String = "C", color: Int = 0, category: String = "",
        topics: List<String> = emptyList()): CourseEntity =
        runBlocking { repository.createCourse(name, color, category, topics) }

    private fun courses() = runBlocking { repository.observeCourses().first() }
    private fun categories() = runBlocking { repository.observeCategories().first() }
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(5_000, condition)
    private fun click(tag: String) {
        waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        if (!tag.startsWith("category_delete_")) scrollTo(tag)
        compose.onNodeWithTag(tag).performClick()
    }
    private fun text(tag: String, value: String) {
        scrollTo(tag)
        compose.onNodeWithTag(tag).performTextReplacement(value)

    }
    private fun scrollTo(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (node.fetchSemanticsNode().parent != null) runCatching { node.performScrollTo() }
    }
}
