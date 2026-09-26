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
        CourseMode.entries.forEachIndexed { index, mode ->
            val course = repository.createCourse("C", index, mode, topics = listOf("Массивы", "Указатели"))
            val topics = dao.getTopics(course.id)
            assertTrue(repository.toggleTopicCompletion(course.id, topics.last().id))
            assertNull(dao.getCompletion(topics.first().id))
            assertEquals(CompletionSource.MANUAL, dao.getCompletion(topics.last().id)?.source)
            assertEquals(200L, dao.getCompletion(topics.last().id)?.completedAt)
            assertFalse(repository.toggleTopicCompletion(course.id, topics.last().id))
            assertNull(dao.getCompletion(topics.last().id))
            assertTrue(dao.getSessions(course.id).isEmpty())
            assertNull(dao.getSchedule(course.id))
        }
    }

    @Test fun removingSessionCompletionKeepsHistoryAndSessionResult() = runBlocking {
        val course = repository.createCourse("C", 0, CourseMode.SCHEDULED, topics = listOf("Массивы"))
        val topic = dao.getTopics(course.id).single()
        val session = SessionEntity("session", course.id, 1, 600, "C", 0, 1, 1, result = SessionResult.DONE)
        val history = SessionTopicHistoryEntity(session.id, topic.id, course.id, topic.title, 50)
        dao.insertSession(session)
        dao.insertHistory(history)
        dao.insertCompletion(TopicCompletionEntity(topic.id, course.id, CompletionSource.SESSION, 50, session.id))
        assertFalse(repository.toggleTopicCompletion(course.id, topic.id))
        assertNull(dao.getCompletion(topic.id))
        assertEquals(listOf(history), dao.getHistory(session.id))
        assertEquals(session, dao.getSession(session.id))
        assertTrue(repository.toggleTopicCompletion(course.id, topic.id))
        assertEquals(CompletionSource.MANUAL, dao.getCompletion(topic.id)?.source)
        assertNull(dao.getCompletion(topic.id)?.sessionId)
    }

    @Test fun invalidAndCompletedTopicsRejectWithoutMutatingProgress() = runBlocking {
        val first = repository.createCourse("C", 0, CourseMode.UNSCHEDULED, topics = listOf("Массивы"))
        val second = repository.createCourse("Other", 1, CourseMode.SCHEDULED, topics = listOf("Указатели"))
        val topic = dao.getTopics(first.id).single()
        reject(RepositoryError.INVALID_TOPIC) { repository.toggleTopicCompletion(second.id, topic.id) }
        reject(RepositoryError.INVALID_TOPIC) { repository.toggleTopicCompletion(first.id, "missing") }
        dao.updateTopic(topic.copy(archivedAt = 100))
        reject(RepositoryError.INVALID_TOPIC) { repository.toggleTopicCompletion(first.id, topic.id) }
        dao.updateTopic(topic)
        dao.updateCourse(first.copy(isCompleted = true, completedAt = 150))
        reject(RepositoryError.COURSE_COMPLETED) { repository.toggleTopicCompletion(first.id, topic.id) }
        assertNull(dao.getCompletion(topic.id))
    }

    private suspend fun reject(error: RepositoryError, block: suspend () -> Unit) {
        try { block(); fail("Expected $error") }
        catch (expected: RepositoryException) { assertEquals(error, expected.error) }
    }
}
