package com.utbildning.tracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.TopicListConflictException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseFormRepositoryTest {
    private lateinit var db: TrackerDatabase
    private lateinit var repository: TrackerRepository
    private val dao get() = db.trackerDao()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrackerDatabase::class.java).build()
        repository = TrackerRepository(db, now = { 100L })
    }
    @After fun close() = db.close()

    @Test fun formCreatesBothModesAndParsesBlankLines() = runBlocking {
        val course = repository.saveCourseForm(name = " C ", colorId = 0, mode = CourseMode.UNSCHEDULED,
            categoryName = "Учёба", topicText = " Массивы \n\n Указатели ")
        val details = repository.observeCourseDetails(course.id).first()!!
        assertEquals(listOf("Массивы", "Указатели"), details.topics.map { it.title })
        assertEquals("Учёба", details.category?.name)
        assertTrue(details.completions.isEmpty())
        val practice = repository.saveCourseForm(name = "Практика", colorId = 1, mode = CourseMode.SCHEDULED)
        assertTrue(repository.getCourseDetails(practice.id)!!.topics.isEmpty())
    }

    @Test fun conflictingTopicsRollBackMetadataCategoryAndColorTogether() = runBlocking {
        val original = repository.createCourse("C", 0, CourseMode.UNSCHEDULED, "Old", listOf("Массивы", "Указатели"))
        val topics = dao.getTopics(original.id)
        dao.insertCompletion(TopicCompletionEntity(topics.first().id, original.id, CompletionSource.MANUAL, 50))
        try {
            repository.saveCourseForm(original.id, "Changed", 1, CourseMode.UNSCHEDULED, "New", "МАССИВЫ")
            fail("Expected conflict")
        } catch (expected: TopicListConflictException) {
            assertEquals(listOf(1), expected.lineNumbers)
        }
        assertEquals(original, dao.getCourse(original.id))
        assertEquals(0, dao.getReservation(original.id)?.colorId)
        assertEquals(listOf("Old"), dao.getCategories().map { it.name })
        assertEquals(topics, dao.getTopics(original.id))
    }

    @Test fun completedFormIgnoresTopicDraftAndKeepsMode() = runBlocking {
        val course = repository.createCourse("C", 0, CourseMode.UNSCHEDULED, topics = listOf("Массивы"))
        dao.updateCourse(course.copy(isCompleted = true, completedAt = 90))
        dao.deleteColorReservation(course.id)
        repository.saveCourseForm(course.id, "C new", 0, CourseMode.SCHEDULED, topicText = "")
        assertEquals(CourseMode.UNSCHEDULED, dao.getCourse(course.id)?.mode)
        assertEquals(listOf("Массивы"), dao.getTopics(course.id).map { it.title })
    }

    @Test fun deletionRequiresConfirmationForCompletedCoursesAndPreservesData() = runBlocking {
        val course = repository.createCourse("C", 0, CourseMode.UNSCHEDULED, "Учёба", listOf("Массивы"))
        val topic = dao.getTopics(course.id).single()
        dao.insertCompletion(TopicCompletionEntity(topic.id, course.id, CompletionSource.MANUAL, 50))
        dao.updateCourse(course.copy(isCompleted = true, completedAt = 90))
        dao.deleteColorReservation(course.id)
        val before = dao.getCourse(course.id)!!
        try {
            repository.deleteCategory(course.categoryId!!)
            fail("Expected confirmation")
        } catch (expected: RepositoryException) { assertEquals(RepositoryError.CATEGORY_IN_USE, expected.error) }
        repository.deleteCategory(course.categoryId!!, confirmed = true)
        assertEquals(before.copy(categoryId = null), dao.getCourse(course.id))
        assertEquals(topic, dao.getTopic(topic.id))
        assertNotNull(dao.getCompletion(topic.id))
        assertTrue(dao.getCategories().isEmpty())
    }

    @Test fun emptyCategoryDeletesImmediatelyAndNullDraftPreservesTopics() = runBlocking {
        dao.insertCategory(CategoryEntity("unused", "Unused"))
        repository.deleteCategory("unused")
        assertTrue(dao.getCategories().isEmpty())
        val course = repository.createCourse("C", 0, CourseMode.UNSCHEDULED, topics = listOf("Массивы"))
        val topics = dao.getTopics(course.id)
        repository.saveCourseForm(course.id, "New", 0, CourseMode.SCHEDULED)
        assertEquals(topics, dao.getTopics(course.id))
        assertEquals(CourseMode.UNSCHEDULED, dao.getCourse(course.id)?.mode)
    }
}
