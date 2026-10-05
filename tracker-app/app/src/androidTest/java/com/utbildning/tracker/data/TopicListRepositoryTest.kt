package com.utbildning.tracker.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.TopicListConflictException
import com.utbildning.tracker.domain.TopicListEditor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TopicListRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: TrackerDatabase
    private lateinit var repository: TrackerRepository
    private val dao get() = database.trackerDao()
    private var timestamp = 100L

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        repository = TrackerRepository(database, now = { timestamp })
    }
    @After fun tearDown() { database.close() }

    @Test fun reorderDuplicatesAndNewTopicsPersistWithStableIds() = runBlocking {
        val course = repository.createCourse("C", 0, topics = listOf("Same", "Other", "Same"))
        val old = dao.getTopics(course.id)
        timestamp = 200
        val saved = repository.saveTopicList(course.id, " Other\r\n\r\nSame\r\nSame\r\nNew ")
        assertEquals(listOf(old[1].id, old[0].id, old[2].id), saved.take(3).map { it.id })
        assertEquals(listOf("Other", "Same", "Same", "New"), saved.map { it.title })
        assertEquals(listOf(0, 1, 2, 3), saved.map { it.position })
        assertEquals(saved, dao.getTopics(course.id))
        assertEquals(200L, dao.getCourse(course.id)?.updatedAt)
        assertTrue(dao.getSessions(course.id).isEmpty())
        assertTrue(dao.observeTopics(course.id).first().filter { it.isCompleted }.isEmpty())
    }

    @Test fun publicCreateAndSaveLimitEveryTitleByCodePointAndRemainIdempotent() = runBlocking {
        val ninetyNine = "Я".repeat(99)
        val hundredEmoji = "😀".repeat(100)
        val mixedLimit = "C".repeat(98) + "🧠Ж"
        val veryLong = "🧠".repeat(5_000)
        val course = repository.createCourse("C", 0, topics = listOf(
            "  $ninetyNine  ", hundredEmoji, hundredEmoji + "tail", "  ${mixedLimit}tail  ", veryLong,
        ))
        val created = dao.getTopics(course.id)

        assertEquals(listOf(99, 100, 100, 100, 100),
            created.map { it.title.codePointCount(0, it.title.length) })
        assertEquals(hundredEmoji, created[1].title)
        assertEquals(hundredEmoji, created[2].title)
        assertNotEquals(created[1].id, created[2].id)
        assertEquals(mixedLimit, created[3].title)
        assertFalse(created.any { it.title.firstOrNull()?.isLowSurrogate() == true })
        assertFalse(created.any { it.title.lastOrNull()?.isHighSurrogate() == true })

        val firstId = created.first().id
        dao.updateTopic(created.first().copy(isCompleted = true, completionDate = 42))
        timestamp = 200
        val editable = "${hundredEmoji}extra\n\n${hundredEmoji}again\n  ${mixedLimit}more  \n$veryLong"
        val saved = repository.saveTopicList(course.id, editable)
        val editableSaved = saved.filterNot { it.isCompleted }
        assertEquals(listOf(hundredEmoji, hundredEmoji, mixedLimit, "🧠".repeat(100)), editableSaved.map { it.title })
        assertEquals(4, editableSaved.map { it.id }.distinct().size)
        assertEquals(dao.getTopic(firstId)?.copy(), created.first().copy(isCompleted = true, completionDate = 42))

        val beforeRepeat = dao.getTopics(course.id)
        timestamp = 300
        assertEquals(beforeRepeat, repository.saveTopicList(course.id, editable))
        assertEquals(100L, dao.getCourse(course.id)?.updatedAt)
    }

    @Test fun conflictAfterLimitKeepsCompletedTopicAndAllOtherRowsUntouched() = runBlocking {
        val completedTitle = "A".repeat(99) + "😀"
        val course = repository.createCourse("C", 0, topics = listOf(completedTitle, "Keep"))
        val original = dao.getTopics(course.id)
        val completed = original.first().copy(isCompleted = true, completionDate = 73)
        dao.updateTopic(completed)

        try {
            repository.saveTopicList(course.id, "\nKeep\n\n${completedTitle}suffix")
            fail("Expected conflict after truncation")
        } catch (exception: TopicListConflictException) {
            assertEquals(listOf(4), exception.lineNumbers)
        }
        assertEquals(listOf(completed, original[1]), dao.getTopics(course.id))
        assertEquals(course, dao.getCourse(course.id))
    }

    @Test fun completedTopicsStayUntouchedAndRemovingHistoricalTopicArchivesIt() = runBlocking {
        val course = repository.createCourse("C", 0, topics = listOf("Remove", "Done", "Keep"))
        val old = dao.getTopics(course.id)
        val session = SessionEntity("session", course.id, 20, 600, "C", 0, 100, 100, result = SessionResult.DONE)
        dao.insertSession(session)
        val completion = dao.getTopic(old[1].id)!!.copy(isCompleted = true, completionDate = dao.getSession(session.id)!!.date)
        dao.updateTopic(completion)
        timestamp = 200
        val saved = repository.saveTopicList(course.id, "Keep\nNew")
        assertEquals(completion, dao.getTopic(old[1].id))
        assertEquals(completion, dao.getTopic(old[1].id)?.takeIf { it.isCompleted })
        assertEquals(session, dao.getSession(session.id))
        assertNull(dao.getTopic(old[0].id))
        assertEquals(listOf("Keep", "Done", "New"), saved.map { it.title })
        assertEquals(old[2].id, saved.first().id)
        val editor = repository.getTopicEditorTopics(course.id)
        assertEquals("Keep\nNew", TopicListEditor.editableText(editor))
        val recreated = repository.saveTopicList(course.id, "Keep\nNew\nRemove").single { it.title == "Remove" }
        assertNotEquals(old[0].id, recreated.id)
    }

    @Test fun emptyListIsAllowedAndCompletedTopicsRemain() = runBlocking {
        val course = repository.createCourse("C", 0, topics = listOf("Done", "Remove"))
        val original = dao.getTopics(course.id)
        dao.updateTopic(original[0].copy(isCompleted = true))
        assertEquals(listOf(original[0].copy(isCompleted = true)), repository.saveTopicList(course.id, " "))
        assertNull(dao.getTopic(original[1].id))
        val empty = repository.createCourse("Empty", 1)
        assertTrue(repository.saveTopicList(empty.id, "").isEmpty())
    }

    @Test fun scheduledListMayBeEmptiedWithoutCreatingCalendarRecords() = runBlocking {
        val course = repository.createCourse("C", 0, topics = listOf("Remove"))
        val original = dao.getTopics(course.id).single()
        assertTrue(repository.saveTopicList(course.id, "").isEmpty())
        assertNull(dao.getTopic(original.id))
        assertTrue(dao.getSessions(course.id).isEmpty())
        assertNull(dao.getSchedule(course.id))
    }

    @Test fun saveRechecksCompletionAfterDraftWasPreparedAndReportsSourceLines() = runBlocking {
        val course = repository.createCourse("C", 0, topics = listOf("Массивы", "Keep"))
        val original = dao.getTopics(course.id)
        val draft = "\n МАССИВЫ \n\nKeep\nмассивы"
        TopicListEditor.plan(draft, repository.getTopicEditorTopics(course.id))
        val completion = dao.getTopic(original[0].id)!!.copy(isCompleted = true, completionDate = null)
        dao.updateTopic(completion)
        try {
            repository.saveTopicList(course.id, draft)
            fail("Expected a conflict with newly completed topic")
        } catch (exception: TopicListConflictException) {
            assertEquals(listOf(2, 5), exception.lineNumbers)
        }
        assertEquals(original.map { if (it.id == completion.id) completion else it }, dao.getTopics(course.id))
        assertEquals(course, dao.getCourse(course.id))
        assertEquals(completion, dao.getTopic(original[0].id)?.takeIf { it.isCompleted })
    }

    @Test fun cancellingDraftWritesNothingIncludingCourseTimestamp() = runBlocking {
        val course = repository.createCourse("C", 0, topics = listOf("Original"))
        val original = dao.getTopics(course.id)
        timestamp = 200
        TopicListEditor.plan("Replacement", repository.getTopicEditorTopics(course.id))
        assertEquals(original, dao.getTopics(course.id))
        assertEquals(course, dao.getCourse(course.id))
    }

    @Test fun unchangedTextSaveDoesNotChangeTimestampOrIds() = runBlocking {
        val course = repository.createCourse("C", 0, topics = listOf("First", "Second"))
        val original = dao.getTopics(course.id)
        timestamp = 200
        assertEquals(original, repository.saveTopicList(course.id, " First \r\n\r\nSecond\n"))
        assertEquals(course, dao.getCourse(course.id))
    }

    @Test fun explicitlySavingLegacyLongEditableTopicTruncatesTitleButKeepsId() = runBlocking {
        val course = repository.createCourse("C", 0)
        val longTitle = "🧠".repeat(120)
        val legacy = TopicEntity("legacy", course.id, 0, longTitle)
        dao.insertTopic(legacy)
        timestamp = 200

        val saved = repository.saveTopicList(course.id, longTitle).single()
        assertEquals(legacy.id, saved.id)
        assertEquals(legacy.position, saved.position)
        assertEquals("🧠".repeat(100), saved.title)
        assertEquals(200L, dao.getCourse(course.id)?.updatedAt)
    }

    @Test fun savedListAndArchivedHistorySurviveDatabaseReopen() = runBlocking {
        val name = "topic-list-${java.util.UUID.randomUUID()}.db"
        var stored = TrackerDatabase.open(context, name)
        try {
            val writer = TrackerRepository(stored, now = { 100 })
            val course = writer.createCourse("C", 0, topics = listOf("Old", "Keep"))
            val original = stored.trackerDao().getTopics(course.id)
            val session = SessionEntity("session", course.id, 20, 600, "C", 0, 100, 100, result = SessionResult.DONE)
            stored.trackerDao().insertSession(session)
            val saved = writer.saveTopicList(course.id, "Keep\nNew")
            stored.close()
            stored = TrackerDatabase.open(context, name)
            val reopenedDao = stored.trackerDao()
            assertEquals(saved, reopenedDao.getTopics(course.id))
            assertNull(reopenedDao.getTopic(original[0].id))
            assertEquals(session, reopenedDao.getSession(session.id))
        } finally {
            stored.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun lateInsertFailureRollsBackDeletedRowsReorderingAndTimestamp() = runBlocking {
        val course = repository.createCourse("C", 0, topics = listOf("Remove", "Keep"))
        val original = dao.getTopics(course.id)
        val failing = TrackerRepository(database, now = { 200 }, newId = { original[1].id })
        try {
            failing.saveTopicList(course.id, "Keep\nNew")
            fail("Expected topic primary key constraint failure")
        } catch (_: SQLiteConstraintException) { }
        assertEquals(original, dao.getTopics(course.id))
        assertEquals(course, dao.getCourse(course.id))
        assertEquals(0, dao.getCourse(course.id)?.colorId)
    }

    @Test fun missingAndCompletedCoursesRejectEdits() = runBlocking {
        rejects(RepositoryError.COURSE_NOT_FOUND) { repository.saveTopicList("missing", "New") }
        val course = repository.createCourse("C", 0, topics = listOf("Original"))
        val completed = course.copy(colorId = null, isCompleted = true, completedAt = 150)
        dao.updateCourse(completed)
        val original = dao.getTopics(course.id)
        rejects(RepositoryError.COURSE_COMPLETED) { repository.saveTopicList(course.id, "New") }
        assertEquals(original, dao.getTopics(course.id))
        assertEquals(completed, dao.getCourse(course.id))
    }

    private suspend fun rejects(error: RepositoryError, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $error")
        } catch (exception: RepositoryException) {
            assertEquals(error, exception.error)
        }
    }
}
