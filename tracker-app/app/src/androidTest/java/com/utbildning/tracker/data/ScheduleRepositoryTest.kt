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
class ScheduleRepositoryTest {
    private lateinit var db: TrackerDatabase
    private lateinit var repo: TrackerRepository
    private var clock = Instant.parse("2026-09-28T10:00:00Z").toEpochMilli()
    private val dao get() = db.trackerDao()
    private val monday = LocalDate.parse("2026-09-28").toEpochDay()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrackerDatabase::class.java).build()
        repo = TrackerRepository(db, now = { clock }, zone = { ZoneId.of("UTC") })
    }
    @After fun close() = db.close()

    @Test fun initialGenerationSkipsPastStartAndExpandsFarHorizonWithoutDuplicates() = runBlocking {
        val course = repo.createCourse("C", 0, CourseMode.SCHEDULED)
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 9 * 60)))
        assertTrue(dao.getSessions(course.id).all { it.date > monday })
        val far = monday + 300
        repo.synchronize(far)
        val sessions = dao.getSessions(course.id)
        assertTrue(sessions.last().date > monday + 290)
        repo.synchronize(far)
        assertEquals(sessions, dao.getSessions(course.id))
        assertEquals(far, dao.getSchedule(course.id)?.generatedThrough)
    }

    @Test fun longAbsenceCatchesUpOnlyPastPendingAndEndDateDoesNotCompleteCourse() = runBlocking {
        val course = repo.createCourse("C", 0, CourseMode.SCHEDULED)
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 12 * 60)), monday + 14)
        val first = dao.getSessions(course.id).first()
        repo.setSessionResult(first.id, SessionResult.DONE)
        clock = Instant.parse("2027-03-29T10:00:00Z").toEpochMilli()
        repo.synchronize()
        val sessions = dao.getSessions(course.id)
        assertEquals(3, sessions.size)
        assertEquals(SessionResult.DONE, sessions.first().result)
        assertTrue(sessions.drop(1).all { it.result == SessionResult.SKIPPED })
        assertFalse(dao.getCourse(course.id)!!.isCompleted)
    }

    @Test fun midnightSkipsYesterdayButLeavesTodayPending() = runBlocking {
        val course = repo.createCourse("C", 0, CourseMode.SCHEDULED)
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 23 * 60), WeeklyRule(2, 60)))
        clock = Instant.parse("2026-09-29T00:00:00Z").toEpochMilli()
        repo.synchronize()
        assertEquals(SessionResult.SKIPPED, dao.getSessions(course.id).first().result)
        assertEquals(SessionResult.PENDING, dao.getSessions(course.id)[1].result)
    }

    @Test fun absenceBeyondInitialHorizonGeneratesMissingPastWeeks() = runBlocking {
        val course = repo.createCourse("C", 0, CourseMode.SCHEDULED)
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)))
        clock = Instant.parse("2027-03-29T10:00:00Z").toEpochMilli()
        repo.synchronize()
        val today = LocalDate.parse("2027-03-29").toEpochDay()
        val missedBeyondOldHorizon = dao.getSessions(course.id).filter { it.date > monday + 90 && it.date < today }
        assertTrue(missedBeyondOldHorizon.isNotEmpty())
        assertTrue(missedBeyondOldHorizon.all { it.result == SessionResult.SKIPPED })
        assertEquals(SessionResult.PENDING, dao.getSessions(course.id).single { it.date == today }.result)
    }

    @Test fun invalidModesAndReplacementDoNotMutateSchedule() = runBlocking {
        val without = repo.createCourse("C", 0, CourseMode.UNSCHEDULED, topics = listOf("Массивы"))
        rejects(RepositoryError.INVALID_SCHEDULE) { repo.saveInitialSchedule(without.id, listOf(WeeklyRule(1, 720))) }
        val course = repo.createCourse("Practice", 1, CourseMode.SCHEDULED)
        rejects(RepositoryError.INVALID_SCHEDULE) { repo.saveInitialSchedule(course.id, emptyList()) }
        rejects(RepositoryError.INVALID_SCHEDULE) { repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)), monday - 1) }
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)))
        val original = dao.getSessions(course.id)
        rejects(RepositoryError.SCHEDULE_EXISTS) { repo.saveInitialSchedule(course.id, listOf(WeeklyRule(2, 720))) }
        assertEquals(original, dao.getSessions(course.id))
        repo.pauseCourse(course.id)
        rejects(RepositoryError.INVALID_SCHEDULE) { repo.saveInitialSchedule(course.id, listOf(WeeklyRule(2, 720))) }
        assertNull(dao.getSchedule(course.id))
        assertTrue(dao.getCourse(course.id)!!.isPaused)
    }

    @Test fun renamingAndRecoloringOnlyRefreshFutureUnmarkedSnapshots() = runBlocking {
        val course = repo.createCourse("C", 0, CourseMode.SCHEDULED)
        repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 720)))
        val sessions = dao.getSessions(course.id)
        repo.setSessionResult(sessions[1].id, SessionResult.DONE)
        clock = Instant.parse("2026-09-28T13:00:00Z").toEpochMilli()
        repo.updateCourse(course.id, "New", 1)
        val updated = dao.getSessions(course.id)
        assertEquals("New", updated[0].courseName)
        assertEquals("New", updated[1].courseName)
        assertEquals("New", updated[2].courseName)
        assertEquals(1, updated[2].colorId)
    }

    private suspend fun rejects(error: RepositoryError, action: suspend () -> Unit) {
        try { action(); fail("Expected $error") } catch (actual: RepositoryException) { assertEquals(error, actual.error) }
    }
}
