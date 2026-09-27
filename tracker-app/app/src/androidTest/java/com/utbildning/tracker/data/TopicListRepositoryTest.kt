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
