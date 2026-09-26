package com.utbildning.tracker.data

import androidx.room.withTransaction
import com.utbildning.tracker.data.local.CategoryEntity
import com.utbildning.tracker.data.local.ColorReservationEntity
import com.utbildning.tracker.data.local.CourseEntity
import com.utbildning.tracker.data.local.CourseMode
import com.utbildning.tracker.data.local.CompletionSource
import com.utbildning.tracker.data.local.TopicCompletionEntity
import com.utbildning.tracker.data.local.TopicEntity
import com.utbildning.tracker.data.local.TrackerDatabase
import com.utbildning.tracker.domain.EditableTopic
import com.utbildning.tracker.domain.TopicListEditor
import com.utbildning.tracker.domain.WeeklyRule
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.data.local.SessionEntity
import com.utbildning.tracker.domain.SessionTime
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class RepositoryError {
    EMPTY_NAME, NAME_TOO_LONG, INVALID_COLOR, TOPICS_REQUIRED, INVALID_TOPIC, COURSE_LIMIT,
    COLOR_UNAVAILABLE, COURSE_NOT_FOUND, CATEGORY_NOT_FOUND, CATEGORY_NAME_CONFLICT,
    COURSE_COMPLETED, CATEGORY_IN_USE, INVALID_SCHEDULE, SCHEDULE_EXISTS, SESSION_NOT_FOUND,
}

class RepositoryException(val error: RepositoryError) : IllegalArgumentException(error.name)

/** Course writes, category resolution and color allocation share one transaction. */
class TrackerRepository(
    private val database: TrackerDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val dao = database.trackerDao()
    private val operations = TrackerOperations(database, now, newId, zone)

    suspend fun saveInitialSchedule(courseId: String, rules: List<WeeklyRule>, endsOn: Long? = null) =
        operations.saveInitialSchedule(courseId, rules, endsOn)
    suspend fun getSchedule(courseId: String) = dao.getSchedule(courseId)
    suspend fun getScheduleRules(courseId: String) = dao.getScheduleRules(courseId)
    fun observeSessions() = dao.observeSessions()
    /** Validation and posting share the write transaction with lifecycle changes. */
    suspend fun withCurrentReminders(trigger: Long, action: (SessionEntity) -> Unit) = database.withTransaction {
        val currentZone = zone()
        val courses = dao.getCourses().filter { !it.isCompleted && !it.isPaused && it.exhaustedAt == null }.map { it.id }.toSet()
        dao.getAllSessions().filter { it.courseId in courses && it.result == SessionResult.PENDING }.forEach {
            if (dao.getSchedule(it.courseId) != null &&
                SessionTime.start(LocalDate.ofEpochDay(it.date), it.startMinute, currentZone).toEpochMilli() == trigger) action(it)
        }
    }
    suspend fun synchronize(throughDate: Long? = null) = operations.synchronize(throughDate)
    suspend fun getSessionDetails(sessionId: String) = operations.getSessionDetails(sessionId)
    fun observeSessionDetails(sessionId: String) = database.invalidationTracker
        .createFlow("sessions", "topics", "topic_completions")
        .map { getSessionDetails(sessionId) }
    suspend fun setSessionResult(sessionId: String, result: SessionResult, selectedTopicIds: Set<String>? = null) =
        operations.setSessionResult(sessionId, result, selectedTopicIds)
    suspend fun completeCourse(courseId: String) = operations.completeCourse(courseId)
    suspend fun pauseCourse(courseId: String) = operations.pauseCourse(courseId)
    suspend fun deleteCourse(courseId: String) = operations.deleteCourse(courseId)
    suspend fun dismissCompletionPrompt(courseId: String) = operations.dismissCompletionPrompt(courseId)
    suspend fun shouldOfferCompletion(courseId: String) = operations.shouldOfferCompletion(courseId)

    fun observeCourses(): Flow<List<CourseEntity>> = dao.observeCourses()

    fun observeCategories(): Flow<List<CategoryEntity>> = dao.observeCategories()

    suspend fun getCourse(courseId: String): CourseEntity? = dao.getCourse(courseId)

    suspend fun getCourseDetails(courseId: String): CourseDetails? = database.withTransaction {
        val course = dao.getCourse(courseId) ?: return@withTransaction null
        CourseDetails(course, dao.getTopics(courseId).filter { it.archivedAt == null },
            dao.getCompletions(courseId), dao.getCategories().find { it.id == course.categoryId })
    }

    fun observeCourseDetails(courseId: String): Flow<CourseDetails?> =
        database.invalidationTracker.createFlow("courses", "categories", "topics", "topic_completions")
            .map { getCourseDetails(courseId) }

    /** Outer transaction includes metadata, category/color allocation and topic reconciliation. */
    suspend fun saveCourseForm(
        courseId: String? = null,
        name: String,
        colorId: Int,
        mode: CourseMode,
        categoryName: String = "",
        topicText: String? = null,
    ): CourseEntity = database.withTransaction {
        if (courseId == null) {
            createCourse(name, colorId, mode, categoryName,
                TopicListEditor.parse(topicText.orEmpty()).map { it.title })
        } else {
            val updated = updateCourse(courseId, name, colorId, categoryName)
            if (!updated.isCompleted && topicText != null) saveTopicList(courseId, topicText)
            dao.getCourse(courseId)!!
        }
    }

    suspend fun deleteCategory(categoryId: String, confirmed: Boolean = false) =
        database.withTransaction {
            if (dao.getCategories().none { it.id == categoryId }) fail(RepositoryError.CATEGORY_NOT_FOUND)
            if (!confirmed && dao.countCoursesInCategory(categoryId) > 0) fail(RepositoryError.CATEGORY_IN_USE)
            dao.deleteCategory(categoryId)
        }

    /** Returns the new completion state; no calendar row is created or rewritten. */
    suspend fun toggleTopicCompletion(courseId: String, topicId: String): Boolean =
        database.withTransaction {
            val course = dao.getCourse(courseId) ?: fail(RepositoryError.COURSE_NOT_FOUND)
            if (course.isCompleted) fail(RepositoryError.COURSE_COMPLETED)
            val topic = dao.getTopic(topicId)
            if (topic == null || topic.courseId != courseId || topic.archivedAt != null) {
                fail(RepositoryError.INVALID_TOPIC)
            }
            val completed = dao.getCompletion(topicId) == null
            val timestamp = now()
            if (completed) {
                dao.insertCompletion(TopicCompletionEntity(topicId, courseId, CompletionSource.MANUAL, timestamp))
            } else {
                dao.deleteCompletion(topicId)
            }
            dao.updateCourse(course.copy(updatedAt = timestamp))
            operations.reconcileExhaustion(courseId)
            completed
        }

    suspend fun getTopicEditorTopics(courseId: String): List<EditableTopic> =
        database.withTransaction {
            dao.getCourse(courseId) ?: fail(RepositoryError.COURSE_NOT_FOUND)
            topicSnapshot(courseId, dao.getTopics(courseId))
        }

    /** Rebuild against current completion state; a stale draft cannot overwrite progress. */
    suspend fun saveTopicList(courseId: String, text: String): List<TopicEntity> =
        database.withTransaction {
            val course = dao.getCourse(courseId) ?: fail(RepositoryError.COURSE_NOT_FOUND)
            if (course.isCompleted) fail(RepositoryError.COURSE_COMPLETED)
            val existing = dao.getTopics(courseId)
            val snapshot = topicSnapshot(courseId, existing)
            val plan = TopicListEditor.plan(text, snapshot)
            if (course.mode == CourseMode.UNSCHEDULED && plan.topics.isEmpty() &&
                snapshot.none { !it.isArchived && it.isCompleted }
            ) {
                fail(RepositoryError.TOPICS_REQUIRED)
            }
            val byId = existing.associateBy { it.id }
            val timestamp = now()
            var changed = false
            plan.archivedIds.forEach { id ->
                dao.updateTopic(byId.getValue(id).copy(archivedAt = timestamp))
                changed = true
            }
            plan.topics.forEach { topic ->
                val old = topic.existingId?.let { byId.getValue(it) }
                if (old == null) {
                    dao.insertTopic(TopicEntity(newId(), courseId, topic.position, topic.title))
                    changed = true
                } else {
                    val updated = old.copy(position = topic.position, title = topic.title)
                    if (updated != old) {
                        dao.updateTopic(updated)
                        changed = true
                    }
                }
            }
            if (changed) dao.updateCourse(course.copy(updatedAt = timestamp))
            operations.reconcileExhaustion(courseId)
            dao.getTopics(courseId).filter { it.archivedAt == null }
        }

    private suspend fun topicSnapshot(
        courseId: String,
        topics: List<TopicEntity>,
    ): List<EditableTopic> {
        val completedIds = dao.getCompletions(courseId).map { it.topicId }.toSet()
        return topics.map {
            EditableTopic(it.id, it.title, it.position, it.id in completedIds, it.archivedAt != null)
        }
    }

    suspend fun createCourse(
        name: String,
        colorId: Int,
        mode: CourseMode,
        categoryName: String = "",
        topics: List<String> = emptyList(),
    ): CourseEntity = database.withTransaction {
        val title = checkedCourseName(name)
        checkColor(colorId)
        val topicTitles = topics.map { it.trim() }
        if (topicTitles.any { it.isEmpty() }) fail(RepositoryError.INVALID_TOPIC)
        if (mode == CourseMode.UNSCHEDULED && topicTitles.isEmpty()) {
            fail(RepositoryError.TOPICS_REQUIRED)
        }
        if (dao.countUnfinishedCourses() >= COLOR_COUNT) fail(RepositoryError.COURSE_LIMIT)
        requireAvailableColor(colorId)
        val categoryId = resolveCategory(categoryName)
        val timestamp = now()
        val course = CourseEntity(
            id = newId(), name = title, colorId = colorId, mode = mode,
            createdAt = timestamp, updatedAt = timestamp, categoryId = categoryId,
        )
        dao.insertCourse(course)
        dao.insertColorReservation(ColorReservationEntity(colorId, course.id))
        topicTitles.forEachIndexed { position, topicTitle ->
            dao.insertTopic(TopicEntity(newId(), course.id, position, topicTitle))
        }
        course
    }

    suspend fun updateCourse(
        courseId: String,
        name: String,
        colorId: Int,
        categoryName: String = "",
    ): CourseEntity = database.withTransaction {
        val existing = dao.getCourse(courseId) ?: fail(RepositoryError.COURSE_NOT_FOUND)
        val title = checkedCourseName(name)
        checkColor(colorId)
        if (existing.isCompleted) {
            if (colorId != existing.colorId) fail(RepositoryError.INVALID_COLOR)
        } else {
            requireAvailableColor(colorId, courseId)
        }
        val updated = existing.copy(
            name = title, colorId = colorId,
            categoryId = resolveCategory(categoryName), updatedAt = now(),
        )
        dao.updateCourse(updated)
        operations.refreshFutureSnapshots(updated)
        dao.deleteColorReservation(courseId)
        if (!updated.isCompleted) {
            dao.insertColorReservation(ColorReservationEntity(colorId, courseId))
        }
        updated
    }

    /** Explicit shared-category rename, distinct from editing a course's category field. */
    suspend fun renameCategory(categoryId: String, name: String): CategoryEntity =
        database.withTransaction {
            val title = checkedName(name)
            val categories = dao.getCategories()
            val existing = categories.find { it.id == categoryId }
                ?: fail(RepositoryError.CATEGORY_NOT_FOUND)
            if (categories.any { it.id != categoryId && sameCategoryName(it.name, title) }) {
                fail(RepositoryError.CATEGORY_NAME_CONFLICT)
            }
            existing.copy(name = title).also { dao.updateCategory(it) }
        }

    suspend fun availableColors(courseId: String? = null): List<Int> =
        database.withTransaction {
            val course = courseId?.let {
                dao.getCourse(it) ?: fail(RepositoryError.COURSE_NOT_FOUND)
            }
            if (course?.isCompleted == true) {
                listOf(course.colorId)
            } else {
                val occupied = dao.getColorReservations()
                    .filter { it.courseId != courseId }.map { it.colorId }.toSet()
                (0 until COLOR_COUNT).filterNot { it in occupied }
            }
        }

    private suspend fun resolveCategory(name: String): String? {
        val title = name.trim()
        if (title.isEmpty()) return null
        return dao.getCategories().find { sameCategoryName(it.name, title) }?.id
            ?: CategoryEntity(newId(), title).also { dao.insertCategory(it) }.id
    }

    private suspend fun requireAvailableColor(colorId: Int, courseId: String? = null) {
        if (dao.getColorReservations().any { it.colorId == colorId && it.courseId != courseId }) {
            fail(RepositoryError.COLOR_UNAVAILABLE)
        }
    }

    private fun checkedCourseName(name: String): String = checkedName(name).also {
        if (it.codePointCount(0, it.length) > 50) fail(RepositoryError.NAME_TOO_LONG)
    }

    private fun checkedName(name: String): String = name.trim().also {
        if (it.isEmpty()) fail(RepositoryError.EMPTY_NAME)
    }

    private fun checkColor(colorId: Int) {
        if (colorId !in 0 until COLOR_COUNT) fail(RepositoryError.INVALID_COLOR)
    }

    private fun sameCategoryName(first: String, second: String): Boolean =
        first.trim().equals(second.trim(), ignoreCase = true)

    private fun fail(error: RepositoryError): Nothing = throw RepositoryException(error)

    private companion object {
        const val COLOR_COUNT = 10
    }
}
