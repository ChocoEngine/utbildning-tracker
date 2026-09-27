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
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class SessionResultRepositoryTest {
    private lateinit var db: TrackerDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var course: CourseEntity
    private lateinit var topics: List<TopicEntity>
    // UTC yesterday, but already today's calendar date in Moscow.
    private var timestamp = Instant.parse("2026-09-26T22:30:00Z").toEpochMilli()
    private val date = java.time.LocalDate.of(2026, 9, 27).toEpochDay()
    private val dao get() = db.trackerDao()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrackerDatabase::class.java).build()
        repo = TrackerRepository(db, now = { timestamp }, zone = { ZoneId.of("Europe/Moscow") })
        course = repo.createCourse("C", 0, topics = listOf("Массивы", "Указатели", "Структуры"))
        topics = dao.getTopics(course.id)
        for ((id, day) in listOf("today" to date, "past" to date - 1, "future" to date + 1)) {
            dao.insertSession(SessionEntity(id, course.id, day, 600, "C", 0, timestamp, timestamp))
        }
    }
    @After fun close() = db.close()

    @Test fun selectionChangesAndResetsOnlyItsOwnTopics() = runBlocking {
        val other = repo.createCourse("Other", 1, topics = listOf("Other"))
        val otherTopic = dao.getTopics(other.id).single().copy(isCompleted = true, completionDate = date)
        dao.updateTopic(otherTopic)
        repo.toggleTopicCompletion(course.id, topics[1].id)
        dao.updateTopic(topics[2].copy(isCompleted = true, completionDate = date - 1))
        repo.setSessionResult("today", SessionResult.DONE, topics.map { it.id }.toSet())
        assertEquals(date, dao.getTopic(topics[0].id)!!.completionDate)
        assertNull(dao.getTopic(topics[1].id)!!.completionDate)
        assertEquals(date - 1, dao.getTopic(topics[2].id)!!.completionDate)
        for (reset in listOf(SessionResult.PENDING, SessionResult.SKIPPED)) {
            repo.setSessionResult("today", reset)
            assertFalse(dao.getTopic(topics[0].id)!!.isCompleted)
            assertNull(dao.getTopic(topics[0].id)!!.completionDate)
            assertTrue(dao.getTopic(topics[1].id)!!.isCompleted)
            assertEquals(date - 1, dao.getTopic(topics[2].id)!!.completionDate)
            assertEquals(otherTopic, dao.getTopic(otherTopic.id))
            repo.setSessionResult("today", SessionResult.DONE, setOf(topics[0].id))
        }
    }

    @Test fun repeatedSaveIsIdempotentAndChangedSelectionIsReconciled() = runBlocking {
        repo.setSessionResult("today", SessionResult.DONE, setOf(topics[0].id))
        val session = dao.getSession("today")
        val savedCourse = dao.getCourse(course.id)
        val savedTopics = dao.getTopics(course.id)
        timestamp += 1000
        repo.setSessionResult("today", SessionResult.DONE)
        repo.setSessionResult("today", SessionResult.DONE, setOf(topics[0].id))
        assertEquals(session, dao.getSession("today"))
        assertEquals(savedCourse, dao.getCourse(course.id))
        assertEquals(savedTopics, dao.getTopics(course.id))
        repo.setSessionResult("today", SessionResult.DONE, setOf(topics[2].id))
        assertFalse(dao.getTopic(topics[0].id)!!.isCompleted)
        assertEquals(date, dao.getTopic(topics[2].id)!!.completionDate)
        try { repo.setSessionResult("today", SessionResult.DONE, setOf(topics[1].id, "foreign")); fail("Invalid selection") }
        catch (e: RepositoryException) { assertEquals(RepositoryError.INVALID_TOPIC, e.error) }
        assertFalse(dao.getTopic(topics[1].id)!!.isCompleted)
        assertEquals(date, dao.getTopic(topics[2].id)!!.completionDate)
    }

    @Test fun manualRemarkBecomesIndependentAndCourseCompletionKeepsDates() = runBlocking {
        assertTrue(topics.all { !it.isCompleted && it.completionDate == null })
        repo.setSessionResult("today", SessionResult.DONE, setOf(topics[0].id, topics[1].id))
        repo.toggleTopicCompletion(course.id, topics[0].id)
        repo.toggleTopicCompletion(course.id, topics[0].id)
        assertNull(dao.getTopic(topics[0].id)!!.completionDate)
        repo.completeCourse(course.id)
        assertEquals(date, dao.getTopic(topics[1].id)!!.completionDate)
        assertNull(dao.getTopic(topics[2].id)!!.completionDate)
        assertTrue(dao.getTopics(course.id).all { it.isCompleted })
        try { repo.setSessionResult("today", SessionResult.SKIPPED); fail("Completed course is read only") }
        catch (e: RepositoryException) { assertEquals(RepositoryError.COURSE_COMPLETED, e.error) }
        assertTrue(dao.getTopic(topics[1].id)!!.isCompleted)
        assertTrue(dao.getTopic(topics[0].id)!!.isCompleted)
    }

    @Test fun completionOfferIsOnlyReturnedByTheResultThatClosesLastTopics() = runBlocking {
        assertFalse(repo.setSessionResult("today", SessionResult.DONE, setOf(topics[0].id)))
        assertTrue(repo.setSessionResult("today", SessionResult.DONE, topics.map { it.id }.toSet()))
        assertFalse(repo.setSessionResult("today", SessionResult.DONE))
        assertFalse(repo.setSessionResult("today", SessionResult.DONE, topics.map { it.id }.toSet()))
    }

    @Test fun pastAndFutureAreReadOnlyWhileAutomaticCatchupWorks() = runBlocking {
        for (id in listOf("past", "future")) {
            assertFalse(repo.getSessionDetails(id)!!.canEdit)
            for (result in SessionResult.entries) {
                try { repo.setSessionResult(id, result); fail("Date restriction") }
                catch (e: RepositoryException) { assertEquals(RepositoryError.INVALID_SESSION_DATE, e.error) }
            }
            assertEquals(SessionResult.PENDING, dao.getSession(id)!!.result)
        }
        assertTrue(repo.getSessionDetails("today")!!.canEdit)
        timestamp += 86400000L
        assertFalse(repo.getSessionDetails("today")!!.canEdit)
        timestamp -= 86400000L
        repo.synchronize()
        assertEquals(SessionResult.SKIPPED, repo.getSessionDetails("past")!!.session.result)
        assertEquals(SessionResult.PENDING, dao.getSession("future")!!.result)
    }
}
