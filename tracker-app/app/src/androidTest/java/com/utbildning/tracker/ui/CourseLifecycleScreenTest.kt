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
        val manual = TopicCompletionEntity(topics.first().id, course.id, CompletionSource.MANUAL, 1)
        runBlocking { dao.insertCompletion(manual) }
        show(course.id)
        click("course_complete")
        clickDialog("course_action_cancel")
        assertFalse(runBlocking { dao.getCourse(course.id) }!!.isCompleted)
        click("course_complete")
        clickDialog("course_action_confirm")
        waitFor { runBlocking { dao.getCourse(course.id) }!!.isCompleted }
        assertEquals(manual, runBlocking { dao.getCompletion(topics.first().id) })
        assertEquals(CompletionSource.COURSE_COMPLETION, runBlocking { dao.getCompletion(topics.last().id) }?.source)
        assertNull(runBlocking { dao.getReservation(course.id) })
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
        assertNotNull(runBlocking { dao.getCompletion(topic.id) })
        assertEquals(0, runBlocking { dao.getReservation(course.id) }?.colorId)
        assertNull(runBlocking { dao.getSchedule(course.id) })
        assertEquals(listOf("past"), runBlocking { dao.getSessions(course.id) }.map { it.id })
    }

    @Test fun deletionRequiresConfirmationAndDoesNotTouchOtherCourse() {
        val course = seed()
        val other = runBlocking { repository.createCourse("Other", 1, CourseMode.SCHEDULED) }
        show(course.id)
        click("course_delete")
        clickDialog("course_action_cancel")
        assertNotNull(runBlocking { dao.getCourse(course.id) })
        click("course_delete")
        clickDialog("course_action_confirm")
        waitFor { runBlocking { dao.getCourse(course.id) } == null }
        assertTrue(runBlocking { dao.getTopics(course.id) }.isEmpty())
        assertTrue(runBlocking { dao.getSessions(course.id) }.isEmpty())
        assertNull(runBlocking { dao.getReservation(course.id) })
        assertEquals(other, runBlocking { dao.getCourse(other.id) })
    }

    @Test fun exhaustedKeepActiveDoesNotRepeatUntilNewCompletionCycle() {
        val course = runBlocking { repository.createCourse("Practice", 0, CourseMode.UNSCHEDULED, topics = listOf("Pointers")) }
        val topic = runBlocking { dao.getTopics(course.id) }.single()
        show(course.id)
        longPress(topic.id)
        clickDialog("course_keep_active")
        waitFor { runBlocking { dao.getCourse(course.id) }!!.completionPromptDismissed }
        assertFalse(runBlocking { dao.getCourse(course.id) }!!.isCompleted)
        click("course_cancel")
        click("course_row_${course.id}")
        compose.onNodeWithTag("course_keep_active").assertDoesNotExist()
        longPress(topic.id)
        waitFor { runBlocking { dao.getCompletion(topic.id) } == null }
        longPress(topic.id)
        clickDialog("course_offer_complete")
        clickDialog("course_action_confirm")
        waitFor { runBlocking { dao.getCourse(course.id) }!!.isCompleted }
        assertTrue(runBlocking { dao.getSessions(course.id) }.isEmpty())
    }

    private fun seed(): CourseEntity = runBlocking {
        val course = repository.createCourse("C", 0, CourseMode.SCHEDULED, topics = listOf("Pointers", "Arrays"))
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
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(5_000, condition)
    private fun click(tag: String) { waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun clickDialog(tag: String) { waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithTag(tag).performClick() }
    private fun longPress(id: String) { compose.onNodeWithTag("topic_$id").performScrollTo().performTouchInput { longClick() } }
}
