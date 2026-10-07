package com.utbildning.tracker.maintenance

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.BackupStateEntity
import com.utbildning.tracker.data.local.CourseEntity
import com.utbildning.tracker.data.local.ScheduleEntity
import com.utbildning.tracker.data.local.ScheduleRuleEntity
import com.utbildning.tracker.data.local.SessionEntity
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.data.local.TopicEntity
import com.utbildning.tracker.data.local.TrackerDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarCleanupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: TrackerDatabase
    private var policy = RetentionPolicy(30)
    private val today = LocalDate.parse("2026-10-07")
    private val now = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
    private lateinit var repository: TrackerRepository
    private val dao get() = database.trackerDao()

    @Before fun setUp() {
        WorkManager.getInstance(context).cancelUniqueWork(CalendarCleanupManager.WORK_NAME)
            .result.get(10, TimeUnit.SECONDS)
        database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        repository = TrackerRepository(
            database,
            now = { now },
            zone = { ZoneId.of("UTC") },
            retentionPolicy = { policy },
        )
    }

    @After fun tearDown() {
        WorkManager.getInstance(context).cancelUniqueWork(CalendarCleanupManager.WORK_NAME)
            .result.get(10, TimeUnit.SECONDS)
        database.close()
    }

    @Test fun cleanupKeepsBoundaryAndNonSessionDataForEveryCourseState() = runBlocking {
        seedAllCourseStates()

        assertEquals(3, repository.cleanupOldSessions())

        listOf("active", "paused", "completed").forEach { courseId ->
            assertEquals(
                listOf("$courseId-boundary", "$courseId-future"),
                dao.getSessions(courseId).map { it.id },
            )
            assertNotNull(dao.getCourse(courseId))
            assertNotNull(dao.getTopic("$courseId-topic"))
            assertNotNull(dao.getSchedule(courseId))
            assertTrue(dao.getScheduleRules(courseId).isNotEmpty())
        }
        assertEquals("backup-id", dao.getCommittedBackupId())
        assertEquals(0, repository.cleanupOldSessions())
    }

    @Test fun nextParallelRunReadsChangedPolicyAndDisabledPolicyDeletesNothing() = runBlocking {
        seedCourse("active", colorId = 0)
        insertSession("very-old", "active", today.minusDays(31))
        insertSession("newly-old", "active", today.minusDays(11))
        insertSession("kept", "active", today.minusDays(10))

        assertEquals(1, repository.cleanupOldSessions())
        policy = RetentionPolicy(10)
        val deleted = listOf(async { repository.cleanupOldSessions() }, async { repository.cleanupOldSessions() })
            .awaitAll()
        assertEquals(1, deleted.sum())
        assertEquals(listOf("kept"), dao.getSessions("active").map { it.id })

        insertSession("disabled-old", "active", today.minusDays(1000))
        policy = RetentionPolicy.Disabled
        assertEquals(0, repository.cleanupOldSessions())
        assertNotNull(dao.getSession("disabled-old"))
    }

    @Test fun databaseFailureRollsBackTheWholeDelete() = runBlocking {
        seedAllCourseStates()
        val before = dao.getAllSessions().map { it.record() }
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_cleanup BEFORE DELETE ON sessions WHEN OLD.id = 'paused-old' " +
                "BEGIN SELECT RAISE(ABORT, 'injected cleanup failure'); END",
        )

        try {
            repository.cleanupOldSessions()
            fail("Expected injected cleanup failure")
        } catch (_: SQLiteException) {
        }

        assertEquals(before, dao.getAllSessions().map { it.record() })
    }

    @Test fun reconcileKeepsOneDailyPeriodicWorker() {
        val manager = CalendarCleanupManager(context)
        manager.reconcile()
        manager.reconcile()

        val active = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(CalendarCleanupManager.WORK_NAME)
            .get(10, TimeUnit.SECONDS)
            .filter { !it.state.isFinished }
        assertEquals(1, active.size)
        assertTrue(active.single().state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING))
        assertTrue(CalendarCleanupWorker::class.java.name in active.single().tags)
    }

    private suspend fun seedAllCourseStates() {
        seedCourse("active", colorId = 0)
        seedCourse("paused", colorId = 1, paused = true)
        seedCourse("completed", colorId = null, completed = true)
        dao.putBackupState(BackupStateEntity(value = "backup-id"))
        listOf("active", "paused", "completed").forEach { courseId ->
            insertSession("$courseId-old", courseId, today.minusDays(31))
            insertSession("$courseId-boundary", courseId, today.minusDays(30))
            insertSession("$courseId-future", courseId, today.plusDays(1))
        }
    }

    private suspend fun seedCourse(
        id: String,
        colorId: Int?,
        paused: Boolean = false,
        completed: Boolean = false,
    ) {
        dao.insertCourse(
            CourseEntity(
                id = id,
                name = id,
                colorId = colorId,
                createdAt = 1,
                updatedAt = 2,
                isCompleted = completed,
                isPaused = paused,
                completedAt = if (completed) 2 else null,
            ),
        )
        dao.insertTopic(
            TopicEntity(
                id = "$id-topic",
                courseId = id,
                position = 0,
                title = "Topic",
                isCompleted = completed,
                completionDate = if (completed) today.minusDays(40).toEpochDay() else null,
            ),
        )
        dao.insertSchedule(ScheduleEntity(id, today.minusDays(60).toEpochDay(), generatedThrough = today.toEpochDay()))
        dao.insertScheduleRule(ScheduleRuleEntity(id, 3, 600))
    }

    private suspend fun insertSession(id: String, courseId: String, date: LocalDate) {
        val course = dao.getCourse(courseId)!!
        dao.insertSession(
            SessionEntity(
                id = id,
                courseId = courseId,
                date = date.toEpochDay(),
                startMinute = 600,
                courseName = course.name,
                colorId = course.colorId,
                createdAt = 1,
                updatedAt = 1,
                result = SessionResult.DONE,
            ),
        )
    }
}
