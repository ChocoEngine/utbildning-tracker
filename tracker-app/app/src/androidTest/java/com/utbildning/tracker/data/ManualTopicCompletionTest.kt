package com.utbildning.tracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualTopicCompletionTest {
    private lateinit var db: TrackerDatabase
    private lateinit var repository: TrackerRepository
    private val dao get() = db.trackerDao()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrackerDatabase::class.java).build()
        repository = TrackerRepository(db, now = { 200L })
    }
    @After fun close() = db.close()

    @Test fun eitherModeCanToggleOutOfOrderWithoutCalendarRows() = runBlocking {
        (0..1).forEach { index ->
            val course = repository.createCourse("C", index, topics = listOf("Массивы", "Указатели"))
            val topics = dao.getTopics(course.id)
            assertTrue(repository.toggleTopicCompletion(course.id, topics.last().id))
            assertNull(dao.getTopic(topics.first().id)?.takeIf { it.isCompleted })
            assertNull(dao.getTopic(topics.last().id)?.takeIf { it.isCompleted }?.completionDate)
            assertNull(dao.getTopic(topics.last().id)?.completionDate)
            assertFalse(repository.toggleTopicCompletion(course.id, topics.last().id))
            assertNull(dao.getTopic(topics.last().id)?.takeIf { it.isCompleted })
            assertTrue(dao.getSessions(course.id).isEmpty())
            assertNull(dao.getSchedule(course.id))
        }
    }

    @Test fun removingSessionCompletionKeepsHistoryAndSessionResult() = runBlocking {
        val course = repository.createCourse("C", 0, topics = listOf("Массивы"))
        val topic = dao.getTopics(course.id).single()
        val session = SessionEntity("session", course.id, 1, 600, "C", 0, 1, 1, result = SessionResult.DONE)
        dao.insertSession(session)
        dao.updateTopic(dao.getTopic(topic.id)!!.copy(isCompleted = true, completionDate = dao.getSession(session.id)!!.date))
        assertFalse(repository.toggleTopicCompletion(course.id, topic.id))
        assertNull(dao.getTopic(topic.id)?.takeIf { it.isCompleted })
        assertEquals(session, dao.getSession(session.id))
        assertTrue(repository.toggleTopicCompletion(course.id, topic.id))
        assertNull(dao.getTopic(topic.id)?.takeIf { it.isCompleted }?.completionDate)
        assertNull(dao.getTopic(topic.id)?.takeIf { it.isCompleted }?.completionDate)
    }

    @Test fun invalidAndCompletedTopicsRejectWithoutMutatingProgress() = runBlocking {
        val first = repository.createCourse("C", 0, topics = listOf("Массивы"))
        val second = repository.createCourse("Other", 1, topics = listOf("Указатели"))
        val topic = dao.getTopics(first.id).single()
        reject(RepositoryError.INVALID_TOPIC) { repository.toggleTopicCompletion(second.id, topic.id) }
        reject(RepositoryError.INVALID_TOPIC) { repository.toggleTopicCompletion(first.id, "missing") }
        dao.updateTopic(topic)
        dao.updateCourse(first.copy(colorId = null, isCompleted = true, completedAt = 150))
        reject(RepositoryError.COURSE_COMPLETED) { repository.toggleTopicCompletion(first.id, topic.id) }
        assertNull(dao.getTopic(topic.id)?.takeIf { it.isCompleted })
    }

    private suspend fun reject(error: RepositoryError, block: suspend () -> Unit) {
        try { block(); fail("Expected $error") }
        catch (expected: RepositoryException) { assertEquals(error, expected.error) }
    }
}
