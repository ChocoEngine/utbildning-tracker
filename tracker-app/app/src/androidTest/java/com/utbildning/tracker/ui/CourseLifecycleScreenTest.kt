package com.utbildning.tracker.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.ui.courses.CoursesScreen
import com.utbildning.tracker.ui.courses.CoursesViewModel
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.time.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseLifecycleScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: TrackerDatabase
    private lateinit var repository: TrackerRepository
    private val dao get() = database.trackerDao()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private var modelJob: Job? = null
    private val today = LocalDate.of(2026, 9, 26)

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrackerDatabase::class.java).build()
        repository = TrackerRepository(database, now = { Instant.parse("2026-09-26T12:00:00Z").toEpochMilli() }, zone = { ZoneOffset.UTC })
    }
    @After fun tearDown() {
        compose.runOnIdle { owner.viewModelStore.clear() }
        runBlocking { modelJob?.join() }
        database.close()
    }

    @Test fun completionCancelPreservesCourseAndConfirmationCompletesTopicsAndReleasesColor() {
        val course = seed()
        val topics = runBlocking { dao.getTopics(course.id) }
        val manual = topics.first().copy(isCompleted = true, completionDate = null)
        runBlocking { dao.updateTopic(manual) }
        show(course.id)
        click("course_complete")
        clickDialog("course_action_cancel")
        assertFalse(runBlocking { dao.getCourse(course.id) }!!.isCompleted)
        compose.runOnIdle { ViewModelProvider(owner)[CoursesViewModel::class.java].change { it.copy(name = "Renamed") } }
        click("course_complete")
        clickDialog("course_action_confirm")
        waitFor { runBlocking { dao.getCourse(course.id) }!!.isCompleted }
        assertEquals("Renamed", runBlocking { dao.getCourse(course.id) }!!.name)
        compose.onNodeWithTag("course_title").assertExists()
        waitFor { compose.onAllNodesWithTag("course_status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("course_status").assertExists()
        assertEquals(manual, runBlocking { dao.getTopic(topics.first().id)?.takeIf { it.isCompleted } })
        assertNull(runBlocking { dao.getTopic(topics.last().id)?.takeIf { it.isCompleted } }?.completionDate)
        assertNull(runBlocking { dao.getCourse(course.id) }!!.colorId)
        assertNull(runBlocking { dao.getSchedule(course.id) })
        assertEquals(listOf("past"), runBlocking { dao.getSessions(course.id) }.map { it.id })
        assertEquals(SessionResult.DONE, runBlocking { dao.getSession("past") }?.result)
    }

    @Test fun pauseCancelPreservesScheduleAndConfirmationKeepsProgressAndColor() {
        val course = seed()
        val topic = runBlocking { dao.getTopics(course.id) }.first()
        runBlocking { repository.toggleTopicCompletion(course.id, topic.id) }
        show(course.id)
        click("course_pause")
        clickDialog("course_action_cancel")
        assertNotNull(runBlocking { dao.getSchedule(course.id) })
        click("course_pause")
        clickDialog("course_action_confirm")
        waitFor { runBlocking { dao.getCourse(course.id) }!!.isPaused }
        assertFalse(runBlocking { dao.getCourse(course.id) }!!.isCompleted)
        assertNotNull(runBlocking { dao.getTopic(topic.id)?.takeIf { it.isCompleted } })
        assertEquals(0, runBlocking { dao.getCourse(course.id) }?.colorId)
        assertNull(runBlocking { dao.getSchedule(course.id) })
        assertEquals(listOf("past"), runBlocking { dao.getSessions(course.id) }.map { it.id })
    }

    @Test fun pauseWorksForActiveCoursesWithAndWithoutSchedule() {
        val free = repositoryCourseUnscheduled()
        show(free.id)
        compose.onNodeWithTag("course_pause").assertExists()
        click("course_pause")
        clickDialog("course_action_cancel")
        assertFalse(runBlocking { dao.getCourse(free.id) }!!.isPaused)
        click("course_pause")
        clickDialog("course_action_confirm")
        waitFor { runBlocking { dao.getCourse(free.id) }!!.isPaused }
        assertEquals(listOf("Pointers"), runBlocking { dao.getTopics(free.id) }.map { it.title })
        click("course_cancel")
        val scheduled = seed()
        click("course_row_${scheduled.id}")
        compose.onNodeWithTag("course_pause").assertExists()
        click("course_pause")
        clickDialog("course_action_confirm")
        waitFor { runBlocking { dao.getCourse(scheduled.id) }!!.isPaused }
        assertTrue(runBlocking { dao.getCourse(free.id) }!!.isPaused)
    }

    private fun repositoryCourseUnscheduled() = runBlocking {
        repository.createCourse("Free", 1, topics = listOf("Pointers"))
    }

    @Test fun deletionRequiresConfirmationAndDoesNotTouchOtherCourse() {
        val course = seed()
        val other = runBlocking { repository.createCourse("Other", 1) }
        show(course.id)
        click("course_delete")
        clickDialog("course_action_cancel")
        assertNotNull(runBlocking { dao.getCourse(course.id) })
        click("course_delete")
        clickDialog("course_action_confirm")
        waitFor { runBlocking { dao.getCourse(course.id) } == null }
        assertTrue(runBlocking { dao.getTopics(course.id) }.isEmpty())
        assertTrue(runBlocking { dao.getSessions(course.id) }.isEmpty())
        assertNull(runBlocking { dao.getCourse(course.id) })
        assertEquals(other, runBlocking { dao.getCourse(other.id) })
    }

    @Test fun completedTopicsShowInlineMessageWithoutCompletionDialog() {
        val course = runBlocking { repository.createCourse("Practice", 0, topics = listOf("Pointers")) }
        val topic = runBlocking { dao.getTopics(course.id) }.single()
        show(course.id)
        longPress(topic.id)
        waitFor { compose.onAllNodesWithTag("course_all_topics_completed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("course_keep_active").assertDoesNotExist()
        click("course_cancel")
        click("course_row_${course.id}")
        compose.onNodeWithTag("course_all_topics_completed").assertExists()
        compose.onNodeWithTag("course_offer_complete").assertDoesNotExist()
        longPress(topic.id)
        waitFor { compose.onAllNodesWithTag("course_all_topics_completed").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun completedCourseIsReadOnlyAndHasNoColorPicker() {
        val course = seed()
        runBlocking { repository.completeCourse(course.id) }
        compose.setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { TrackerTheme { CoursesScreen({}, repository) } } }
        click("courses_filter")
        click("course_row_${course.id}")
        compose.runOnIdle { modelJob = ViewModelProvider(owner)[CoursesViewModel::class.java].viewModelScope.coroutineContext[Job] }
        compose.onNodeWithTag("course_title").performTouchInput { longClick() }
        for (tag in listOf("course_name", "course_category", "color_0", "topics_edit", "course_schedule", "course_complete")) compose.onNodeWithTag(tag).assertDoesNotExist()
        compose.onNodeWithTag("course_restart").assertIsDisplayed().assertIsEnabled()
        assertNull(runBlocking { dao.getCourse(course.id) }!!.colorId)
    }

    @Test fun restartWarningCancelConfirmationAndReopenProduceCleanCourse() {
        val course = seed()
        runBlocking { repository.completeCourse(course.id) }
        val completed = runBlocking { repository.getCourseDetails(course.id) }!!
        showCompleted(course.id)

        click("course_restart")
        compose.onNodeWithTag("course_restart_warning")
            .assertTextEquals(ApplicationProvider.getApplicationContext<Context>().getString(com.utbildning.tracker.R.string.course_confirm_restart))
        clickDialog("course_action_cancel")
        assertEquals(completed, runBlocking { repository.getCourseDetails(course.id) })
        assertEquals(listOf("past"), runBlocking { dao.getSessions(course.id) }.map { it.id })

        click("course_restart")
        clickDialog("course_action_confirm")
        waitFor { runBlocking { dao.getCourse(course.id) }?.isCompleted == false }
        val restarted = runBlocking { repository.getCourseDetails(course.id) }!!
        assertEquals(completed.course.name, restarted.course.name)
        assertEquals(completed.course.categoryId, restarted.course.categoryId)
        assertEquals(0, restarted.course.colorId)
        assertFalse(restarted.course.isPaused)
        assertNull(restarted.course.completedAt)
        assertEquals(completed.topics.map { it.id to it.title }, restarted.topics.map { it.id to it.title })
        assertEquals(completed.topics.map { it.position }, restarted.topics.map { it.position })
        assertTrue(restarted.topics.all { !it.isCompleted && it.completionDate == null })
        assertTrue(runBlocking { dao.getSessions(course.id) }.isEmpty())
        assertNull(runBlocking { dao.getSchedule(course.id) })
        waitFor { compose.onAllNodesWithTag("course_restart").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("course_restart").assertDoesNotExist()
        compose.onNodeWithTag("course_schedule").assertExists()

        click("course_cancel")
        click("course_row_${course.id}")
        compose.onNodeWithTag("course_restart").assertDoesNotExist()
        compose.onNodeWithTag("course_schedule").assertExists()
        assertTrue(runBlocking { dao.getSessions(course.id) }.isEmpty())
        assertTrue(runBlocking { dao.getTopics(course.id) }.all { !it.isCompleted })
    }

    @Test fun restartIsDisabledWhenEveryColorIsOccupied() {
        val course = seed()
        runBlocking {
            repository.completeCourse(course.id)
            (0..9).forEach { repository.createCourse("Active $it", it) }
        }
        showCompleted(course.id)
        compose.onNodeWithTag("course_restart").assertIsDisplayed().assertIsNotEnabled()
        assertTrue(runBlocking { dao.getCourse(course.id) }!!.isCompleted)
        assertEquals(listOf("past"), runBlocking { dao.getSessions(course.id) }.map { it.id })
    }

    @Test fun restartStorageFailureKeepsDialogAndAllDataForRetry() {
        val course = seed()
        runBlocking { repository.completeCourse(course.id) }
        val before = runBlocking { repository.getCourseDetails(course.id) }!!
        val sessionsBefore = runBlocking { dao.getSessions(course.id) }
        showCompleted(course.id)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_restart_ui BEFORE UPDATE ON courses WHEN OLD.isCompleted = 1 AND NEW.isCompleted = 0 BEGIN SELECT RAISE(ABORT, 'injected restart failure'); END")

        click("course_restart")
        clickDialog("course_action_confirm")
        waitFor { compose.onAllNodesWithTag("course_action_error").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before, runBlocking { repository.getCourseDetails(course.id) })
        assertEquals(sessionsBefore, runBlocking { dao.getSessions(course.id) })

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_restart_ui")
        clickDialog("course_action_confirm")
        waitFor { runBlocking { dao.getCourse(course.id) }?.isCompleted == false }
        assertTrue(runBlocking { dao.getSessions(course.id) }.isEmpty())
    }

    private fun seed(): CourseEntity = runBlocking {
        val course = repository.createCourse("C", 0, topics = listOf("Pointers", "Arrays"))
        // The fixture already represents a synchronized calendar through today.
        dao.insertSchedule(ScheduleEntity(course.id, today.toEpochDay(), generatedThrough = today.toEpochDay()))
        dao.insertScheduleRule(ScheduleRuleEntity(course.id, 6, 600))
        dao.insertSession(SessionEntity("past", course.id, today.minusDays(1).toEpochDay(), 600, "C", 0, 1, 1, result = SessionResult.DONE))
        dao.insertSession(SessionEntity("future", course.id, today.plusDays(1).toEpochDay(), 600, "C", 0, 1, 1))
        course
    }
    private fun show(id: String) {
        compose.setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            TrackerTheme { CoursesScreen({}, repository) }
        } }
        click("course_row_$id")
        compose.runOnIdle { modelJob = ViewModelProvider(owner)[CoursesViewModel::class.java].viewModelScope.coroutineContext[Job] }
    }
    private fun showCompleted(id: String) {
        compose.setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            TrackerTheme { CoursesScreen({}, repository) }
        } }
        click("courses_filter")
        click("course_row_$id")
        compose.runOnIdle { modelJob = ViewModelProvider(owner)[CoursesViewModel::class.java].viewModelScope.coroutineContext[Job] }
    }
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(5_000, condition)
    private fun click(tag: String) { waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; if (tag.startsWith("course_row_")) compose.onNodeWithTag(tag).performScrollTo(); compose.onNodeWithTag(tag).performClick(); if (tag.startsWith("course_row_")) waitFor { compose.onAllNodesWithTag("course_title").fetchSemanticsNodes().isNotEmpty() } }
    private fun clickDialog(tag: String) { waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithTag(tag).performClick() }
    private fun longPress(id: String) { waitFor { compose.onAllNodesWithTag("topic_$id").fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithTag("topic_$id").performScrollTo().performTouchInput { longClick() } }
}
