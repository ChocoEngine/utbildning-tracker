package com.utbildning.tracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.local.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class SessionResultRepositoryTest {
    private lateinit var db: TrackerDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var course: CourseEntity
    private lateinit var topics: List<TopicEntity>
    // UTC yesterday, but already today's calendar date in Moscow.
    private var timestamp = Instant.parse("2026-09-26T22:30:00Z").toEpochMilli()
    private var zoneId = ZoneId.of("Europe/Moscow")
    private val date = LocalDate.of(2026, 9, 27).toEpochDay()
    private val dao get() = db.trackerDao()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrackerDatabase::class.java).build()
        repo = TrackerRepository(db, now = { timestamp }, zone = { zoneId })
        course = repo.createCourse("C", 0, topics = listOf("Массивы", "Указатели", "Структуры"))
        topics = dao.getTopics(course.id)
        for ((id, day) in listOf("today" to date, "yesterday" to date - 1, "old" to date - 2, "future" to date + 1)) {
            dao.insertSession(SessionEntity(id, course.id, day, 600, "C", 0, timestamp, timestamp))
        }
    }
    @After fun close() = db.close()

    @Test fun reminderCandidatesUseRepositoryDateWithoutChangingResultsOrTopics() = runBlocking {
        repo.saveInitialSchedule(course.id, listOf(com.utbildning.tracker.domain.WeeklyRule(1, 800)))
        val sessions = dao.getAllSessions()
        val beforeTopics = dao.getTopics(course.id)
        assertEquals(setOf("today", "yesterday", "future"),
            repo.getReminderCandidates().filter { it.id in setOf("today", "yesterday", "old", "future") }.map { it.id }.toSet())
        assertFalse(repo.getSessionDetails("future")!!.canEdit)
        // The same instant is September 26 in UTC: September 25 is still yesterday there.
        zoneId = ZoneId.of("UTC")
        assertTrue(repo.getReminderCandidates().any { it.id == "old" })
        zoneId = ZoneId.of("Europe/Moscow")
        assertFalse(repo.getReminderCandidates().any { it.id == "old" })
        assertEquals(sessions, dao.getAllSessions())
        assertEquals(beforeTopics, dao.getTopics(course.id))
        for (result in listOf(SessionResult.DONE, SessionResult.SKIPPED)) {
            dao.updateSession(dao.getSession("today")!!.copy(result = result))
            assertFalse(repo.getReminderCandidates().any { it.id == "today" })
        }
        timestamp += 86_400_000
        assertFalse(repo.getReminderCandidates().any { it.id == "yesterday" })
        assertEquals(SessionResult.PENDING, dao.getSession("yesterday")!!.result)
    }

    @Test fun reminderCandidatesExcludeInactiveOrUnscheduledOrExhaustedCourses() = runBlocking {
        repo.saveInitialSchedule(course.id, listOf(com.utbildning.tracker.domain.WeeklyRule(1, 800)))
        assertTrue(repo.getReminderCandidates().isNotEmpty())
        for (inactive in listOf(course.copy(isPaused = true), course.copy(isCompleted = true, colorId = null, completedAt = System.currentTimeMillis()))) {
            dao.updateCourse(inactive)
            assertTrue(repo.getReminderCandidates().isEmpty())
        }
        dao.updateCourse(course)
        topics.forEach { dao.updateTopic(it.copy(isCompleted = true)) }
        assertTrue(repo.getReminderCandidates().isEmpty())
        dao.updateTopic(topics.first())
        assertTrue(repo.getReminderCandidates().isNotEmpty())
        dao.deleteSchedule(course.id)
        assertTrue(repo.getReminderCandidates().isEmpty())
    }

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
        // Keep this session in history: at completion time its 10:00 local start has passed.
        timestamp = Instant.parse("2026-09-27T09:00:00Z").toEpochMilli()
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

    @Test fun directCompletionOnlyHandlesCoursesWithoutTopics() = runBlocking {
        assertFalse(repo.markSessionDoneIfNoTopics("today"))
        assertEquals(SessionResult.PENDING, dao.getSession("today")!!.result)

        val noTopics = repo.createCourse("Practice", 1)
        dao.insertSession(SessionEntity("practice", noTopics.id, date, 720, noTopics.name, noTopics.colorId, timestamp, timestamp))
        assertTrue(repo.markSessionDoneIfNoTopics("practice"))
        assertEquals(SessionResult.DONE, dao.getSession("practice")!!.result)
    }

    @Test fun pendingNotificationActionsAreAtomicAndNeverOverwriteAResult() = runBlocking {
        assertEquals(PendingSessionActionResult.NEEDS_TOPICS,
            repo.applyPendingSessionAction("today", SessionResult.DONE))
        assertEquals(SessionResult.PENDING, dao.getSession("today")!!.result)
        assertEquals(PendingSessionActionResult.APPLIED,
            repo.applyPendingSessionAction("today", SessionResult.SKIPPED))
        assertEquals(PendingSessionActionResult.IGNORED,
            repo.applyPendingSessionAction("today", SessionResult.DONE))
        assertEquals(SessionResult.SKIPPED, dao.getSession("today")!!.result)
        assertTrue(dao.getTopics(course.id).none { it.isCompleted })

        val noTopics = repo.createCourse("Practice action", 1)
        dao.insertSession(SessionEntity("practice_action", noTopics.id, date, 720,
            noTopics.name, noTopics.colorId, timestamp, timestamp))
        assertEquals(PendingSessionActionResult.APPLIED,
            repo.applyPendingSessionAction("practice_action", SessionResult.DONE))
        assertEquals(PendingSessionActionResult.IGNORED,
            repo.applyPendingSessionAction("practice_action", SessionResult.SKIPPED))
        assertEquals(SessionResult.DONE, dao.getSession("practice_action")!!.result)
    }

    @Test fun concurrentPendingActionsAndCourseCompletionHaveConsistentAtomicOutcomes() = runBlocking {
        val noTopics = repo.createCourse("Concurrent practice", 1)
        dao.insertSession(SessionEntity("concurrent", noTopics.id, date, 720,
            noTopics.name, noTopics.colorId, timestamp, timestamp))
        val start = CompletableDeferred<Unit>()
        val competing = listOf(SessionResult.DONE, SessionResult.SKIPPED).map { result ->
            async(Dispatchers.IO) { start.await(); repo.applyPendingSessionAction("concurrent", result) }
        }
        start.complete(Unit)
        val outcomes = competing.awaitAll()
        assertEquals(1, outcomes.count { it == PendingSessionActionResult.APPLIED })
        assertEquals(1, outcomes.count { it == PendingSessionActionResult.IGNORED })
        assertTrue(dao.getSession("concurrent")!!.result in setOf(SessionResult.DONE, SessionResult.SKIPPED))

        val completionStart = CompletableDeferred<Unit>()
        val action = async(Dispatchers.IO) {
            completionStart.await()
            repo.applyPendingSessionAction("today", SessionResult.SKIPPED)
        }
        val completion = async(Dispatchers.IO) {
            completionStart.await()
            repo.completeCourse(course.id)
        }
        completionStart.complete(Unit)
        action.await()
        completion.await()

        assertTrue(repo.getCourse(course.id)!!.isCompleted)
        assertTrue(dao.getTopics(course.id).all { it.isCompleted })
        val survivingSession = dao.getSession("today")
        assertTrue(survivingSession == null || survivingSession.result in setOf(SessionResult.DONE, SessionResult.SKIPPED))
        assertTrue(dao.getTopics(course.id).none { it.completionDate == date })
    }

    @Test fun pendingNotificationActionsIgnoreMissingOldFutureAndCompletedSessions() = runBlocking {
        assertEquals(PendingSessionActionResult.IGNORED,
            repo.applyPendingSessionAction("missing", SessionResult.SKIPPED))
        assertEquals(PendingSessionActionResult.IGNORED,
            repo.applyPendingSessionAction("old", SessionResult.SKIPPED))
        assertEquals(PendingSessionActionResult.IGNORED,
            repo.applyPendingSessionAction("future", SessionResult.DONE))
        repo.completeCourse(course.id)
        assertEquals(PendingSessionActionResult.IGNORED,
            repo.applyPendingSessionAction("today", SessionResult.SKIPPED))
    }

    @Test fun todayAndYesterdayAreEditableWhileOlderAndFutureDatesAreReadOnly() = runBlocking {
        for (id in listOf("today", "yesterday")) assertTrue(repo.getSessionDetails(id)!!.canEdit)
        for (id in listOf("old", "future")) {
            assertFalse(repo.getSessionDetails(id)!!.canEdit)
            for (result in SessionResult.entries) {
                try { repo.setSessionResult(id, result); fail("Date restriction") }
                catch (e: RepositoryException) { assertEquals(RepositoryError.INVALID_SESSION_DATE, e.error) }
            }
            assertEquals(SessionResult.PENDING, dao.getSession(id)!!.result)
        }
        repo.synchronize()
        assertEquals(SessionResult.PENDING, dao.getSession("yesterday")!!.result)
        assertEquals(SessionResult.SKIPPED, dao.getSession("old")!!.result)
        repo.setSessionResult("yesterday", SessionResult.DONE, setOf(topics[0].id))
        assertEquals(date - 1, dao.getTopic(topics[0].id)!!.completionDate)
        timestamp += 86400000L
        assertTrue(repo.getSessionDetails("today")!!.canEdit)
        assertFalse(repo.getSessionDetails("yesterday")!!.canEdit)
    }

    @Test fun overnightAndOpenEndedYesterdaySessionsStayPendingAndCanBeCorrected() = runBlocking {
        dao.updateSession(dao.getSession("yesterday")!!.copy(startMinute = 23 * 60 + 30, endMinute = 60))
        val practice = repo.createCourse("Practice", 1)
        dao.insertSession(SessionEntity("open_ended", practice.id, date - 1, 23 * 60 + 30,
            practice.name, practice.colorId, timestamp, timestamp, endMinute = null))

        repo.synchronize()
        assertEquals(SessionResult.PENDING, dao.getSession("yesterday")!!.result)
        assertEquals(SessionResult.PENDING, dao.getSession("open_ended")!!.result)

        repo.setSessionResult("yesterday", SessionResult.DONE, setOf(topics[1].id))
        assertEquals(date - 1, dao.getTopic(topics[1].id)!!.completionDate)
        val savedYesterday = dao.getSession("yesterday")
        timestamp += 1_000L
        assertFalse(repo.setSessionResult("yesterday", SessionResult.DONE, setOf(topics[1].id)))
        assertEquals(savedYesterday, dao.getSession("yesterday"))
        assertTrue(repo.markSessionDoneIfNoTopics("open_ended"))
        assertEquals(SessionResult.DONE, dao.getSession("open_ended")!!.result)

        repo.setSessionResult("yesterday", SessionResult.SKIPPED)
        assertFalse(dao.getTopic(topics[1].id)!!.isCompleted)
        assertNull(dao.getTopic(topics[1].id)!!.completionDate)
    }

    @Test fun editableDatesFollowTheCurrentZoneAndCrossYearBoundary() = runBlocking {
        assertTrue(repo.getSessionDetails("today")!!.canEdit)
        assertFalse(repo.getSessionDetails("old")!!.canEdit)
        zoneId = ZoneId.of("UTC")
        assertFalse(repo.getSessionDetails("today")!!.canEdit)
        assertTrue(repo.getSessionDetails("old")!!.canEdit)

        zoneId = ZoneId.of("Europe/Moscow")
        timestamp = Instant.parse("2026-12-31T22:30:00Z").toEpochMilli()
        val december31 = LocalDate.of(2026, 12, 31).toEpochDay()
        dao.insertSession(SessionEntity("year_yesterday", course.id, december31, 23 * 60 + 30,
            course.name, course.colorId, timestamp, timestamp, endMinute = 60))
        dao.insertSession(SessionEntity("year_old", course.id, december31 - 1, 23 * 60 + 30,
            course.name, course.colorId, timestamp, timestamp, endMinute = 60))
        assertTrue(repo.getSessionDetails("year_yesterday")!!.canEdit)
        assertFalse(repo.getSessionDetails("year_old")!!.canEdit)
    }
}
