package com.utbildning.tracker.data

import androidx.room.withTransaction
import com.utbildning.tracker.data.local.CategoryEntity
import com.utbildning.tracker.data.local.CourseEntity
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay

enum class RepositoryError {
    EMPTY_NAME, NAME_TOO_LONG, INVALID_COLOR, TOPICS_REQUIRED, INVALID_TOPIC, COURSE_LIMIT,
    COLOR_UNAVAILABLE, COURSE_NOT_FOUND, CATEGORY_NOT_FOUND, CATEGORY_NAME_CONFLICT,
    COURSE_COMPLETED, CATEGORY_IN_USE, INVALID_SCHEDULE, SCHEDULE_EXISTS, SESSION_NOT_FOUND, INVALID_SESSION_DATE,
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
    suspend fun updateSchedule(courseId: String, rules: List<WeeklyRule>, endsOn: Long? = null) =
        operations.updateSchedule(courseId, rules, endsOn)
    suspend fun getSchedule(courseId: String) = dao.getSchedule(courseId)
    suspend fun getScheduleRules(courseId: String) = dao.getScheduleRules(courseId)
    suspend fun getOccupiedSchedules(excludingCourseId: String) = database.withTransaction {
        dao.getReminderCourses().filter { it.id != excludingCourseId }.mapNotNull { course ->
            val schedule = dao.getSchedule(course.id) ?: return@mapNotNull null
            com.utbildning.tracker.domain.OccupiedSchedule(course.id, course.name,
                LocalDate.ofEpochDay(schedule.startsOn), schedule.endsOn?.let(LocalDate::ofEpochDay),
                dao.getScheduleRules(course.id).map { WeeklyRule(it.dayOfWeek, it.startMinute, it.endMinute) })
        }
    }
    fun observeOccupiedSchedules(excludingCourseId: String) =
        database.invalidationTracker.createFlow("courses", "topics", "schedules", "schedule_rules")
            .map { getOccupiedSchedules(excludingCourseId) }.distinctUntilChanged()
    fun observeSessions() = dao.observeSessions()
    fun observeSessionsBetween(firstDate: Long, lastDate: Long) = dao.observeSessionsBetween(firstDate, lastDate)
    fun observeReminderChanges(): Flow<Unit> =
        database.invalidationTracker.createFlow("sessions", "courses", "topics", "schedules", "schedule_rules")
            .map { Unit }
    fun observeTopicPaces(): Flow<Map<String, Int>> = merge(
        database.invalidationTracker.createFlow("courses", "topics", "schedules", "schedule_rules", "sessions").map { Unit },
        flow { while (true) { emit(Unit); delay(com.utbildning.tracker.domain.millisUntilNextLocalDay(now(), zone())) } },
    ).map {
        database.withTransaction {
            val currentZone = zone()
            val instant = java.time.Instant.ofEpochMilli(now())
            val today = instant.atZone(currentZone).toLocalDate()
            val sessions = dao.getAllSessions().groupBy { it.courseId }
            dao.getReminderCourses().mapNotNull { course ->
                val schedule = dao.getSchedule(course.id) ?: return@mapNotNull null
                val rules = dao.getScheduleRules(course.id).map { WeeklyRule(it.dayOfWeek, it.startMinute, it.endMinute) }
                val remaining = dao.getTopics(course.id).count { !it.isCompleted }
                val records = sessions[course.id].orEmpty()
                val unavailable = records.filter { it.result != SessionResult.PENDING }
                    .map { LocalDate.ofEpochDay(it.date) }.toMutableSet()
                // A pending session remains actionable all day. Do not invent a past start
                // when the calendar never created today's session.
                if (records.none { it.date == today.toEpochDay() && it.result == SessionResult.PENDING } &&
                    rules.none { it.dayOfWeek == today.dayOfWeek.value && SessionTime.start(today, it.startMinute, currentZone) >= instant })
                    unavailable += today
                com.utbildning.tracker.domain.TopicPace.perSession(remaining,
                    LocalDate.ofEpochDay(schedule.startsOn), schedule.endsOn?.let(LocalDate::ofEpochDay),
                    rules, today, unavailable)?.let { course.id to it }
            }.toMap()
        }
    }.distinctUntilChanged()
    /** A read-only snapshot; future result permissions do not restrict alarm planning. */
    suspend fun getReminderCandidates(): List<SessionEntity> = database.withTransaction {
        val yesterday = java.time.Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().minusDays(1)
        dao.getReminderCandidates(yesterday.toEpochDay())
    }

    /** Validation and posting share the write transaction with lifecycle changes. */
    suspend fun withCurrentReminders(trigger: Long, action: (SessionEntity) -> Unit) = database.withTransaction {
        val currentZone = zone()
        getReminderCandidates().forEach {
            val date = LocalDate.ofEpochDay(it.date)
            if (SessionTime.start(date, it.startMinute, currentZone).toEpochMilli() == trigger ||
                    SessionTime.question(date, it.startMinute, it.endMinute, currentZone).toEpochMilli() == trigger) action(it)
        }
    }
    suspend fun synchronize(throughDate: Long? = null) = operations.synchronize(throughDate)
    suspend fun getSessionDetails(sessionId: String) = operations.getSessionDetails(sessionId)
    fun observeSessionDetails(sessionId: String) = merge(
        database.invalidationTracker.createFlow("sessions", "courses", "topics").map { Unit },
        // Date permissions also expire while the editor stays open across midnight.
        flow { while (true) { emit(Unit); delay(com.utbildning.tracker.domain.millisUntilNextLocalDay(now(), zone())) } },
    ).map { getSessionDetails(sessionId) }.distinctUntilChanged()
    suspend fun setSessionResult(sessionId: String, result: SessionResult, selectedTopicIds: Set<String>? = null) =
        operations.setSessionResult(sessionId, result, selectedTopicIds)
    suspend fun applyPendingSessionAction(sessionId: String, result: SessionResult) =
        operations.applyPendingSessionAction(sessionId, result)
    suspend fun markSessionDoneIfNoTopics(sessionId: String): Boolean {
        val details = getSessionDetails(sessionId) ?: throw RepositoryException(RepositoryError.SESSION_NOT_FOUND)
        if (details.hasTopics) return false
        setSessionResult(sessionId, SessionResult.DONE, emptySet())
        return true
    }
    suspend fun completeCourse(courseId: String) = operations.completeCourse(courseId)
    suspend fun pauseCourse(courseId: String) = operations.pauseCourse(courseId)
    suspend fun disableSchedule(courseId: String) = operations.disableSchedule(courseId)
    suspend fun deleteCourse(courseId: String) = operations.deleteCourse(courseId)

    fun observeCourses(): Flow<List<CourseEntity>> = dao.observeCourses()

    fun observeCoursesSnapshot(): Flow<CoursesSnapshot> =
        database.invalidationTracker.createFlow("courses", "topics", "schedule_rules", "sessions")
            .map {
                database.withTransaction {
                    val courses = dao.getCourses()
                    val topics = dao.getCourseTopicCounts().associateBy { it.courseId }
                    val done = dao.getCourseDoneCounts().associateBy { it.courseId }
                    val rules = dao.getAllScheduleRules().groupBy { it.courseId }
                    CoursesSnapshot(courses, courses.associate { course ->
                        val count = topics[course.id]
                        course.id to if (count == null) (done[course.id]?.done ?: 0) to 0
                            else count.completed to count.total
                    }, rules)
                }
            }.distinctUntilChanged()

    fun observeReminderCourses(): Flow<List<CourseEntity>> = dao.observeReminderCourses()

    fun observeCategories(): Flow<List<CategoryEntity>> = dao.observeCategories()

    suspend fun getCourse(courseId: String): CourseEntity? = dao.getCourse(courseId)

    suspend fun getCourseDetails(courseId: String): CourseDetails? = database.withTransaction {
        val course = dao.getCourse(courseId) ?: return@withTransaction null
        CourseDetails(course, dao.getTopics(courseId),
            dao.getCategories().find { it.id == course.categoryId }, dao.getSchedule(courseId) != null)
    }

    fun observeCourseDetails(courseId: String): Flow<CourseDetails?> =
        database.invalidationTracker.createFlow("courses", "categories", "topics", "schedules", "schedule_rules")
            .map { getCourseDetails(courseId) }

    /** Outer transaction includes metadata, category/color allocation and topic reconciliation. */
    suspend fun saveCourseForm(
        courseId: String? = null,
        name: String,
        colorId: Int?,
        categoryName: String = "",
        topicText: String? = null,
    ): CourseEntity = database.withTransaction {
        if (courseId == null) {
            createCourse(name, colorId, categoryName,
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
            if (topic == null || topic.courseId != courseId) {
                fail(RepositoryError.INVALID_TOPIC)
            }
            val completed = !topic.isCompleted
            val timestamp = now()
            dao.updateTopic(topic.copy(isCompleted = completed, completionDate = null))
            dao.updateCourse(course.copy(updatedAt = timestamp))
            operations.reconcileExhaustion(courseId)
            completed
        }

    suspend fun getTopicEditorTopics(courseId: String): List<EditableTopic> =
        database.withTransaction {
            dao.getCourse(courseId) ?: fail(RepositoryError.COURSE_NOT_FOUND)
            topicSnapshot(dao.getTopics(courseId))
        }

    /** Rebuild against current completion state; a stale draft cannot overwrite progress. */
    suspend fun saveTopicList(courseId: String, text: String): List<TopicEntity> =
        database.withTransaction {
            val course = dao.getCourse(courseId) ?: fail(RepositoryError.COURSE_NOT_FOUND)
            if (course.isCompleted) fail(RepositoryError.COURSE_COMPLETED)
            val existing = dao.getTopics(courseId)
            val snapshot = topicSnapshot(existing)
            val plan = TopicListEditor.plan(text, snapshot)
            val byId = existing.associateBy { it.id }
            val timestamp = now()
            var changed = false
            plan.deletedIds.forEach { id ->
                dao.deleteTopic(id)
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
            dao.getTopics(courseId)
        }

    private fun topicSnapshot(
        topics: List<TopicEntity>,
    ): List<EditableTopic> {
        return topics.map {
            EditableTopic(it.id, it.title, it.position, it.isCompleted)
        }
    }

    suspend fun createCourse(
        name: String,
        colorId: Int?,
        categoryName: String = "",
        topics: List<String> = emptyList(),
    ): CourseEntity = database.withTransaction {
        val title = checkedCourseName(name)
        checkColor(colorId)
        val topicTitles = topics.map(TopicListEditor::normalizeTitle)
        if (topicTitles.any { it.isEmpty() }) fail(RepositoryError.INVALID_TOPIC)
        if (dao.countUnfinishedCourses() >= COLOR_COUNT) fail(RepositoryError.COURSE_LIMIT)
        requireAvailableColor(colorId)
        val categoryId = resolveCategory(categoryName)
        val timestamp = now()
        val course = CourseEntity(
            id = newId(), name = title, colorId = colorId,
            createdAt = timestamp, updatedAt = timestamp, categoryId = categoryId,
        )
        dao.insertCourse(course)
        topicTitles.forEachIndexed { position, topicTitle ->
            dao.insertTopic(TopicEntity(newId(), course.id, position, topicTitle))
        }
        course
    }

    suspend fun updateCourse(
        courseId: String,
        name: String,
        colorId: Int?,
        categoryName: String = "",
    ): CourseEntity = database.withTransaction {
        val existing = dao.getCourse(courseId) ?: fail(RepositoryError.COURSE_NOT_FOUND)
        if (existing.isCompleted) fail(RepositoryError.COURSE_COMPLETED)
        val title = checkedCourseName(name)
        checkColor(colorId)
        requireAvailableColor(colorId, courseId)
        val updated = existing.copy(
            name = title, colorId = colorId,
            categoryId = resolveCategory(categoryName), updatedAt = now(),
        )
        dao.updateCourse(updated)
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
                emptyList()
            } else {
                val occupied = dao.getCourses().filter { !it.isCompleted }
                    .filter { it.id != courseId }.map { it.colorId }.toSet()
                (0 until COLOR_COUNT).filterNot { it in occupied }
            }
        }

    private suspend fun resolveCategory(name: String): String? {
        val title = name.trim()
        if (title.isEmpty()) return null
        return dao.getCategories().find { sameCategoryName(it.name, title) }?.id
            ?: CategoryEntity(newId(), title).also { dao.insertCategory(it) }.id
    }

    private suspend fun requireAvailableColor(colorId: Int?, courseId: String? = null) {
        if (dao.getCourses().filter { !it.isCompleted }.any { it.colorId == colorId && it.id != courseId }) {
            fail(RepositoryError.COLOR_UNAVAILABLE)
        }
    }

    private fun checkedCourseName(name: String): String = checkedName(name).also {
        if (it.codePointCount(0, it.length) > 50) fail(RepositoryError.NAME_TOO_LONG)
    }

    private fun checkedName(name: String): String = name.trim().also {
        if (it.isEmpty()) fail(RepositoryError.EMPTY_NAME)
    }

    private fun checkColor(colorId: Int?) {
        if (colorId == null || colorId !in 0 until COLOR_COUNT) fail(RepositoryError.INVALID_COLOR)
    }

    private fun sameCategoryName(first: String, second: String): Boolean =
        first.trim().equals(second.trim(), ignoreCase = true)

    private fun fail(error: RepositoryError): Nothing = throw RepositoryException(error)

    private companion object {
        const val COLOR_COUNT = 10
    }
}
