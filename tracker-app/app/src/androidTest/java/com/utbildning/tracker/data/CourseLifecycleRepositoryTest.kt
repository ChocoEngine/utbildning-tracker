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

    @Test fun exhaustionAndResumeDoNotBackfillSuspensionInterval(): Unit = runBlocking {
        val course = repo.createCourse("C", 0, topics = listOf("Массивы"))
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)))
        val topic = dao.getTopics(course.id).single()
        repo.toggleTopicCompletion(course.id, topic.id)
        assertTrue(dao.getSessions(course.id).isEmpty())
        assertTrue(dao.hasExhaustedTopics(course.id))
        assertEquals(LocalDate.parse("2026-09-27").toEpochDay(), dao.getSchedule(course.id)!!.generatedThrough)
        repo.synchronize()
        clock = Instant.parse("2026-11-30T10:00:00Z").toEpochMilli()
        repo.synchronize()
        assertTrue(dao.getSessions(course.id).isEmpty())
        repo.toggleTopicCompletion(course.id, topic.id)
        assertFalse(dao.hasExhaustedTopics(course.id))
        val restartDay = LocalDate.parse("2026-11-30").toEpochDay()
        assertTrue(dao.getSessions(course.id).all { it.date >= restartDay })
        assertEquals(restartDay, dao.getSessions(course.id).first().date)
        repo.toggleTopicCompletion(course.id, topic.id)
    }

    @Test fun addingNewTopicResumesAndDoesNotEraseCompletedTopic() = runBlocking {
        val course = repo.createCourse("C", 0, topics = listOf("Массивы"))
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)))
        val old = dao.getTopics(course.id).single()
        repo.toggleTopicCompletion(course.id, old.id)
        clock = Instant.parse("2026-10-12T13:00:00Z").toEpochMilli()
        repo.saveTopicList(course.id, "Указатели")
        assertNotNull(dao.getTopic(old.id)?.takeIf { it.isCompleted })
        assertEquals(2, dao.getTopics(course.id).size)
        assertEquals(LocalDate.parse("2026-10-19").toEpochDay(), dao.getSessions(course.id).first().date)
    }

    @Test fun exhaustionAndResumeSurviveDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "lifecycle-reopen.db"
        context.deleteDatabase(name)
        var fileDb = TrackerDatabase.open(context, name)
        try {
            var fileRepo = TrackerRepository(fileDb, now = { clock }, zone = { ZoneId.of("UTC") })
            val course = fileRepo.createCourse("C", 0, topics = listOf("Массивы"))
            fileRepo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)))
            val topic = fileDb.trackerDao().getTopics(course.id).single()
            fileRepo.toggleTopicCompletion(course.id, topic.id)
            assertTrue(fileDb.trackerDao().hasExhaustedTopics(course.id))
            fileDb.close()
            clock = Instant.parse("2026-12-07T10:00:00Z").toEpochMilli()
            fileDb = TrackerDatabase.open(context, name)
            fileRepo = TrackerRepository(fileDb, now = { clock }, zone = { ZoneId.of("UTC") })
            fileRepo.synchronize()
            assertTrue(fileDb.trackerDao().hasExhaustedTopics(course.id))
            assertTrue(fileDb.trackerDao().getSessions(course.id).isEmpty())
            fileRepo.toggleTopicCompletion(course.id, topic.id)
            val resumed = fileDb.trackerDao().getSessions(course.id)
            assertEquals(LocalDate.parse("2026-12-07").toEpochDay(), resumed.first().date)
            assertTrue(resumed.all { it.date >= LocalDate.parse("2026-12-07").toEpochDay() })
        } finally { fileDb.close(); context.deleteDatabase(name) }
    }

    @Test fun courseWithoutTopicsKeepsGeneratingAndRemindersRemainEligible() = runBlocking {
        val course = repo.createCourse("Practice", 0)
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)))
        repo.synchronize()
        assertFalse(dao.hasExhaustedTopics(course.id))
        assertTrue(dao.getSessions(course.id).isNotEmpty())
        assertEquals(listOf(course.id), dao.getReminderCourses().map { it.id })
    }

    @Test fun pauseCatchesUpAndKeepsStartedAndExplicitlyMarkedFutureSessions() = runBlocking {
        val course = repo.createCourse("C", 0)
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)))
        val first = dao.getSessions(course.id).first()
        val markedFuture = dao.getSessions(course.id)[3]
        // Preserve legacy early results without permitting new future writes.
        dao.updateSession(markedFuture.copy(result = SessionResult.DONE))
        clock = Instant.parse("2026-10-05T13:00:00Z").toEpochMilli()
        repo.pauseCourse(course.id)
        assertTrue(dao.getCourse(course.id)!!.isPaused)
        assertNull(dao.getSchedule(course.id))
        assertNotNull(dao.getCourse(course.id))
        assertEquals(SessionResult.SKIPPED, dao.getSession(first.id)?.result)
        assertEquals(SessionResult.DONE, dao.getSession(markedFuture.id)?.result)
        assertEquals(3, dao.getSessions(course.id).size)
    }

    @Test fun completeKeepsExistingSourcesHistoryAndReleasesColorDeleteCascades() = runBlocking {
        val course = repo.createCourse("C", 0, topics = listOf("Массивы", "Указатели"))
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)))
        val topics = dao.getTopics(course.id)
        val session = dao.getSessions(course.id).first()
        repo.setSessionResult(session.id, SessionResult.DONE, setOf(topics.first().id))
        val own = dao.getTopic(topics.first().id)?.takeIf { it.isCompleted }
        repo.completeCourse(course.id)
        assertTrue(dao.getCourse(course.id)!!.isCompleted)
        assertEquals(own, dao.getTopic(topics.first().id)?.takeIf { it.isCompleted })
        assertNull(dao.getTopic(topics.last().id)?.takeIf { it.isCompleted }?.completionDate)
        assertEquals(SessionResult.DONE, dao.getSession(session.id)?.result)
        assertNull(dao.getCourse(course.id)!!.colorId)
        assertNull(dao.getSchedule(course.id))
        val another = repo.createCourse("Another", 0)
        repo.deleteCourse(course.id)
        assertNull(dao.getCourse(course.id))
        assertTrue(dao.getTopics(course.id).isEmpty())
        assertTrue(dao.getSessions(course.id).isEmpty())
        assertNotNull(dao.getCourse(another.id))
    }
}
