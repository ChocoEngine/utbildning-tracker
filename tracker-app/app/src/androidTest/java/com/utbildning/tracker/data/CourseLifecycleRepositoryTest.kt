package com.utbildning.tracker.data

import android.content.Context
import android.database.sqlite.SQLiteException
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

    @Test fun completionUsesOriginalResultsAndFullStartInstantAndIsIdempotent() = runBlocking {
        val moscow = ZoneId.of("Europe/Moscow")
        clock = Instant.parse("2026-10-05T09:00:00Z").toEpochMilli() // 12:00 in Moscow.
        repo = TrackerRepository(db, now = { clock }, zone = { moscow })
        val course = repo.createCourse("C", 0, topics = listOf("Массивы", "Указатели"))
        val other = repo.createCourse("Other", 1)
        val day = LocalDate.parse("2026-10-05")
        dao.insertSchedule(ScheduleEntity(course.id, day.minusDays(1).toEpochDay()))
        dao.insertScheduleRule(ScheduleRuleEntity(course.id, 1, 720))
        fun session(id: String, date: LocalDate, minute: Int, result: SessionResult = SessionResult.PENDING) =
            SessionEntity(id, course.id, date.toEpochDay(), minute, course.name, course.colorId, 11, 11, result = result)
        listOf(
            session("yesterday_pending", day.minusDays(1), 600),
            session("overnight_pending", day.minusDays(1), 23 * 60 + 30),
            session("started_pending", day, 660),
            session("exact_pending", day, 720),
            session("started_done", day, 600, SessionResult.DONE),
            session("started_skipped", day, 630, SessionResult.SKIPPED),
            session("future_pending", day, 721),
            session("future_done", day.plusDays(1), 600, SessionResult.DONE),
            session("future_skipped", day.plusDays(1), 630, SessionResult.SKIPPED),
        ).forEach { dao.insertSession(it) }
        dao.insertSession(SessionEntity("other_pending", other.id, day.minusDays(1).toEpochDay(), 600,
            other.name, other.colorId, 22, 22))
        val topics = dao.getTopics(course.id)
        dao.updateTopic(topics.first().copy(isCompleted = true, completionDate = day.minusDays(3).toEpochDay()))
        val completedBefore = dao.getTopic(topics.first().id)!!
        val doneBefore = dao.getSession("started_done")!!
        val skippedBefore = dao.getSession("started_skipped")!!

        repo.completeCourse(course.id)

        val completed = dao.getCourse(course.id)!!
        assertTrue(completed.isCompleted)
        assertFalse(completed.isPaused)
        assertEquals(clock, completed.completedAt)
        assertNull(completed.colorId)
        assertNull(dao.getSchedule(course.id))
        assertEquals(completedBefore, dao.getTopic(topics.first().id))
        assertTrue(dao.getTopic(topics.last().id)!!.isCompleted)
        assertNull(dao.getTopic(topics.last().id)!!.completionDate)
        for (id in listOf("yesterday_pending", "overnight_pending", "started_pending", "exact_pending"))
            assertEquals(id, SessionResult.DONE, dao.getSession(id)?.result)
        assertEquals(doneBefore.result, dao.getSession("started_done")?.result)
        assertEquals(doneBefore.updatedAt, dao.getSession("started_done")?.updatedAt)
        assertEquals(skippedBefore.result, dao.getSession("started_skipped")?.result)
        assertEquals(skippedBefore.updatedAt, dao.getSession("started_skipped")?.updatedAt)
        for (id in listOf("future_pending", "future_done", "future_skipped")) assertNull(id, dao.getSession(id))
        assertEquals(SessionResult.PENDING, dao.getSession("other_pending")?.result)

        val savedCourse = dao.getCourse(course.id)
        val savedSessions = dao.getSessions(course.id).map { it.record() }
        val savedTopics = dao.getTopics(course.id)
        clock += 86_400_000L
        repo.completeCourse(course.id)
        assertEquals(savedCourse, dao.getCourse(course.id))
        assertEquals(savedSessions, dao.getSessions(course.id).map { it.record() })
        assertEquals(savedTopics, dao.getTopics(course.id))
    }

    @Test fun completionSupportsPausedAndUnscheduledCoursesWithOrWithoutTopics() = runBlocking {
        val scheduled = repo.createCourse("Paused", 0, topics = listOf("Topic"))
        repo.saveInitialSchedule(scheduled.id, listOf(WeeklyRule(1, 720)))
        repo.pauseCourse(scheduled.id)
        val free = repo.createCourse("Free", 1)

        repo.completeCourse(scheduled.id)
        repo.completeCourse(free.id)

        assertTrue(dao.getCourse(scheduled.id)!!.isCompleted)
        assertFalse(dao.getCourse(scheduled.id)!!.isPaused)
        assertTrue(dao.getTopics(scheduled.id).single().isCompleted)
        assertTrue(dao.getCourse(free.id)!!.isCompleted)
        assertTrue(dao.getTopics(free.id).isEmpty())
    }

    @Test fun restartCompletedCourseResetsOnlyItsProgressAndHistoryAndUsesFirstFreeColor() = runBlocking {
        val category = CategoryEntity("category", "Programming")
        dao.insertCategory(category)
        val completed = CourseEntity("completed", "C from scratch", null, 1, 2, category.id,
            isCompleted = true, isPaused = false, completedAt = 2)
        dao.insertCourse(completed)
        val topics = listOf(
            TopicEntity("topic-2", completed.id, 1, "Pointers", isCompleted = true, completionDate = 20),
            TopicEntity("topic-1", completed.id, 0, "Basics", isCompleted = true, completionDate = null),
        )
        topics.forEach { dao.insertTopic(it) }
        dao.insertSchedule(ScheduleEntity(completed.id, 10, generatedThrough = 30))
        dao.insertScheduleRule(ScheduleRuleEntity(completed.id, 1, 600))
        dao.insertSession(SessionEntity("old", completed.id, 20, 600, completed.name, null, 1, 2,
            result = SessionResult.DONE))
        repo.createCourse("Uses color zero", 0)
        val other = repo.createCourse("Other", 3, topics = listOf("Keep"))
        val otherBefore = repo.getCourseDetails(other.id)

        clock = 500
        repo.restartCourse(completed.id)

        assertEquals(completed.copy(colorId = 1, isCompleted = false, completedAt = null, updatedAt = clock),
            dao.getCourse(completed.id))
        assertEquals(listOf("topic-1", "topic-2"), dao.getTopics(completed.id).map { it.id })
        assertTrue(dao.getTopics(completed.id).all { !it.isCompleted && it.completionDate == null })
        assertNull(dao.getSchedule(completed.id))
        assertTrue(dao.getScheduleRules(completed.id).isEmpty())
        assertTrue(dao.getSessions(completed.id).isEmpty())
        assertEquals(otherBefore, repo.getCourseDetails(other.id))
        assertEquals(listOf(1, 2, 4, 5, 6, 7, 8, 9), repo.availableColors(completed.id))
    }

    @Test fun restartIsUnavailableAtLimitAndLeavesCompletedCourseUntouched() = runBlocking {
        val completed = CourseEntity("completed", "Archived", null, 1, 2,
            isCompleted = true, completedAt = 2)
        dao.insertCourse(completed)
        val topic = TopicEntity("topic", completed.id, 0, "Keep", isCompleted = true, completionDate = 10)
        dao.insertTopic(topic)
        dao.insertSession(SessionEntity("old", completed.id, 10, 600, completed.name, null, 1, 1,
            result = SessionResult.DONE))
        (0..9).forEach { repo.createCourse("Active $it", it) }

        try {
            repo.restartCourse(completed.id)
            fail("Expected unfinished-course limit")
        } catch (failure: RepositoryException) {
            assertEquals(RepositoryError.COURSE_LIMIT, failure.error)
        }

        assertEquals(completed, dao.getCourse(completed.id))
        assertEquals(topic, dao.getTopic(topic.id))
        assertEquals(listOf("old"), dao.getSessions(completed.id).map { it.id })
    }

    @Test fun failureAfterDestructiveRestartStepsRollsBackEverything() = runBlocking {
        val completed = CourseEntity("completed", "Atomic", null, 1, 2,
            isCompleted = true, completedAt = 2)
        dao.insertCourse(completed)
        val topic = TopicEntity("topic", completed.id, 0, "Keep", isCompleted = true, completionDate = 10)
        dao.insertTopic(topic)
        val schedule = ScheduleEntity(completed.id, 10, generatedThrough = 30)
        val rule = ScheduleRuleEntity(completed.id, 1, 600)
        val session = SessionEntity("old", completed.id, 10, 600, completed.name, null, 1, 1,
            result = SessionResult.DONE)
        dao.insertSchedule(schedule)
        dao.insertScheduleRule(rule)
        dao.insertSession(session)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_restart BEFORE UPDATE ON courses WHEN OLD.isCompleted = 1 AND NEW.isCompleted = 0 BEGIN SELECT RAISE(ABORT, 'injected restart failure'); END")

        try {
            repo.restartCourse(completed.id)
            fail("Expected injected restart failure")
        } catch (_: SQLiteException) { }

        assertEquals(completed, dao.getCourse(completed.id))
        assertEquals(topic, dao.getTopic(topic.id))
        assertEquals(schedule, dao.getSchedule(completed.id))
        assertEquals(listOf(rule), dao.getScheduleRules(completed.id))
        assertEquals(session.copy(courseCompleted = true), dao.getSession(session.id))
    }
}
