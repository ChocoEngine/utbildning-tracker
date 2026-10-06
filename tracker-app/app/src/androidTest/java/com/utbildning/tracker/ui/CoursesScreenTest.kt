package com.utbildning.tracker.ui

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.room.Room
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.ui.courses.CoursesScreen
import com.utbildning.tracker.ui.courses.CoursesViewModel
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoursesScreenTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
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

    @Test fun allCourseSchedulesSurviveConcurrentLoadingAndRefresh() {
        val expected = (0..7).associate { index ->
            val course = seed(name = "Scheduled $index", color = index)
            runBlocking { repository.saveInitialSchedule(course.id,
                listOf(com.utbildning.tracker.domain.WeeklyRule(2, 540 + index * 30))) }
            course.id to (540 + index * 30)
        }
        show("ru")
        lateinit var model: CoursesViewModel
        compose.runOnIdle { model = ViewModelProvider(modelOwner)[CoursesViewModel::class.java] }
        fun allLoaded() = expected.all { (id, minute) ->
            model.scheduleRules[id]?.singleOrNull()?.startMinute == minute
        }
        waitFor { allLoaded() }
        compose.onAllNodesWithText("Без расписания", substring = true).assertCountEquals(0)
        runBlocking { repository.updateSchedule(expected.keys.first(),
            listOf(com.utbildning.tracker.domain.WeeklyRule(4, 900))) }
        waitFor { model.scheduleRules[expected.keys.first()]?.singleOrNull()?.startMinute == 900 }
        compose.runOnIdle {
            expected.entries.drop(1).forEach { (id, minute) ->
                org.junit.Assert.assertEquals(minute, model.scheduleRules[id]?.singleOrNull()?.startMinute)
            }
        }
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
        waitForTag("course_title")
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
        waitForTag("course_schedule")
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

    @Test fun nameCommitsOnFocusLossAndLeaving() {
        val course = seed()
        show()
        click("course_row_${course.id}")
        rename("On blur")
        click("course_category_open")
        waitFor { runBlocking { dao.getCourse(course.id) }?.name == "On blur" }
        applyCategory()
        rename("On exit")
        click("course_cancel")
        waitFor { runBlocking { dao.getCourse(course.id) }?.name == "On exit" }
    }

    @Test fun nameSavesOnOutsideTouchWithoutSwallowingColorAction() {
        val course = seed()
        show(); click("course_row_${course.id}")
        rename("Saved on background")
        compose.onNodeWithTag("course_editor").performTouchInput { click(androidx.compose.ui.geometry.Offset(2f, 2f)) }
        waitFor { runBlocking { dao.getCourse(course.id) }?.name == "Saved on background" }
        compose.onNodeWithTag("course_title").assertTextEquals("Saved on background")
        rename("Saved with color")
        click("course_color_toggle")
        compose.onNodeWithTag("color_3").performTouchInput { click() }
        waitFor { runBlocking { dao.getCourse(course.id) }?.let { it.name == "Saved with color" && it.colorId == 3 } == true }
    }

    @Test fun creationNameRejectsWholeOverLimitEditInRussianAndClearsErrorAfterValidEdit() {
        show("ru"); click("course_add")
        assertRejectedEditPreservesSelection("Название должно содержать не более 50 символов")
        captureError("course_name_create_ru.png")
        compose.onNodeWithTag("course_name").performTextInputSelection(TextRange(10, 20))
        compose.onNodeWithTag("course_name").performTextInput("Б".repeat(10))
        assertInputText("А".repeat(10) + "Б".repeat(10) + "А".repeat(30))
        compose.onNodeWithTag("course_name_length_error", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun creationNameShowsEnglishErrorAndAcceptsReplacementOnlyWhenResultFits() {
        show("en"); click("course_add")
        text("course_name", "A".repeat(50))
        compose.onNodeWithTag("course_name").performTextInputSelection(TextRange(10, 20))
        compose.onNodeWithTag("course_name").performTextInput("B".repeat(10))
        assertInputText("A".repeat(10) + "B".repeat(10) + "A".repeat(30))
        compose.onNodeWithTag("course_name").performTextInputSelection(TextRange(10, 20))
        paste("C".repeat(11))
        assertInputText("A".repeat(10) + "B".repeat(10) + "A".repeat(30))
        compose.onNodeWithTag("course_name_length_error", useUnmergedTree = true)
            .assertTextEquals("Name must contain no more than 50 characters")
        captureError("course_name_create_en.png")
    }

    @Test fun creationAndEditorCountSupplementaryUnicodeAsSingleCodePoints() {
        val fiftyEmoji = "😀".repeat(50)
        show("en"); click("course_add")
        text("course_name", fiftyEmoji)
        assertInputText(fiftyEmoji)
        paste("C")
        assertInputText(fiftyEmoji)
        compose.onNodeWithTag("course_name_length_error", useUnmergedTree = true)
            .assertTextEquals("Name must contain no more than 50 characters")
        click("course_cancel")

        val course = seed(name = "C".repeat(49) + "😀")
        waitForTag("course_row_${course.id}")
        click("course_row_${course.id}")
        waitForTag("course_title")
        compose.onNodeWithTag("course_title").performTouchInput { longClick() }
        compose.onNodeWithTag("course_name").performTextInputSelection(TextRange(0, 1))
        paste("😀😀")
        assertInputText("C".repeat(49) + "😀")
        assertSelection(TextRange(0, 1))
        assertEquals("C".repeat(49) + "😀", runBlocking { dao.getCourse(course.id) }?.name)
    }

    @Test fun editorNameRejectsPasteWithoutChangingDatabaseAndClearsLocalizedError() {
        val savedName = "A".repeat(50)
        val course = seed(name = savedName)
        show("en"); click("course_row_${course.id}")
        waitForTag("course_title")
        compose.onNodeWithTag("course_title").performTouchInput { longClick() }
        compose.onNodeWithTag("course_name").performTextInputSelection(TextRange(12, 18))
        paste("Z".repeat(7))
        compose.onNodeWithTag("course_name_length_error", useUnmergedTree = true)
            .assertTextEquals("Name must contain no more than 50 characters")
        captureError("course_name_editor_en.png")
        assertSelection(TextRange(12, 18))
        click("course_cancel")
        waitFor { runBlocking { dao.getCourse(course.id) }?.name == savedName }
        if (compose.onAllNodesWithTag("course_title").fetchSemanticsNodes().isEmpty()) click("course_row_${course.id}")
        waitForTag("course_title")
        compose.onNodeWithTag("course_title").assertTextEquals(savedName)
        compose.onNodeWithTag("course_title").performTouchInput { longClick() }
        compose.onNodeWithTag("course_name").performTextInputSelection(TextRange(12, 18))
        compose.onNodeWithTag("course_name").performTextInput("Z".repeat(6))
        compose.onNodeWithTag("course_name_length_error", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun editorNameShowsRussianErrorAndPreservesTextCursorAndSelection() {
        val course = seed(name = "Курс")
        show("ru"); click("course_row_${course.id}")
        waitForTag("course_title")
        compose.onNodeWithTag("course_title").performTouchInput { longClick() }
        assertRejectedEditPreservesSelection("Название должно содержать не более 50 символов")
        captureError("course_name_editor_ru.png")
        assertEquals("Курс", runBlocking { dao.getCourse(course.id) }?.name)
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
        waitFor { compose.onAllNodesWithTag("topic_${after[2].id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("topic_${after[2].id}", useUnmergedTree = true).assertExists()
    }

    @Test fun longMultilineTopicPasteIsUnrestrictedUntilApplyThenShowsStoredTitlesInRussian() {
        val longEmoji = "😀".repeat(101)
        val pasted = "  $longEmoji  \n\n  ${"Т".repeat(150)}  "
        val expected = "😀".repeat(100) + "\n" + "Т".repeat(100)
        show("ru"); click("course_add")
        waitFor { compose.onAllNodesWithTag("course_name").fetchSemanticsNodes().isNotEmpty() }
        text("course_name", "Длинные темы"); click("course_continue")
        waitFor { courses().size == 1 }
        val course = courses().single()
        val original = runBlocking { dao.getTopics(course.id) }
        click("topics_edit")

        text("topics_input", "x")
        compose.onNodeWithTag("topics_input").performTextInputSelection(TextRange(0, 1))
        pasteInto("topics_input", pasted)
        waitFor {
            compose.onNodeWithTag("topics_input").fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text == pasted
        }
        assertInputText("topics_input", pasted)
        click("topics_cancel")
        assertEquals(original, runBlocking { dao.getTopics(course.id) })

        click("topics_edit"); text("topics_input", "x")
        compose.onNodeWithTag("topics_input").performTextInputSelection(TextRange(0, 1))
        pasteInto("topics_input", pasted)
        waitFor {
            compose.onNodeWithTag("topics_input").fetchSemanticsNode()
                .config[SemanticsProperties.EditableText].text == pasted
        }
        click("topics_apply")
        waitFor { runBlocking { dao.getTopics(course.id) }.map { it.title } == expected.lines() }
        click("topics_edit")
        assertInputText("topics_input", expected)
        SystemClock.sleep(3_500)
        captureScreenshot("topic_title_limit_ru.png")
        click("topics_cancel"); click("course_cancel"); click("course_row_${course.id}"); click("topics_edit")
        assertInputText("topics_input", expected)
    }

    @Test fun savedLongMixedTopicReopensWithStoredTextInEnglish() {
        val mixed = "C".repeat(98) + "🧠Ж"
        val course = seed(topics = listOf("${mixed}ignored", "Short"))
        show("en"); click("course_row_${course.id}"); click("topics_edit")

        assertInputText("topics_input", "$mixed\nShort")
        captureScreenshot("topic_title_limit_en.png")
    }

    @Test fun categorySuggestionsKeepFocusAndDoNotSavePartialInput() {
        val course = seed()
        runBlocking { dao.insertCategory(CategoryEntity("existing", "Programming")) }
        show(); click("course_row_${course.id}")
        click("course_category_open")
        val field = compose.onNodeWithTag("course_category")
        field.performClick()
        field.assertIsFocused()
        for (letter in "Programming new") {
            field.performTextInput(letter.toString())
            field.assertIsFocused()
            assertNull(runBlocking { dao.getCourse(course.id) }?.categoryId)
            assertEquals(listOf("Programming"), categories().map { it.name })
        }
        field.assertTextContains("Programming new")
        compose.onNodeWithTag("category_menu").assertDoesNotExist()
        applyCategory()
        click("course_color_toggle")
        click("color_3")
        waitFor { runBlocking { repository.getCourseDetails(course.id) }?.category?.name == "Programming new" }
    }

    @Test fun categoryAndColorSaveIndependentlyWithoutLosingRapidChanges() {
        val course = seed()
        runBlocking { dao.insertCategory(CategoryEntity("existing", "Existing")) }
        show(); click("course_row_${course.id}")
        click("course_category_open"); click("course_category"); click("category_option_existing")
        waitFor { runBlocking { dao.getCourse(course.id) }?.categoryId == "existing" }
        click("course_category_open"); text("course_category", "New category")
        applyCategory()
        click("course_color_toggle"); click("color_3")
        click("course_color_toggle"); click("color_6")
        waitFor { runBlocking { dao.getCourse(course.id) }?.colorId == 6 }
        waitFor { runBlocking { repository.getCourseDetails(course.id) }?.category?.name == "New category" }
        click("course_cancel"); click("course_row_${course.id}")
        click("course_color_toggle")
        compose.onNodeWithTag("color_6").assertIsSelected()
        click("course_category_open")
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
        show(); click("course_row_${course.id}"); click("course_category_open"); click("course_category")
        click("category_delete_${course.categoryId}"); click("category_delete_cancel")
        assertEquals(1, categories().size)
        compose.onNodeWithTag("course_category").performClick()
        click("category_delete_${course.categoryId}"); click("category_delete_confirm")
        waitFor { categories().isEmpty() }
        applyCategory()
        click("course_cancel")
        waitFor { compose.onAllNodesWithTag("course_row_${course.id}").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(categories().isEmpty())
        assertNull(runBlocking { dao.getCourse(course.id) }?.categoryId)
    }

    @Test fun emptyCategoriesDoNotOpenMenuAndFilterRequiresCompletedCourses() {
        seed()
        show()
        compose.onNodeWithTag("courses_filter").assertDoesNotExist()
        click("course_add"); waitForTag("course_category"); compose.onNodeWithTag("course_category").performClick()
        compose.onNodeWithTag("category_menu").assertDoesNotExist()
        click("course_cancel")
    }

    @Test fun completedFilterAndTenCourseLimitKeepExistingRowsEditable() {
        val active = (0..9).map { seed(name = "C $it", color = it) }
        runBlocking { dao.insertCourse(CourseEntity("completed", "Completed", null, 1, 2, isCompleted = true, completedAt = 2)) }
        show()
        waitFor { active.all { compose.onAllNodesWithTag("course_row_${it.id}").fetchSemanticsNodes().isNotEmpty() } }
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
        waitForTag("course_resume")
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
        waitFor { compose.onAllNodesWithTag("course_title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("course_title").performTouchInput { longClick() }
        text("course_name", value)
    }

    private fun assertRejectedEditPreservesSelection(error: String) {
        text("course_name", "А".repeat(50))
        compose.onNodeWithTag("course_name").performTextInputSelection(TextRange(10, 20))
        paste("Б".repeat(11))
        assertInputText("А".repeat(50))
        assertSelection(TextRange(10, 20))
        compose.onNodeWithTag("course_name_length_error", useUnmergedTree = true).assertTextEquals(error)
    }

    private fun assertSelection(expected: TextRange) {
        val actual = compose.onNodeWithTag("course_name").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(expected, actual)
    }

    private fun assertInputText(expected: String) = assertInputText("course_name", expected)

    private fun assertInputText(tag: String, expected: String) {
        val actual = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(expected, actual)
    }

    private fun paste(value: String) {
        pasteInto("course_name", value)
    }

    private fun pasteInto(tag: String, value: String) {
        val node = compose.onNodeWithTag(tag)
        node.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        node.assertIsFocused()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("tracker text", value)) }
        waitFor { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == value }
        compose.waitForIdle()
        node.performSemanticsAction(SemanticsActions.PasteText) { action ->
            assertTrue("Focused field must accept the system clipboard paste", action())
        }
        compose.waitForIdle()
    }

    private fun captureError(name: String) = captureScreenshot(name)

    private fun captureScreenshot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, uniqueTestScreenshotName(name))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrackerChecks")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        checkNotNull(resolver.openOutputStream(uri)).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

    private fun show(language: String = "en") {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val localized = context.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources,
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
    private fun applyCategory() {
        compose.onNode(hasText("Apply list") or hasText("Применить список")).performClick()
    }
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(5_000, condition)
    private fun waitForTag(tag: String) = waitFor {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun click(tag: String) {
        waitForTag(tag)
        if (!tag.startsWith("category_delete_")) scrollTo(tag)
        compose.onNodeWithTag(tag).performClick()
    }
    private fun text(tag: String, value: String) {
        waitForTag(tag)
        scrollTo(tag)
        compose.onNodeWithTag(tag).performTextReplacement(value)

    }
    private fun scrollTo(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (node.fetchSemanticsNode().parent != null) runCatching { node.performScrollTo() }
    }
}
