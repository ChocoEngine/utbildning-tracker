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
        val course = repository.saveCourseForm(name = " C ", colorId = 0,
            categoryName = "Учёба", topicText = " Массивы \n\n Указатели ")
        val details = repository.observeCourseDetails(course.id).first()!!
        assertEquals(listOf("Массивы", "Указатели"), details.topics.map { it.title })
        assertEquals("Учёба", details.category?.name)
        assertTrue(details.topics.filter { it.isCompleted }.isEmpty())
        val practice = repository.saveCourseForm(name = "Практика", colorId = 1)
        assertTrue(repository.getCourseDetails(practice.id)!!.topics.isEmpty())
    }

    @Test fun conflictingTopicsRollBackMetadataCategoryAndColorTogether() = runBlocking {
        val original = repository.createCourse("C", 0, "Old", listOf("Массивы", "Указатели"))
        val topics = dao.getTopics(original.id)
        dao.updateTopic(dao.getTopic(topics.first().id)!!.copy(isCompleted = true, completionDate = null))
        try {
            repository.saveCourseForm(original.id, "Changed", 1, "New", "МАССИВЫ")
            fail("Expected conflict")
        } catch (expected: TopicListConflictException) {
            assertEquals(listOf(1), expected.lineNumbers)
        }
        assertEquals(original, dao.getCourse(original.id))
        assertEquals(0, dao.getCourse(original.id)?.colorId)
        assertEquals(listOf("Old"), dao.getCategories().map { it.name })
        assertEquals(topics.mapIndexed { i, topic -> topic.copy(isCompleted = i == 0) }, dao.getTopics(original.id))
    }

    @Test fun completedFormRejectsAllChanges() = runBlocking {
        val course = repository.createCourse("C", 0, topics = listOf("Массивы"))
        repository.completeCourse(course.id)
        val before = repository.getCourseDetails(course.id)
        try { repository.saveCourseForm(course.id, "New", 1, topicText = ""); fail("Completed course is read only") }
        catch (e: RepositoryException) { assertEquals(RepositoryError.COURSE_COMPLETED, e.error) }
        assertEquals(before, repository.getCourseDetails(course.id))
    }

    @Test fun deletionRequiresConfirmationForCompletedCoursesAndPreservesData() = runBlocking {
        val course = repository.createCourse("C", 0, "Учёба", listOf("Массивы"))
        val topic = dao.getTopics(course.id).single()
        dao.updateTopic(dao.getTopic(topic.id)!!.copy(isCompleted = true, completionDate = null))
        dao.updateCourse(course.copy(colorId = null, isCompleted = true, completedAt = 90))
        val before = dao.getCourse(course.id)!!
        try {
            repository.deleteCategory(course.categoryId!!)
            fail("Expected confirmation")
        } catch (expected: RepositoryException) { assertEquals(RepositoryError.CATEGORY_IN_USE, expected.error) }
        repository.deleteCategory(course.categoryId!!, confirmed = true)
        assertEquals(before.copy(categoryId = null), dao.getCourse(course.id))
        assertEquals(topic.copy(isCompleted = true), dao.getTopic(topic.id))
        assertNotNull(dao.getTopic(topic.id)?.takeIf { it.isCompleted })
        assertTrue(dao.getCategories().isEmpty())
    }

    @Test fun emptyCategoryDeletesImmediatelyAndNullDraftPreservesTopics() = runBlocking {
        dao.insertCategory(CategoryEntity("unused", "Unused"))
        repository.deleteCategory("unused")
        assertTrue(dao.getCategories().isEmpty())
        val course = repository.createCourse("C", 0, topics = listOf("Массивы"))
        val topics = dao.getTopics(course.id)
        repository.saveCourseForm(course.id, "New", 0)
        assertEquals(topics, dao.getTopics(course.id))
        assertNotNull(dao.getCourse(course.id))
    }
}
