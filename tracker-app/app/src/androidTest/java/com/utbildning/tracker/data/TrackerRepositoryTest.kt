package com.utbildning.tracker.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.local.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackerRepositoryTest {
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

    @Test fun creationTrimsInputsAndPersistsInitialTopicsWithoutCalendar() = runBlocking {
        val course = repository.createCourse("  C  ", 0, " Учёба ", listOf(" Массивы ", "Указатели"))
        assertEquals("C", course.name)
        assertEquals(100L, course.createdAt)
        assertEquals(course, dao.getCourse(course.id))
        assertEquals("Учёба", repository.observeCategories().first().single().name)
        assertEquals(listOf("Массивы", "Указатели"), dao.getTopics(course.id).map { it.title })
        assertEquals(listOf(0, 1), dao.getTopics(course.id).map { it.position })
        assertEquals(0, dao.getCourse(course.id)?.colorId)
        assertTrue(dao.getSessions(course.id).isEmpty())
        assertNull(dao.getSchedule(course.id))
        assertTrue(dao.observeTopics(course.id).first().filter { it.isCompleted }.isEmpty())
    }

    @Test fun invalidInputLeavesNoCourseCategoryOrColorReservation() = runBlocking {
        rejects(RepositoryError.EMPTY_NAME) { repository.createCourse(" \n ", 0, "New") }
        for (color in listOf(-1, 10)) {
            rejects(RepositoryError.INVALID_COLOR) { repository.createCourse("C", color, "New") }
        }
        rejects(RepositoryError.INVALID_TOPIC) { repository.createCourse("C", 0, "New", listOf("Valid", "  ")) }
        assertTrue(repository.observeCourses().first().isEmpty())
        assertTrue(repository.observeCategories().first().isEmpty())
        assertEquals((0..9).toList(), repository.availableColors())
    }

    @Test fun categoryMatchingIsExactUnicodeCaseInsensitiveAndReassignmentIsLocal() = runBlocking {
        val first = repository.createCourse("C", 0, " Программирование ")
        val second = repository.createCourse("Практика", 1, "программирование")
        assertEquals(first.categoryId, second.categoryId)
        val changed = repository.updateCourse(first.id, "C", 0, "Программ")
        assertNotEquals(first.categoryId, changed.categoryId)
        assertEquals(second.categoryId, dao.getCourse(second.id)?.categoryId)
        assertEquals(setOf("Программирование", "Программ"), repository.observeCategories().first().map { it.name }.toSet())
        assertNull(repository.updateCourse(first.id, "C", 0, "  ").categoryId)
        assertEquals(2, repository.observeCategories().first().size)
    }

    @Test fun explicitCategoryRenamePreservesLinksAndRejectsConflicts() = runBlocking {
        val first = repository.createCourse("C", 0, "Учёба")
        val second = repository.createCourse("Практика", 1, "Учёба")
        repository.createCourse("Язык", 2, "Языки")
        val categoryId = requireNotNull(first.categoryId)
        val renamed = repository.renameCategory(categoryId, " Программирование ")
        assertEquals("Программирование", renamed.name)
        assertEquals(categoryId, dao.getCourse(second.id)?.categoryId)
        rejects(RepositoryError.CATEGORY_NAME_CONFLICT) { repository.renameCategory(categoryId, " языки ") }
        rejects(RepositoryError.EMPTY_NAME) { repository.renameCategory(categoryId, " ") }
        rejects(RepositoryError.CATEGORY_NOT_FOUND) { repository.renameCategory("missing", "New") }
        assertEquals(renamed, repository.observeCategories().first().single { it.id == categoryId })
    }

    @Test fun tenUnfinishedCoursesIncludePausedAndUnscheduledButNotCompleted() = runBlocking {
        dao.insertCourse(CourseEntity("completed", "Old", null, 1, 2, isCompleted = true, completedAt = 2))
        val courses = (0..9).map { color ->
            repository.createCourse("C $color", color, topics = if (color == 0) listOf("Pointers") else emptyList())
        }
        dao.updateCourse(courses[1].copy(isPaused = true))
        assertEquals(11, repository.observeCourses().first().size)
        rejects(RepositoryError.COURSE_LIMIT) { repository.createCourse("Eleventh", 0, "Should not exist") }
        assertTrue(repository.observeCategories().first().isEmpty())
        assertTrue(repository.availableColors().isEmpty())
        assertEquals(listOf(0), repository.availableColors(courses[0].id))
        assertEquals("Renamed", repository.updateCourse(courses[0].id, "Renamed", 0).name)
        assertEquals(10, repository.observeCourses().first().count { !it.isCompleted })
    }

    @Test fun failedEditPreservesOriginalCourseCategoryAndReservation() = runBlocking {
        val first = repository.createCourse("C", 0, "Original")
        repository.createCourse("Other", 1)
        rejects(RepositoryError.COLOR_UNAVAILABLE) { repository.updateCourse(first.id, "Changed", 1, "New") }
        rejects(RepositoryError.EMPTY_NAME) { repository.updateCourse(first.id, " ", 2, "New") }
        rejects(RepositoryError.COURSE_NOT_FOUND) { repository.updateCourse("missing", "Changed", 2, "New") }
        assertEquals(first, dao.getCourse(first.id))
        assertEquals(0, dao.getCourse(first.id)?.colorId)
        assertEquals(listOf("Original"), repository.observeCategories().first().map { it.name })
        assertEquals(listOf(0) + (2..9).toList(), repository.availableColors(first.id))
    }

    @Test fun editMovesReservationAndPreservesProgressWithCurrentCourseMetadata() = runBlocking {
        val course = repository.createCourse("C", 0, topics = listOf("Pointers"))
        val topic = dao.getTopics(course.id).single()
        val session = SessionEntity("session", course.id, 20, 600, "C", 0, 100, 100, result = SessionResult.DONE)
        dao.insertSession(session)
        val completion = dao.getTopic(topic.id)!!.copy(isCompleted = true, completionDate = dao.getSession(session.id)!!.date)
        dao.updateTopic(completion)
        timestamp = 200
        val edited = repository.updateCourse(course.id, "Advanced C", 3, "Programming")
        assertEquals(100L, edited.createdAt)
        assertEquals(200L, edited.updatedAt)
        assertEquals(3, dao.getCourse(course.id)?.colorId)
        assertTrue(repository.availableColors().contains(0))
        assertFalse(repository.availableColors().contains(3))
        assertEquals(session.copy(courseName = "Advanced C", colorId = 3), dao.getSession(session.id))
        assertEquals(completion, dao.getTopic(topic.id)?.takeIf { it.isCompleted })
        assertEquals(listOf(completion), dao.getTopics(course.id))
    }

    @Test fun completedCourseClearsColorAndRejectsMetadataEdits() = runBlocking {
        val course = repository.createCourse("Old", 0)
        repository.completeCourse(course.id)
        val completed = dao.getCourse(course.id)!!
        assertNull(completed.colorId)
        repository.createCourse("Active", 0)
        assertTrue(repository.availableColors(completed.id).isEmpty())
        rejects(RepositoryError.COURSE_COMPLETED) { repository.updateCourse(completed.id, "Renamed", 1) }
        assertEquals(completed, dao.getCourse(completed.id))
    }

    @Test fun competingCreatesCannotReserveSameColor() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val outcomes = (1..2).map { index -> async(Dispatchers.Default) {
            start.await()
            val writer = if (index == 1) repository else TrackerRepository(database)
            runCatching { writer.createCourse("C $index", 0, "Category $index") }
        } }
        start.complete(Unit)
        val results = outcomes.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(RepositoryError.COLOR_UNAVAILABLE, (results.single { it.isFailure }.exceptionOrNull() as RepositoryException).error)
        val saved = repository.observeCourses().first().single()
        assertEquals(saved.categoryId, repository.observeCategories().first().single().id)
        assertEquals(0, dao.getCourse(saved.id)?.colorId)
    }

    @Test fun concurrentCategoryResolutionCreatesOnlyOneSharedCategory() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val pending = (0..1).map { color -> async(Dispatchers.Default) {
            start.await()
            repository.createCourse("C $color", color, if (color == 0) " Учёба " else "учёба")
        } }
        start.complete(Unit)
        val courses = pending.awaitAll()
        assertEquals(courses[0].categoryId, courses[1].categoryId)
        assertEquals(1, repository.observeCategories().first().size)
    }

    @Test fun lateTopicConstraintFailureRollsBackCategoryCourseAndColor() = runBlocking {
        val original = repository.createCourse("Original", 0, topics = listOf("Existing"))
        val existingTopic = dao.getTopics(original.id).single()
        var generated = 0
        val failing = TrackerRepository(database, newId = {
            generated++
            if (generated <= 2) "new-id-$generated" else existingTopic.id
        })
        try {
            failing.createCourse("New", 1, "New category", listOf("New topic"))
            fail("Expected a late topic constraint failure")
        } catch (_: SQLiteConstraintException) { }
        assertEquals(listOf(original), repository.observeCourses().first())
        assertTrue(repository.observeCategories().first().isEmpty())
        assertEquals(listOf(existingTopic), dao.getTopics(original.id))
        assertEquals((1..9).toList(), repository.availableColors())
        assertNull(dao.getCourse("new-id-1"))
        assertNull(dao.getCourse("new-id-2"))
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
