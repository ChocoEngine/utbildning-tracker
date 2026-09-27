package com.utbildning.tracker.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.ui.session.SessionScreen
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: TrackerDatabase
    private lateinit var repository: TrackerRepository
    private val dao get() = database.trackerDao()
    private val visible = mutableStateOf(true)
    private lateinit var course: CourseEntity
    private lateinit var topics: List<TopicEntity>
    private lateinit var session: SessionEntity

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrackerDatabase::class.java).build()
        repository = TrackerRepository(database)
        course = repository.createCourse("C", 0, topics = listOf("Pointers", "Arrays", "Functions"))
        topics = dao.getTopics(course.id)
        session = SessionEntity("lesson", course.id, LocalDate.now().toEpochDay(), 600, "C", 0, 100, 100)
        dao.insertSession(session)
    }

    @After fun tearDown() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        database.close()
    }

    @Test fun pastDetailsHideSaveAndDisableSelection() = checkReadOnly(-1L)
    @Test fun futureDetailsHideSaveAndDisableSelection() = checkReadOnly(1L)

    private fun checkReadOnly(offset: Long) {
        runBlocking {
            dao.updateSession(session.copy(date = LocalDate.now().toEpochDay() + offset))
            dao.updateTopic(topics[0].copy(isCompleted = true, completionDate = LocalDate.now().toEpochDay() + offset))
        }
        show()
        compose.onNodeWithTag("session_save").assertDoesNotExist()
        compose.onNodeWithTag("session_topic_${topics[0].id}").assertIsNotEnabled()
    }

    @Test fun choosingSeveralTopicsOutOfOrderPersistsSingleDoneSession() {
        show()
        click("session_topic_${topics[2].id}")
        click("session_topic_${topics[0].id}")
        click("session_save")
        waitClosed()
        assertEquals(SessionResult.DONE, runBlocking { dao.getSession(session.id) }?.result)
        assertEquals(setOf(topics[0].id, topics[2].id), runBlocking { dao.getTopics(course.id).filter { it.isCompleted } }.map { it.id }.toSet())
        assertEquals(1, runBlocking { dao.getSessions(course.id) }.size)
    }

    @Test fun nothingCompletedClearsDraftSelectionAndSavesFactOfStudyOnly() {
        show()
        click("session_topic_${topics[0].id}")
        click("session_topic_${topics[0].id}")
        compose.onNodeWithTag("session_topic_${topics[0].id}").assertIsOff()
        click("session_save")
        waitClosed()
        assertEquals(SessionResult.DONE, runBlocking { dao.getSession(session.id) }?.result)
        assertTrue(runBlocking { dao.getTopics(course.id).filter { it.isCompleted } }.isEmpty())
    }

    @Test fun cancelDoesNotChangeResultOrCompletion() {
        show()
        click("session_topic_${topics[1].id}")
        click("session_back")
        waitClosed()
        assertEquals(session, runBlocking { dao.getSession(session.id) })
        assertTrue(runBlocking { dao.getTopics(course.id).filter { it.isCompleted } }.isEmpty())
    }

    @Test fun savedStateRestorationRetainsUnsubmittedTopicSelection() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TrackerTheme { if (visible.value) SessionScreen(repository, session.id) { visible.value = false } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("session_topics").fetchSemanticsNodes().isNotEmpty() }
        click("session_topic_${topics[2].id}")
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("session_topics").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("session_topic_${topics[2].id}").assertIsOn()
        compose.onNodeWithTag("session_topic_${topics[0].id}").assertIsOff()
        assertEquals(SessionResult.PENDING, runBlocking { dao.getSession(session.id) }?.result)
        assertTrue(runBlocking { dao.getTopics(course.id).filter { it.isCompleted } }.isEmpty())
        click("session_save")
        waitClosed()
        assertEquals(listOf(topics[2].id), runBlocking { dao.getTopics(course.id).filter { it.isCompleted } }.map { it.id })
    }

    @Test fun correctionPreselectsOwnTopicsAndPreservesIndependentManualCompletion() {
        runBlocking {
            repository.setSessionResult(session.id, SessionResult.DONE, setOf(topics[0].id))
            repository.toggleTopicCompletion(course.id, topics[1].id)
        }
        show()
        compose.onNodeWithTag("session_topic_${topics[0].id}").assertIsOn()
        compose.onNodeWithTag("session_topic_${topics[1].id}").assertDoesNotExist()
        click("session_topic_${topics[0].id}")
        click("session_topic_${topics[2].id}")
        click("session_save")
        waitClosed()
        assertNull(runBlocking { dao.getTopic(topics[0].id)?.takeIf { it.isCompleted } })
        assertTrue(runBlocking { dao.getTopic(topics[1].id)!!.isCompleted })
        assertNull(runBlocking { dao.getTopic(topics[1].id)!!.completionDate })
        assertEquals(session.date, runBlocking { dao.getTopic(topics[2].id)?.takeIf { it.isCompleted } }?.completionDate)
    }

    private fun show() {
        compose.setContent { TrackerTheme { if (visible.value) SessionScreen(repository, session.id) { visible.value = false } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("session_topics").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag.startsWith("session_topic_")) node.performScrollTo()
        node.performClick()
    }
    private fun waitClosed() = compose.waitUntil(5_000) { !visible.value }
}
