package com.utbildning.tracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.WeeklyRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseLifecycleRepositoryTest {
    private lateinit var db: TrackerDatabase
    private lateinit var repo: TrackerRepository
    private val dao get() = db.trackerDao()
    private var clock = Instant.parse("2026-09-28T10:00:00Z").toEpochMilli()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrackerDatabase::class.java).build()
        repo = TrackerRepository(db, now = { clock }, zone = { ZoneId.of("UTC") })
    }
    @After fun close() = db.close()

    @Test fun exhaustionDismissalAndResumeDoNotBackfillSuspensionInterval() = runBlocking {
        val course = repo.createCourse("C", 0, CourseMode.SCHEDULED, topics = listOf("Массивы"))
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)))
        val topic = dao.getTopics(course.id).single()
        repo.toggleTopicCompletion(course.id, topic.id)
        assertTrue(dao.getSessions(course.id).isEmpty())
        assertNotNull(dao.getCourse(course.id)?.exhaustedAt)
        assertTrue(repo.shouldOfferCompletion(course.id))
        repo.dismissCompletionPrompt(course.id)
        repo.synchronize()
        assertFalse(repo.shouldOfferCompletion(course.id))
        clock = Instant.parse("2026-11-30T10:00:00Z").toEpochMilli()
        repo.synchronize()
        assertTrue(dao.getSessions(course.id).isEmpty())
        repo.toggleTopicCompletion(course.id, topic.id)
        assertNull(dao.getCourse(course.id)?.exhaustedAt)
        assertFalse(dao.getCourse(course.id)!!.completionPromptDismissed)
        val restartDay = LocalDate.parse("2026-11-30").toEpochDay()
        assertTrue(dao.getSessions(course.id).all { it.date >= restartDay })
        assertEquals(restartDay, dao.getSessions(course.id).first().date)
        repo.toggleTopicCompletion(course.id, topic.id)
        assertTrue(repo.shouldOfferCompletion(course.id))
    }

    @Test fun addingNewTopicResumesAndDoesNotEraseCompletedTopic() = runBlocking {
        val course = repo.createCourse("C", 0, CourseMode.SCHEDULED, topics = listOf("Массивы"))
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)))
        val old = dao.getTopics(course.id).single()
        repo.toggleTopicCompletion(course.id, old.id)
        clock = Instant.parse("2026-10-12T13:00:00Z").toEpochMilli()
        repo.saveTopicList(course.id, "Указатели")
        assertNotNull(dao.getCompletion(old.id))
        assertEquals(2, dao.getTopics(course.id).size)
        assertEquals(LocalDate.parse("2026-10-19").toEpochDay(), dao.getSessions(course.id).first().date)
    }

    @Test fun exhaustedBoundaryAndDismissalSurviveDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "lifecycle-reopen.db"
        context.deleteDatabase(name)
        var fileDb = TrackerDatabase.open(context, name)
        try {
            var fileRepo = TrackerRepository(fileDb, now = { clock }, zone = { ZoneId.of("UTC") })
            val course = fileRepo.createCourse("C", 0, CourseMode.SCHEDULED, topics = listOf("Массивы"))
            fileRepo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)))
            val topic = fileDb.trackerDao().getTopics(course.id).single()
            fileRepo.toggleTopicCompletion(course.id, topic.id)
            fileRepo.dismissCompletionPrompt(course.id)
            val stoppedAt = fileDb.trackerDao().getCourse(course.id)!!.exhaustedAt
            fileDb.close()
            clock = Instant.parse("2026-12-07T10:00:00Z").toEpochMilli()
            fileDb = TrackerDatabase.open(context, name)
            fileRepo = TrackerRepository(fileDb, now = { clock }, zone = { ZoneId.of("UTC") })
            fileRepo.synchronize()
            assertEquals(stoppedAt, fileDb.trackerDao().getCourse(course.id)!!.exhaustedAt)
            assertFalse(fileRepo.shouldOfferCompletion(course.id))
            assertTrue(fileDb.trackerDao().getSessions(course.id).isEmpty())
        } finally { fileDb.close(); context.deleteDatabase(name) }
    }

    @Test fun pauseCatchesUpAndKeepsStartedAndExplicitlyMarkedFutureSessions() = runBlocking {
        val course = repo.createCourse("C", 0, CourseMode.SCHEDULED)
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)))
        val first = dao.getSessions(course.id).first()
        val markedFuture = dao.getSessions(course.id)[3]
        repo.setSessionResult(markedFuture.id, SessionResult.DONE)
        clock = Instant.parse("2026-10-05T13:00:00Z").toEpochMilli()
        repo.pauseCourse(course.id)
        assertTrue(dao.getCourse(course.id)!!.isPaused)
        assertNull(dao.getSchedule(course.id))
        assertNotNull(dao.getReservation(course.id))
        assertEquals(SessionResult.SKIPPED, dao.getSession(first.id)?.result)
        assertEquals(SessionResult.DONE, dao.getSession(markedFuture.id)?.result)
        assertEquals(3, dao.getSessions(course.id).size)
    }

    @Test fun completeKeepsExistingSourcesHistoryAndReleasesColorDeleteCascades() = runBlocking {
        val course = repo.createCourse("C", 0, CourseMode.SCHEDULED, topics = listOf("Массивы", "Указатели"))
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)))
        val topics = dao.getTopics(course.id)
        val session = dao.getSessions(course.id).first()
        repo.setSessionResult(session.id, SessionResult.DONE, setOf(topics.first().id))
        val own = dao.getCompletion(topics.first().id)
        repo.completeCourse(course.id)
        assertTrue(dao.getCourse(course.id)!!.isCompleted)
        assertEquals(own, dao.getCompletion(topics.first().id))
        assertEquals(CompletionSource.COURSE_COMPLETION, dao.getCompletion(topics.last().id)?.source)
        assertEquals(SessionResult.DONE, dao.getSession(session.id)?.result)
        assertEquals(1, dao.getHistory(session.id).size)
        assertNull(dao.getReservation(course.id))
        assertNull(dao.getSchedule(course.id))
        val another = repo.createCourse("Another", 0, CourseMode.SCHEDULED)
        repo.deleteCourse(course.id)
        assertNull(dao.getCourse(course.id))
        assertTrue(dao.getTopics(course.id).isEmpty())
        assertTrue(dao.getSessions(course.id).isEmpty())
        assertNotNull(dao.getCourse(another.id))
    }
}
