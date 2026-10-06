package com.utbildning.tracker.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.TrackerDatabase
import com.utbildning.tracker.ui.courses.CoursesScreen
import com.utbildning.tracker.ui.courses.CoursesViewModel
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** Real windows and IME, saved pixels are reviewed separately from semantics assertions. */
class R05EditorReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: TrackerDatabase
    private lateinit var repo: TrackerRepository
    private var job: Job? = null

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(compose.activity, TrackerDatabase::class.java).build()
        repo = TrackerRepository(db)
    }
    @After fun cleanup() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        runBlocking { job?.join() }
        db.close()
    }
    @Test fun russianEditorAndKeyboard() = review("ru")
    @Test fun englishEditorAndKeyboard() = review("en")

    private fun review(language: String) {
        val name = if (language == "ru") "Лекции и практика программирования на C" else "C pointers arrays memory structured programming"
        val category = if (language == "ru") "Программирование и самостоятельная практика" else "Programming and independent practice"
        val course = runBlocking { repo.createCourse(name, 0, category, (1..30).map { "C topic $it: pointers arrays memory" }) }
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val localized = compose.activity.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources) { TrackerTheme { CoursesScreen({}, repo) } }
        }
        compose.runOnIdle { job = ViewModelProvider(compose.activity)[CoursesViewModel::class.java].viewModelScope.coroutineContext[Job] }
        click("course_row_${course.id}")
        waitTag("course_title")
        capture(language, "editor")
        compose.onNodeWithTag("course_title").performTouchInput { longClick() }
        waitTag("course_name")
        compose.onNodeWithTag("course_name").performClick()
        capture(language, "name_keyboard")
        compose.onNodeWithTag("course_name").performTextReplacement("C renamed")
        click("course_color_toggle")
        click("color_1")
        compose.waitUntil(5_000) { runBlocking { repo.getCourse(course.id)?.name == "C renamed" && repo.getCourse(course.id)?.colorId == 1 } }
        click("topics_edit")
        waitTag("topics_input")
        compose.onNodeWithTag("topics_input").performClick().performTextReplacement("  " + "😀".repeat(101) + "  \nC arrays")
        compose.onNodeWithTag("topics_apply").assertIsDisplayed()
        compose.onNodeWithTag("topics_cancel").assertIsDisplayed()
        capture(language, "topics_keyboard")
        assertAboveKeyboard(localized.getString(com.utbildning.tracker.R.string.topics_apply))
        tapActualButton(localized.getString(com.utbildning.tracker.R.string.course_cancel))
        assertEquals(30, runBlocking { db.trackerDao().getTopics(course.id) }.size)
        click("course_category_open")
        waitTag("course_category")
        compose.onNodeWithTag("course_category").performTextReplacement("")
        capture(language, "category_keyboard")
        assertAboveKeyboard(localized.getString(com.utbildning.tracker.R.string.topics_apply))
        tapActualButton(localized.getString(com.utbildning.tracker.R.string.topics_apply))
        compose.waitUntil(5_000) { runBlocking { repo.getCourseDetails(course.id)?.category == null } }
        click("course_cancel")
        click("course_row_${course.id}")
        waitTag("course_title")
        compose.onNodeWithTag("course_title").assertTextContains("C renamed")
        capture(language, "reopened")
        click("course_cancel")
        click("course_add")
        waitTag("course_name")
        compose.onNodeWithTag("course_name").performClick().performTextReplacement("C".repeat(50))
        compose.onNodeWithTag("course_name").performTextInput("X")
        capture(language, "creation_name_error_keyboard")
        val errorBounds = assertAboveKeyboard(localized.getString(com.utbildning.tracker.R.string.course_name_too_long))
        assertTrue("Error text is clipped", errorBounds.height() >= 10 * localized.resources.displayMetrics.density)
        compose.onNodeWithTag("course_name").performTextReplacement(name)
        compose.onNodeWithTag("course_category").performScrollTo().performClick().performTextReplacement("$category new")
        capture(language, "creation_keyboard")
        assertAboveKeyboard(localized.getString(com.utbildning.tracker.R.string.course_continue))
        tapActualButton(localized.getString(com.utbildning.tracker.R.string.course_cancel))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("course_name").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, runBlocking { repo.observeCourses().first() }.size)
    }
    private fun waitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun click(tag: String) {
        waitTag(tag)
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }
    private fun capture(language: String, state: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Wait for IME/window animations using the platform, not Compose's virtual clock.
        android.os.SystemClock.sleep(400)
        val directory = File(compose.activity.getExternalFilesDir(null), "r05-editor").apply { mkdirs() }
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "${language}_$state.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun assertAboveKeyboard(label: String): android.graphics.Rect {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val windows = automation.windows
        val keyboard = windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        assertNotNull("System keyboard must be visible", keyboard)
        val ime = android.graphics.Rect().also { keyboard!!.getBoundsInScreen(it) }
        fun nodes(node: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { index -> node.getChild(index)?.let { nodes(it) }.orEmpty() }
        val candidates = windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .flatMap { it.root?.let { root -> nodes(root) }.orEmpty() }
            .filter { it.text?.toString() == label }
        assertTrue("Button missing from actual system windows: $label", candidates.isNotEmpty())
        val visible = candidates.map { node ->
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            println("R05_WINDOW button=$label bounds=$bounds ime=$ime")
            bounds
        }.firstOrNull { !it.isEmpty && it.top >= 0 && it.bottom <= ime.top }
        assertNotNull("Button covered by keyboard: $label, IME=$ime", visible)
        return visible!!
    }
    private fun tapActualButton(label: String) {
        val bounds = assertAboveKeyboard(label)
        val now = android.os.SystemClock.uptimeMillis()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now, now + if (action == 1) 50 else 0,
                action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event, true))
            event.recycle()
        }
        compose.waitForIdle()
    }
}
