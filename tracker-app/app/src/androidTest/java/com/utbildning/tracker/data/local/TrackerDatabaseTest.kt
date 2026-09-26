package com.utbildning.tracker.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackerDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: TrackerDatabase
    private val dao get() = database.trackerDao()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
    }

    @After fun tearDown() { database.close() }

    @Test fun constructorsRejectInvalidRangesAndCompletionOwnership() {
        assertThrows(IllegalArgumentException::class.java) { course("a", color = -1) }
        assertThrows(IllegalArgumentException::class.java) { course("a", color = 10) }
        assertThrows(IllegalArgumentException::class.java) { course("a").copy(isCompleted = true) }
        assertThrows(IllegalArgumentException::class.java) { course("a").copy(completedAt = 200L) }
        for (day in listOf(0, 8)) {
            assertThrows(IllegalArgumentException::class.java) { ScheduleRuleEntity("a", day, 600) }
        }
        for (minute in listOf(-1, 1440)) {
            assertThrows(IllegalArgumentException::class.java) { session("s", "a").copy(startMinute = minute) }
            assertThrows(IllegalArgumentException::class.java) { session("s", "a").copy(endMinute = minute) }
        }
        assertThrows(IllegalArgumentException::class.java) { session("s", "a").copy(endDayOffset = 2) }
        assertThrows(IllegalArgumentException::class.java) { session("s", "a").copy(endDayOffset = 1) }
        assertThrows(IllegalArgumentException::class.java) { completion("t", "a").copy(source = CompletionSource.SESSION) }
        assertThrows(IllegalArgumentException::class.java) { completion("t", "a", "s").copy(source = CompletionSource.MANUAL) }
        assertThrows(IllegalArgumentException::class.java) { completion("t", "a", "s").copy(source = CompletionSource.COURSE_COMPLETION) }
        assertEquals(0, course("a", color = 0).colorId)
        assertEquals(9, course("b", color = 9).colorId)
        assertEquals(1439, ScheduleRuleEntity("a", 7, 1439, 0, 1).startMinute)
    }

    @Test fun manualCompletionDoesNotCreateCalendarEntries() = runBlocking {
        dao.insertCourse(course("a", mode = CourseMode.UNSCHEDULED))
        dao.insertTopic(topic("t", "a"))
        dao.insertCompletion(completion("t", "a"))
        assertEquals(CompletionSource.MANUAL, dao.getCompletion("t")?.source)
        assertTrue(dao.getSessions("a").isEmpty())
        assertNull(dao.getSchedule("a"))
    }

    @Test fun missingParentsAndCrossCourseLinksAreRejected() = runBlocking {
        rejects { dao.insertTopic(topic("orphan", "missing")) }
        rejects { dao.insertSession(session("orphan", "missing")) }
        dao.insertCourse(course("a"))
        dao.insertCourse(course("b", color = 1))
        dao.insertTopic(topic("ta", "a"))
        dao.insertTopic(topic("tb", "b"))
        dao.insertSession(session("sa", "a"))
        dao.insertSession(session("sb", "b"))
        rejects { dao.insertHistory(history("sa", "tb", "a")) }
        rejects { dao.insertHistory(history("sb", "ta", "a")) }
        rejects { dao.insertHistory(history("missing", "ta", "a")) }
        rejects { dao.insertHistory(history("sa", "missing", "a")) }
        rejects { dao.insertCompletion(completion("ta", "b")) }
        rejects { dao.insertCompletion(completion("missing", "a")) }
        rejects { dao.insertCompletion(completion("ta", "a", "sa")) }
        dao.insertHistory(history("sa", "ta", "a"))
        dao.insertCompletion(completion("ta", "a", "sa"))
        assertEquals("sa", dao.getCompletion("ta")?.sessionId)
    }

    @Test fun duplicateKeysAbortWithoutReplacingExistingData() = runBlocking {
        seedHistory()
        dao.insertCompletion(completion("t", "a", "s"))
        dao.insertColorReservation(ColorReservationEntity(colorId = 0, courseId = "a"))
        dao.insertCourse(course("b", color = 1))
        rejects { dao.insertCourse(course("a", name = "replacement")) }
        rejects { dao.insertSession(session("other-id", "a")) }
        rejects { dao.insertHistory(history("s", "t", "a").copy(topicTitleSnapshot = "replacement")) }
        rejects { dao.insertCompletion(completion("t", "a")) }
        rejects { dao.insertColorReservation(ColorReservationEntity(colorId = 0, courseId = "b")) }
        rejects { dao.insertColorReservation(ColorReservationEntity(colorId = 1, courseId = "a")) }
        assertEquals("C", dao.getCourse("a")?.name)
        assertEquals(listOf("s"), dao.getSessions("a").map { it.id })
        assertEquals("Pointers", dao.getHistory("s").single().topicTitleSnapshot)
        assertEquals(CompletionSource.SESSION, dao.getCompletion("t")?.source)
        assertEquals(0, dao.getReservation("a")?.colorId)
        assertNull(dao.getReservation("b"))
    }

    @Test fun deletingCategoryAndSchedulePreservesCourseAndHistory() = runBlocking {
        dao.insertCategory(CategoryEntity(id = "cat", name = "Programming"))
        seedHistory(categoryId = "cat")
        seedSchedule("a")
        dao.deleteCategory("cat")
        assertNull(dao.getCourse("a")?.categoryId)
        assertNotNull(dao.getCourse("a"))
        dao.deleteSchedule("a")
        assertNull(dao.getSchedule("a"))
        assertTrue(dao.getScheduleRules("a").isEmpty())
        assertEquals(SessionResult.DONE, dao.getSession("s")?.result)
        assertEquals(1, dao.getHistory("s").size)
    }

    @Test fun deletingCourseCascadesOnlyItsOwnData() = runBlocking {
        seedHistory()
        seedSchedule("a")
        dao.insertCompletion(completion("t", "a", "s"))
        dao.insertColorReservation(ColorReservationEntity(colorId = 0, courseId = "a"))
        dao.insertCourse(course("b", color = 1))
        dao.insertTopic(topic("tb", "b"))
        dao.insertCompletion(completion("tb", "b"))
        dao.deleteCourse("a")
        assertNull(dao.getCourse("a"))
        assertTrue(dao.getTopics("a").isEmpty())
        assertTrue(dao.getSessions("a").isEmpty())
        assertTrue(dao.getHistory("s").isEmpty())
        assertTrue(dao.getScheduleRules("a").isEmpty())
        assertNull(dao.getSchedule("a"))
        assertNull(dao.getCompletion("t"))
        assertNull(dao.getReservation("a"))
        assertNotNull(dao.getCourse("b"))
        assertEquals("tb", dao.getTopics("b").single().id)
        assertEquals(CompletionSource.MANUAL, dao.getCompletion("tb")?.source)
    }

    @Test fun removingCompletionAndEditingTopicPreservesHistoricalFact() = runBlocking {
        seedHistory()
        dao.insertCompletion(completion("t", "a", "s"))
        dao.deleteCompletion("t")
        assertNull(dao.getCompletion("t"))
        assertEquals(SessionResult.DONE, dao.getSession("s")?.result)
        dao.updateTopic(topic("t", "a").copy(title = "Renamed", archivedAt = 300L))
        assertEquals("Pointers", dao.getHistory("s").single().topicTitleSnapshot)
        assertEquals(300L, dao.getTopics("a").single().archivedAt)
        dao.insertCompletion(completion("t", "a"))
        assertEquals(CompletionSource.MANUAL, dao.getCompletion("t")?.source)
        assertNull(dao.getCompletion("t")?.sessionId)
        assertEquals(1, dao.getHistory("s").size)
    }

    @Test fun failedTransactionRollsBackEarlierWrites() = runBlocking {
        dao.insertCourse(course("existing"))
        rejects {
            database.withTransaction {
                dao.insertCourse(course("new", color = 1))
                dao.insertTopic(topic("new-topic", "new"))
                dao.insertCourse(course("existing"))
            }
        }
        assertNotNull(dao.getCourse("existing"))
        assertNull(dao.getCourse("new"))
        assertNull(dao.getTopic("new-topic"))
    }

    @Test fun fileDatabasePersistsCurrentStateAndIndependentHistoryAfterReopen() = runBlocking {
        val name = "room-persistence-${System.nanoTime()}.db"
        database.close()
        try {
            database = TrackerDatabase.open(context, name)
            seedHistory()
            seedSchedule("a")
            dao.insertSession(session("pending", "a").copy(date = 20_001L, result = SessionResult.PENDING))
            dao.insertSession(session("skipped", "a").copy(date = 20_002L, result = SessionResult.SKIPPED))
            dao.insertCompletion(completion("t", "a", "s"))
            dao.deleteCompletion("t")
            dao.insertCompletion(completion("t", "a"))
            database.close()
            database = TrackerDatabase.open(context, name)
            assertEquals("C", dao.getCourse("a")?.name)
            assertEquals(CompletionSource.MANUAL, dao.getCompletion("t")?.source)
            assertNull(dao.getCompletion("t")?.sessionId)
            assertEquals("s", dao.getHistory("s").single().sessionId)
            assertEquals(SessionResult.DONE, dao.getSession("s")?.result)
            assertEquals(SessionResult.PENDING, dao.getSession("pending")?.result)
            assertEquals(SessionResult.SKIPPED, dao.getSession("skipped")?.result)
            assertEquals(1, dao.getScheduleRules("a").size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun seedHistory(categoryId: String? = null) {
        dao.insertCourse(course("a").copy(categoryId = categoryId))
        dao.insertTopic(topic("t", "a"))
        dao.insertSession(session("s", "a"))
        dao.insertHistory(history("s", "t", "a"))
    }

    private suspend fun seedSchedule(courseId: String) {
        dao.insertSchedule(ScheduleEntity(courseId = courseId, startsOn = 20_000L, endsOn = null, generatedThrough = null))
        dao.insertScheduleRule(ScheduleRuleEntity(courseId = courseId, dayOfWeek = 1, startMinute = 600, endMinute = null, endDayOffset = 0))
    }

    private suspend fun rejects(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected SQLiteConstraintException")
        } catch (_: SQLiteConstraintException) {
            // The real SQLite constraint, rather than constructor validation, must reject this.
        }
    }

    private fun course(id: String, color: Int = 0, name: String = "C", mode: CourseMode = CourseMode.SCHEDULED) =
        CourseEntity(id = id, name = name, colorId = color, categoryId = null, mode = mode,
            isCompleted = false, isPaused = false, createdAt = 100L, updatedAt = 100L, completedAt = null)

    private fun topic(id: String, courseId: String) =
        TopicEntity(id = id, courseId = courseId, position = 0, title = "Pointers", archivedAt = null)

    private fun session(id: String, courseId: String) =
        SessionEntity(id = id, courseId = courseId, date = 20_000L, startMinute = 600,
            endMinute = null, endDayOffset = 0, result = SessionResult.DONE,
            courseNameSnapshot = "C", colorIdSnapshot = 0, createdAt = 100L, updatedAt = 100L)

    private fun history(sessionId: String, topicId: String, courseId: String) =
        SessionTopicHistoryEntity(sessionId = sessionId, topicId = topicId, courseId = courseId,
            topicTitleSnapshot = "Pointers", recordedAt = 200L)

    private fun completion(topicId: String, courseId: String, sessionId: String? = null) =
        TopicCompletionEntity(topicId = topicId, courseId = courseId,
            source = if (sessionId == null) CompletionSource.MANUAL else CompletionSource.SESSION,
            sessionId = sessionId, completedAt = 200L)
}
