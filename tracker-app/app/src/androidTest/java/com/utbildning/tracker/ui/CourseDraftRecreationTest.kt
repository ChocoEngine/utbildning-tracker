package com.utbildning.tracker.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.R
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.TrackerDatabase
import com.utbildning.tracker.ui.courses.CoursesScreen
import com.utbildning.tracker.ui.courses.CoursesViewModel
import com.utbildning.tracker.ui.theme.TrackerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseDraftRecreationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: TrackerDatabase
    private lateinit var repository: TrackerRepository
    private var modelJob: Job? = null
    private val reattachContent = object : Application.ActivityLifecycleCallbacks {
        // The empty test host has no onCreate content of its own. Reinstall the real screen
        // after actual Activity recreation, using the Activity's retained ViewModelStore.
        override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity.javaClass == ComponentActivity::class.java) {
                (activity as ComponentActivity).setContent { TrackerTheme { CoursesScreen({}, repository) } }
            }
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(application, TrackerDatabase::class.java).build()
        repository = TrackerRepository(database)
    }
    @After fun tearDown() {
        application.unregisterActivityLifecycleCallbacks(reattachContent)
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        runBlocking { modelJob?.join() }
        database.close()
    }

    @Test fun actualActivityRecreationRetainsCreatedCourseAndOpenTopicEditor() {
        compose.setContent { TrackerTheme { CoursesScreen({}, repository) } }
        var original: CoursesViewModel? = null
        compose.runOnIdle {
            original = ViewModelProvider(compose.activity)[CoursesViewModel::class.java]
            modelJob = original!!.viewModelScope.coroutineContext[Job]
        }
        click("course_add")
        waitForTag("course_name")
        compose.onNodeWithTag("course_name").performTextReplacement("C draft")
        compose.onNodeWithTag("course_category").performTextReplacement("New category")
        click("course_continue")
        compose.waitUntil(5_000) { original?.draft?.id != null }
        click("course_category_open")
        waitForTag("course_category")
        compose.onNodeWithTag("course_category").assertTextContains("New category")
        compose.onNodeWithText(compose.activity.getString(R.string.topics_apply)).performClick()
        click("topics_edit")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("topics_input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("topics_input").performTextReplacement("Pointers\nArrays")
        application.registerActivityLifecycleCallbacks(reattachContent)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.runOnIdle { assertSame(original, ViewModelProvider(compose.activity)[CoursesViewModel::class.java]) }
        compose.onNodeWithTag("course_title").assertTextContains("C draft")
        compose.onNodeWithTag("topics_input").assertTextContains("Pointers\nArrays")
        assertEquals(1, runBlocking { repository.observeCourses().first() }.size)
        assertEquals(1, runBlocking { repository.observeCategories().first() }.size)
        click("topics_apply")
        compose.waitUntil(5_000) { runBlocking { repository.observeCourses().first() }.size == 1 }
        val saved = runBlocking { repository.observeCourses().first() }.single()
        assertEquals(listOf("Pointers", "Arrays"), runBlocking { database.trackerDao().getTopics(saved.id) }.map { it.title })
    }

    @Test fun backgroundSavesActiveFieldsWithoutApplyingTopicDraft() {
        val course = runBlocking { repository.createCourse("C", 0, topics = listOf("Pointers")) }
        compose.setContent { TrackerTheme { CoursesScreen({}, repository) } }
        click("course_row_${course.id}")
        waitForTag("course_title")
        compose.onNodeWithTag("course_title").performTouchInput { longClick() }
        waitForTag("course_name")
        compose.onNodeWithTag("course_name").performTextReplacement("C background")
        // A stopped Activity must not depend on focus loss or composition disposal.
        assertStoredWhileStopped { runBlocking { repository.getCourse(course.id)?.name == "C background" } }
        assertEquals(listOf("Pointers"), runBlocking { database.trackerDao().getTopics(course.id) }.map { it.title })
        compose.onNodeWithTag("course_name").assertTextContains("C background")
        click("course_category_open")
        waitForTag("course_category")
        compose.onNodeWithTag("course_category").performTextReplacement("Background category")
        assertStoredWhileStopped { runBlocking { repository.getCourseDetails(course.id)?.category?.name == "Background category" } }
        compose.onNodeWithText(compose.activity.getString(R.string.topics_apply)).performClick()
        click("topics_edit")
        compose.onNodeWithTag("topics_input").performTextReplacement("Unapplied arrays")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(listOf("Pointers"), runBlocking { database.trackerDao().getTopics(course.id) }.map { it.title })
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("topics_input").assertTextContains("Unapplied arrays")
    }

    private fun assertStoredWhileStopped(condition: () -> Boolean) {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        var stored = false
        try {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (condition()) { stored = true; break }
                Thread.sleep(50)
            }
        } finally { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        assertTrue("Active field was not stored while Activity was stopped", stored)
    }

    private fun click(tag: String) {
        waitForTag(tag)
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performClick()
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
