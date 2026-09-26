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
class SessionResultRepositoryTest {
    private lateinit var db: TrackerDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var course: CourseEntity
    private lateinit var topics: List<TopicEntity>
    private var timestamp = 100L
    private val dao get() = db.trackerDao()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrackerDatabase::class.java).build()
        repo = TrackerRepository(db, now = { timestamp })
        course = repo.createCourse("C", 0, CourseMode.SCHEDULED, topics = listOf("Массивы", "Указатели", "Структуры"))
        topics = dao.getTopics(course.id)
        dao.insertSession(SessionEntity("a", course.id, 1, 600, "C", 0, 1, 1))
        dao.insertSession(SessionEntity("b", course.id, 2, 600, "C", 0, 1, 1))
    }
    @After fun close() = db.close()

    @Test fun doneWithoutTopicsAndExplicitSelectionAreIndependentOfOrder() = runBlocking {
        repo.setSessionResult("a", SessionResult.DONE, emptySet())
        assertTrue(dao.getCompletions(course.id).isEmpty())
        repo.setSessionResult("a", SessionResult.DONE, setOf(topics[2].id, topics[0].id))
        assertEquals(setOf(topics[2].id, topics[0].id), repo.getSessionDetails("a")!!.selectedTopicIds)
        repo.setSessionResult("a", SessionResult.DONE, setOf(topics[2].id))
        assertNull(dao.getCompletion(topics[0].id))
        assertEquals(2, dao.getHistory("a").size)
        repo.setSessionResult("a", SessionResult.SKIPPED)
        assertTrue(dao.getCompletions(course.id).isEmpty())
        assertEquals(2, dao.getHistory("a").size)
    }

    @Test fun staleHistoryCannotReclaimAnotherSessionsOrManualProgress() = runBlocking {
        val id = topics[0].id
        repo.setSessionResult("a", SessionResult.DONE, setOf(id))
        repo.toggleTopicCompletion(course.id, id)
        repo.setSessionResult("b", SessionResult.DONE, setOf(id))
        repo.setSessionResult("a", SessionResult.SKIPPED)
        assertEquals("b", dao.getCompletion(id)?.sessionId)
        repo.setSessionResult("a", SessionResult.DONE)
        assertEquals("b", dao.getCompletion(id)?.sessionId)
        assertFalse(repo.getSessionDetails("a")!!.selectableTopics.any { it.id == id })
        repo.toggleTopicCompletion(course.id, id)
        repo.setSessionResult("a", SessionResult.DONE)
        assertNull(dao.getCompletion(id))
        repo.toggleTopicCompletion(course.id, id)
        repo.setSessionResult("b", SessionResult.PENDING)
        assertEquals(CompletionSource.MANUAL, dao.getCompletion(id)?.source)
    }

    @Test fun repeatedDonePreservesOwnSelectionAndInvalidSelectionRollsBack() = runBlocking {
        repo.setSessionResult("a", SessionResult.DONE, setOf(topics[0].id))
        val completion = dao.getCompletion(topics[0].id)
        val originalSession = dao.getSession("a")
        val originalCourse = dao.getCourse(course.id)
        timestamp = 200L
        repo.setSessionResult("a", SessionResult.DONE)
        repo.setSessionResult("a", SessionResult.DONE, setOf(topics[0].id))
        assertEquals(originalSession, dao.getSession("a"))
        assertEquals(originalCourse, dao.getCourse(course.id))
        assertEquals(completion, dao.getCompletion(topics[0].id))
        try { repo.setSessionResult("a", SessionResult.DONE, setOf(topics[1].id, "foreign")); fail("Expected invalid topic") }
        catch (actual: RepositoryException) { assertEquals(RepositoryError.INVALID_TOPIC, actual.error) }
        assertEquals(completion, dao.getCompletion(topics[0].id))
        assertNull(dao.getCompletion(topics[1].id))
        assertEquals(1, dao.getHistory("a").size)
    }

    @Test fun correctingCompletedCoursesHistoryPreservesCompletionStatus() = runBlocking {
        repo.setSessionResult("a", SessionResult.DONE, setOf(topics[0].id))
        repo.completeCourse(course.id)
        val completedAt = dao.getCourse(course.id)!!.completedAt
        assertEquals(setOf(topics[0].id), repo.getSessionDetails("a")!!.selectedTopicIds)
        repo.setSessionResult("a", SessionResult.SKIPPED)
        val replacement = dao.getCompletion(topics[0].id)!!
        assertEquals(CompletionSource.COURSE_COMPLETION, replacement.source)
        assertEquals(completedAt, replacement.completedAt)
        assertTrue(dao.getCourse(course.id)!!.isCompleted)
        assertEquals(3, dao.getCompletions(course.id).size)
        assertEquals(1, dao.getHistory("a").size)
    }
}
