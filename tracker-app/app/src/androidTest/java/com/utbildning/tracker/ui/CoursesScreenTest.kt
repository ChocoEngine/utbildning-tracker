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

    @Test fun creationDialogValidatesNameAndModeIsChosenInCourseWindow() {
        show("ru")
        click("course_add")
        click("course_continue")
        waitFor { compose.onAllNodesWithTag("course_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("course_error").assertTextEquals("Введите название курса")
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
        assertTrue(courses().isEmpty())
        click("mode_scheduled")
        waitFor { courses().size == 1 }
        assertEquals("Программирование", categories().single().name)
        assertEquals(CourseMode.SCHEDULED, courses().single().mode)
    }

    @Test fun unscheduledCourseIsCreatedByApplyingNonemptyTopics() {
        show()
        click("course_add")
        text("course_name", "C practice")
        click("course_continue")
        click("mode_unscheduled")
        click("topics_edit")
        click("topics_apply")
        waitFor { compose.onAllNodesWithTag("course_error").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(courses().isEmpty())
        text("topics_input", "Pointers\nArrays")
        click("topics_apply")
        waitFor { courses().size == 1 }
        val saved = courses().single()
        assertEquals(CourseMode.UNSCHEDULED, saved.mode)
        assertEquals(listOf("Pointers", "Arrays"), runBlocking { dao.getTopics(saved.id) }.map { it.title })
        assertTrue(runBlocking { dao.getSessions(saved.id) }.isEmpty())
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
        compose.onNodeWithTag("course_title").assertDoesNotExist() // Invalid field stays editable.
    }

    @Test fun appliedTopicsPersistImmediatelyAndCancelOnlyDiscardsTextarea() {
        val original = seed(topics = listOf("Pointers", "Arrays"))
        val before = runBlocking { dao.getTopics(original.id) }.associateBy { it.title }
        show(); click("course_row_${original.id}")
        click("topics_edit"); text("topics_input", "Discarded"); click("topics_cancel")
        assertEquals(before.values.toList(), runBlocking { dao.getTopics(original.id) })
        click("topics_edit"); text("topics_input", "Arrays\nPointers\nFunctions"); click("topics_apply")
        waitFor { runBlocking { dao.getTopics(original.id) }.size == 3 }
        val after = runBlocking { dao.getTopics(original.id) }.filter { it.archivedAt == null }
        assertEquals(listOf("Arrays", "Pointers", "Functions"), after.map { it.title })
        assertEquals(before.getValue("Arrays").id, after[0].id)
        click("course_cancel"); click("course_row_${original.id}")
        compose.onNodeWithTag("topic_${after[2].id}").assertExists()
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
        assertNotNull(runBlocking { dao.getCompletion(topic.id) })
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
        runBlocking { dao.insertCourse(CourseEntity("completed", "Completed", 0, CourseMode.SCHEDULED, 1, 2, isCompleted = true, completedAt = 2)) }
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
        val unscheduled = seed(name = "Practice", color = 1, mode = CourseMode.UNSCHEDULED, topics = (1..20).map { "Practice topic $it" })
        show()
        for (course in listOf(scheduled, unscheduled)) {
            click("course_row_${course.id}")
            val topics = runBlocking { dao.getTopics(course.id) }
            val topic = topics[1]
            val tag = "topic_${topic.id}"
            click(tag)
            assertNull(runBlocking { dao.getCompletion(topic.id) })
            compose.onNodeWithTag(tag).performTouchInput { swipeUp() }
            assertNull(runBlocking { dao.getCompletion(topic.id) })
            scrollTo(tag); compose.onNodeWithTag(tag).performTouchInput { longClick() }
            waitFor { runBlocking { dao.getCompletion(topic.id) } != null }
            compose.onNodeWithTag("topic_number_${topic.id}", useUnmergedTree = true).assertTextEquals("2.")
            compose.onNodeWithTag("topic_number_${topics[2].id}", useUnmergedTree = true).assertTextEquals("3.")
            compose.onNodeWithTag(tag).performTouchInput { longClick() }
            waitFor { runBlocking { dao.getCompletion(topic.id) } == null }
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
        mode: CourseMode = CourseMode.SCHEDULED, topics: List<String> = emptyList()): CourseEntity =
        runBlocking { repository.createCourse(name, color, mode, category, topics) }

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
