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

    @Test fun russianScheduledFormValidatesNameAndSavesCourseWithoutTopics() {
        show("ru")
        click("course_add")
        click("course_save")
        waitFor { compose.onAllNodesWithTag("course_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("course_error").assertTextEquals("Введите название курса")
        assertTrue(courses().isEmpty())
        text("course_name", "Лекции C")
        text("course_category", "Программирование")
        click("course_save")
        waitFor { courses().size == 1 }
        val saved = courses().single()
        assertEquals(CourseMode.SCHEDULED, saved.mode)
        assertEquals("Лекции C", saved.name)
        assertEquals("Программирование", categories().single().name)
        assertTrue(runBlocking { dao.getTopics(saved.id) }.isEmpty())
        waitFor { compose.onAllNodesWithTag("course_row_${saved.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("course_row_${saved.id}").assertIsDisplayed()
    }

    @Test fun englishUnscheduledFormRequiresTopicsAndAppliesDraftOnlyOnSave() {
        show("en")
        click("course_add")
        text("course_name", "C practice")
        click("mode_unscheduled")
        click("course_save")
        waitFor { compose.onAllNodesWithTag("course_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("course_error").assertTextEquals("Add at least one topic")
        assertTrue(courses().isEmpty())
        click("topics_edit")
        text("topics_input", "Pointers\nArrays")
        click("topics_apply")
        assertTrue(courses().isEmpty())
        click("course_save")
        waitFor { courses().size == 1 }
        val saved = courses().single()
        assertEquals(CourseMode.UNSCHEDULED, saved.mode)
        assertEquals(listOf("Pointers", "Arrays"), runBlocking { dao.getTopics(saved.id) }.map { it.title })
        assertTrue(runBlocking { dao.getSessions(saved.id) }.isEmpty())
    }

    @Test fun cancelNewFormDoesNotCreateCourseOrCategory() {
        show()
        click("course_add")
        text("course_name", "Discarded")
        text("course_category", "Discarded category")
        click("topics_edit")
        text("topics_input", "Discarded topic")
        click("topics_apply")
        click("course_cancel")
        assertTrue(courses().isEmpty())
        assertTrue(categories().isEmpty())
    }

    @Test fun topicEditorCancelAndFormCancelPreserveOriginalTopicsAndCourse() {
        val original = seed(topics = listOf("Pointers", "Arrays"))
        val originalTopics = runBlocking { dao.getTopics(original.id) }
        show()
        click("course_row_${original.id}")
        click("topics_edit")
        text("topics_input", "Discarded")
        click("topics_cancel")
        click("topics_edit")
        compose.onNodeWithTag("topics_input").assertTextContains("Pointers\nArrays")
        text("topics_input", "Arrays\nPointers\nFunctions")
        click("topics_apply")
        assertEquals(originalTopics, runBlocking { dao.getTopics(original.id) })
        text("course_name", "Discarded name")
        click("course_cancel")
        assertEquals(original, runBlocking { dao.getCourse(original.id) })
        assertEquals(originalTopics, runBlocking { dao.getTopics(original.id) })
    }

    @Test fun emptyCategoryIsDeletedImmediatelyAndCancelDoesNotRestoreIt() {
        runBlocking { dao.insertCategory(CategoryEntity("empty", "Empty")) }
        show()
        click("course_add")
        click("course_category")
        click("category_delete_empty")
        waitFor { categories().isEmpty() }
        compose.onNodeWithTag("category_delete_confirm").assertDoesNotExist()
        click("course_cancel")
        assertTrue(categories().isEmpty())
        assertTrue(courses().isEmpty())
    }

    @Test fun existingFormSavesReorderedTopicsAndKeepsTheirIds() {
        val original = seed(topics = listOf("Pointers", "Arrays"))
        val before = runBlocking { dao.getTopics(original.id) }.associateBy { it.title }
        show()
        click("course_row_${original.id}")
        text("course_name", "Advanced C")
        click("topics_edit")
        text("topics_input", "Arrays\nPointers\nFunctions")
        click("topics_apply")
        click("course_save")
        waitFor { runBlocking { dao.getCourse(original.id) }?.name == "Advanced C" }
        val after = runBlocking { dao.getTopics(original.id) }.filter { it.archivedAt == null }
        assertEquals(listOf("Arrays", "Pointers", "Functions"), after.map { it.title })
        assertEquals(before.getValue("Arrays").id, after[0].id)
        assertEquals(before.getValue("Pointers").id, after[1].id)
    }

    @Test fun completedTopicConflictDisablesApplyAndSaveWithoutChangingProgress() {
        val course = seed(topics = listOf("Pointers", "Arrays"))
        val topic = runBlocking { dao.getTopics(course.id) }.first()
        val completion = TopicCompletionEntity(topic.id, course.id, CompletionSource.MANUAL, 100)
        runBlocking { dao.insertCompletion(completion) }
        show()
        click("course_row_${course.id}")
        click("topics_edit")
        compose.onNodeWithTag("topics_input").assertTextContains("Arrays")
        text("topics_input", "Arrays\n pointers ")
        compose.onNodeWithTag("topics_apply").assertIsNotEnabled()
        compose.onNodeWithTag("course_save").assertIsNotEnabled()
        click("topics_cancel")
        click("course_cancel")
        assertEquals(completion, runBlocking { dao.getCompletion(topic.id) })
        assertEquals(listOf("Pointers", "Arrays"), runBlocking { dao.getTopics(course.id) }.map { it.title })
    }

    @Test fun linkedCategoryDeletionNeedsConfirmationAndPreservesAllCourses() {
        val active = seed(category = "Shared", topics = listOf("Pointers"))
        val topic = runBlocking { dao.getTopics(active.id) }.single()
        val completed = CourseEntity("completed", "Completed", 1, CourseMode.SCHEDULED, 1, 2,
            categoryId = active.categoryId, isCompleted = true, completedAt = 2)
        runBlocking { dao.insertCourse(completed) }
        show()
        click("course_row_${active.id}")
        click("course_category")
        click("category_delete_${active.categoryId}")
        click("category_delete_cancel")
        assertEquals(1, categories().size)
        assertEquals(active.categoryId, runBlocking { dao.getCourse(completed.id) }?.categoryId)
        click("course_category")
        click("category_delete_${active.categoryId}")
        click("category_delete_confirm")
        waitFor { categories().isEmpty() }
        compose.onNodeWithTag("course_category").assert(hasText("", substring = false))
        click("course_save")
        waitFor { runBlocking { dao.getCourse(active.id) }?.categoryId == null }
        assertTrue(categories().isEmpty())
        assertEquals(completed.copy(categoryId = null), runBlocking { dao.getCourse(completed.id) })
        assertEquals(listOf(topic), runBlocking { dao.getTopics(active.id) })
        assertEquals(0, runBlocking { dao.getReservation(active.id) }?.colorId)
    }

    @Test fun completedFilterAndTenCourseLimitKeepExistingRowsEditable() {
        val active = (0..9).map { seed(name = "C $it", color = it) }
        runBlocking { dao.insertCourse(CourseEntity("completed", "Completed", 0,
            CourseMode.SCHEDULED, 1, 2, isCompleted = true, completedAt = 2)) }
        show()
        waitFor { compose.onAllNodesWithTag("course_row_${active.first().id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("course_add").assertDoesNotExist()
        compose.onNodeWithTag("course_row_completed").assertDoesNotExist()
        click("courses_filter")
        scrollTo("course_row_completed")
        compose.onNodeWithTag("course_row_completed").assertIsDisplayed()
        click("courses_filter")
        compose.onNodeWithTag("course_row_completed").assertDoesNotExist()
        scrollTo("course_row_${active.first().id}")
        click("course_row_${active.first().id}")
        text("course_name", "Edited at limit")
        click("course_save")
        waitFor { runBlocking { dao.getCourse(active.first().id) }?.name == "Edited at limit" }
        assertEquals(11, courses().size)
    }

    @Test fun onlyLongPressTogglesTopicsInBothModesAndNeverCreatesSessions() {
        val scheduled = seed(topics = (1..20).map { "C topic $it" })
        val unscheduled = seed(name = "Practice", color = 1, mode = CourseMode.UNSCHEDULED,
            topics = (1..20).map { "Practice topic $it" })
        show()
        for (course in listOf(scheduled, unscheduled)) {
            click("course_row_${course.id}")
            val topic = runBlocking { dao.getTopics(course.id) }.first()
            val tag = "topic_${topic.id}"
            scrollTo(tag)
            click(tag)
            compose.waitForIdle()
            assertNull(runBlocking { dao.getCompletion(topic.id) })
            compose.onNodeWithTag(tag).performTouchInput { swipeUp() }
            compose.waitForIdle()
            assertTrue(runBlocking { dao.observeCompletions(course.id).first() }.isEmpty())
            scrollTo(tag)
            compose.onNodeWithTag(tag).performTouchInput { longClick() }
            waitFor { runBlocking { dao.getCompletion(topic.id) } != null }
            compose.onNodeWithTag(tag).performTouchInput { longClick() }
            waitFor { runBlocking { dao.getCompletion(topic.id) } == null }
            assertTrue(runBlocking { dao.getSessions(course.id) }.isEmpty())
            click("course_cancel")
        }
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
        if (tag == "course_category") compose.onNodeWithTag(tag).performClick()
    }
    private fun scrollTo(tag: String) { compose.onNodeWithTag(tag).performScrollTo() }
}
