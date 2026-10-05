package com.utbildning.tracker.data

import androidx.room.withTransaction
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.ScheduleGenerator
import com.utbildning.tracker.domain.SessionEditPolicy
import com.utbildning.tracker.domain.SessionTime
import com.utbildning.tracker.domain.WeeklyRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SessionDetails(
    val session: SessionEntity,
    val selectableTopics: List<TopicEntity>,
    val selectedTopicIds: Set<String>,
    val canEdit: Boolean = true,
    val hasTopics: Boolean = selectableTopics.isNotEmpty(),
)

enum class PendingSessionActionResult { APPLIED, NEEDS_TOPICS, IGNORED }

/** Calendar and lifecycle transactions; called through TrackerRepository. */
internal class TrackerOperations(
    private val database: TrackerDatabase,
    private val now: () -> Long,
    private val newId: () -> String,
    private val zone: () -> ZoneId,
) {
    private val dao = database.trackerDao()
    private fun today(timestamp: Long) = Instant.ofEpochMilli(timestamp).atZone(zone()).toLocalDate()
    private fun start(session: SessionEntity) = SessionTime.start(LocalDate.ofEpochDay(session.date), session.startMinute, zone()).toEpochMilli()
    private fun fail(error: RepositoryError): Nothing = throw RepositoryException(error)
    private suspend fun course(id: String) = dao.getCourse(id) ?: fail(RepositoryError.COURSE_NOT_FOUND)

    suspend fun saveInitialSchedule(courseId: String, rules: List<WeeklyRule>, endsOn: Long?) = database.withTransaction {
        val course = course(courseId)
        if (course.isCompleted) fail(RepositoryError.COURSE_COMPLETED)
        if ((rules.isEmpty() && endsOn == null) || rules.map { it.dayOfWeek }.distinct().size != rules.size) {
            fail(RepositoryError.INVALID_SCHEDULE)
        }
        if (dao.getSchedule(courseId) != null) fail(RepositoryError.SCHEDULE_EXISTS)
        val timestamp = now()
        val date = today(timestamp).toEpochDay()
        if (endsOn != null && endsOn < date) fail(RepositoryError.INVALID_SCHEDULE)
        dao.insertSchedule(ScheduleEntity(courseId, date, endsOn))
        rules.forEach { dao.insertScheduleRule(ScheduleRuleEntity(courseId, it.dayOfWeek, it.startMinute, it.endMinute)) }
        dao.updateCourse(course.copy(isPaused = false, updatedAt = timestamp))
        reconcileExhaustion(courseId)
        generate(courseId, date + 90, timestamp)
    }

    /** Editing retains history and results; only future unmarked occurrences are regenerated. */
    suspend fun updateSchedule(courseId: String, rules: List<WeeklyRule>, endsOn: Long?) = database.withTransaction {
        val course = course(courseId)
        if (course.isCompleted) fail(RepositoryError.COURSE_COMPLETED)
        if (dao.getSchedule(courseId) == null) fail(RepositoryError.INVALID_SCHEDULE)
        val timestamp = now()
        val date = today(timestamp).toEpochDay()
        if ((rules.isEmpty() && endsOn == null) || rules.map { it.dayOfWeek }.distinct().size != rules.size ||
            (endsOn != null && endsOn < date)) fail(RepositoryError.INVALID_SCHEDULE)
        removeFuturePending(courseId, timestamp)
        dao.deleteSchedule(courseId)
        dao.insertSchedule(ScheduleEntity(courseId, date, endsOn))
        rules.forEach { dao.insertScheduleRule(ScheduleRuleEntity(courseId, it.dayOfWeek, it.startMinute, it.endMinute)) }
        dao.updateCourse(course.copy(updatedAt = timestamp))
        reconcileExhaustion(courseId)
        generate(courseId, date + 90, timestamp)
    }

    suspend fun synchronize(throughDate: Long? = null) = database.withTransaction {
        val timestamp = now()
        val date = today(timestamp).toEpochDay()
        val through = maxOf(date + 90, throughDate ?: date)
        dao.getCourses().forEach {
            reconcileExhaustion(it.id)
            generate(it.id, through, timestamp)
        }
        dao.getAllSessions().filter { it.date < date && it.result == SessionResult.PENDING }.forEach {
            dao.updateSession(it.copy(result = SessionResult.SKIPPED, updatedAt = timestamp))
        }
    }

    private suspend fun generate(courseId: String, through: Long, timestamp: Long) {
        val course = course(courseId)
        if (course.isCompleted || course.isPaused || dao.hasExhaustedTopics(courseId)) return
        val schedule = dao.getSchedule(courseId) ?: return
        val first = maxOf(today(timestamp).toEpochDay(), schedule.startsOn, schedule.generatedThrough?.plus(1) ?: schedule.startsOn)
        if (first > through) return
        val rules = dao.getScheduleRules(courseId).map { WeeklyRule(it.dayOfWeek, it.startMinute, it.endMinute) }
        val existing = dao.getSessions(courseId).map { it.date }.toHashSet()
        ScheduleGenerator.generate(LocalDate.ofEpochDay(schedule.startsOn), schedule.endsOn?.let(LocalDate::ofEpochDay),
            rules, LocalDate.ofEpochDay(first), LocalDate.ofEpochDay(through)).forEach { occurrence ->
            if (occurrence.startAt(zone()).toEpochMilli() >= timestamp &&
                occurrence.date.toEpochDay() !in existing) {
                dao.insertSession(SessionEntity(newId(), courseId, occurrence.date.toEpochDay(), occurrence.startMinute,
                    course.name, course.colorId, timestamp, timestamp, occurrence.endMinute))
            }
        }
        dao.updateSchedule(schedule.copy(generatedThrough = through))
    }

    suspend fun getSessionDetails(sessionId: String): SessionDetails? = database.withTransaction {
        val session = dao.getSession(sessionId) ?: return@withTransaction null
        val topics = dao.getTopics(session.courseId)
        val own = topics.filter { it.isCompleted && it.completionDate == session.date }.map { it.id }.toSet()
        val canEdit = SessionEditPolicy.canEdit(session.date, course(session.courseId).isCompleted, today(now()))
        SessionDetails(session, topics.filter { if (canEdit) !it.isCompleted || it.id in own else it.id in own }, own, canEdit, topics.isNotEmpty())
    }

    suspend fun setSessionResult(sessionId: String, result: SessionResult, selectedTopicIds: Set<String>? = null): Boolean = database.withTransaction {
        val session = dao.getSession(sessionId) ?: fail(RepositoryError.SESSION_NOT_FOUND)
        val course = course(session.courseId)
        val timestamp = now()
        if (!SessionEditPolicy.canEdit(session.date, course.isCompleted, today(timestamp))) {
            fail(if (course.isCompleted) RepositoryError.COURSE_COMPLETED else RepositoryError.INVALID_SESSION_DATE)
        }
        val topics = dao.getTopics(session.courseId).associateBy { it.id }
        val own = topics.values.filter { it.isCompleted && it.completionDate == session.date }.map { it.id }.toSet()
        val selected = if (result == SessionResult.DONE) selectedTopicIds ?: own else emptySet()
        selected.forEach { id ->
            val topic = topics[id]
            if (topic == null) fail(RepositoryError.INVALID_TOPIC)
        }
        // Already completed manual/older topics keep their original provenance.
        val effective = selected.filter { !topics.getValue(it).isCompleted || it in own }.toSet()
        if (session.result == result && effective == own) return@withTransaction false
        val wasExhausted = topics.isNotEmpty() && topics.values.all { it.isCompleted }
        own.filter { it !in effective }.forEach { id ->
            dao.updateTopic(topics.getValue(id).copy(isCompleted = false, completionDate = null))
        }
        effective.filter { it !in own }.forEach { id ->
            dao.updateTopic(topics.getValue(id).copy(isCompleted = true, completionDate = session.date))
        }
        dao.updateSession(session.copy(result = result, updatedAt = timestamp))
        dao.updateCourse(course.copy(updatedAt = timestamp))
        reconcileExhaustion(session.courseId)
        result == SessionResult.DONE && !wasExhausted && dao.getTopics(session.courseId).let { it.isNotEmpty() && it.all { topic -> topic.isCompleted } }
    }

    /** Applies a notification action only while the session is still unmarked. */
    suspend fun applyPendingSessionAction(sessionId: String, result: SessionResult): PendingSessionActionResult = database.withTransaction {
        require(result == SessionResult.DONE || result == SessionResult.SKIPPED)
        val session = dao.getSession(sessionId) ?: return@withTransaction PendingSessionActionResult.IGNORED
        val course = dao.getCourse(session.courseId) ?: return@withTransaction PendingSessionActionResult.IGNORED
        val timestamp = now()
        if (session.result != SessionResult.PENDING ||
            !SessionEditPolicy.canEdit(session.date, course.isCompleted, today(timestamp))) {
            return@withTransaction PendingSessionActionResult.IGNORED
        }
        if (result == SessionResult.DONE && dao.getTopics(session.courseId).isNotEmpty()) {
            return@withTransaction PendingSessionActionResult.NEEDS_TOPICS
        }
        dao.updateSession(session.copy(result = result, updatedAt = timestamp))
        dao.updateCourse(course.copy(updatedAt = timestamp))
        reconcileExhaustion(session.courseId)
        PendingSessionActionResult.APPLIED
    }

    suspend fun reconcileExhaustion(courseId: String) {
        val course = course(courseId)
        if (course.isCompleted) return
        val timestamp = now()
        if (dao.hasExhaustedTopics(courseId)) {
            removeFuturePending(courseId, timestamp)
            // Future sessions were removed: the old generation horizon is no longer valid.
            // Keep the cursor ready for resuming, even after a database reopen.
            dao.getSchedule(courseId)?.let { schedule ->
                val through = today(timestamp).toEpochDay() - 1
                if (schedule.generatedThrough != through) {
                    dao.updateSchedule(schedule.copy(generatedThrough = through))
                }
            }
        } else {
            generate(courseId, today(timestamp).toEpochDay() + 90, timestamp)
        }
    }

    private suspend fun removeFuturePending(courseId: String, timestamp: Long) {
        dao.getSessions(courseId).filter { it.result == SessionResult.PENDING && start(it) > timestamp }.forEach { dao.deleteSession(it.id) }
    }

    suspend fun completeCourse(courseId: String) = database.withTransaction {
        synchronize()
        val course = course(courseId)
        if (!course.isCompleted) {
            val timestamp = now()
            dao.getTopics(courseId).filter { !it.isCompleted }.forEach {
                dao.updateTopic(it.copy(isCompleted = true, completionDate = null))
            }
            removeFuturePending(courseId, timestamp)
            dao.deleteSchedule(courseId)
            dao.updateCourse(course.copy(colorId = null, isCompleted = true, isPaused = false, completedAt = timestamp, updatedAt = timestamp))
        }
    }

    suspend fun pauseCourse(courseId: String) = database.withTransaction {
        synchronize()
        val course = course(courseId)
        if (course.isCompleted) fail(RepositoryError.COURSE_COMPLETED)
        val timestamp = now()
        removeFuturePending(courseId, timestamp)
        dao.deleteSchedule(courseId)
        dao.updateCourse(course.copy(isPaused = true, updatedAt = timestamp))
    }

    suspend fun disableSchedule(courseId: String) = database.withTransaction {
        val course = course(courseId)
        if (course.isCompleted) fail(RepositoryError.COURSE_COMPLETED)
        if (dao.getSchedule(courseId) == null && !course.isPaused) return@withTransaction
        val timestamp = now()
        // Keep started sessions and every recorded result, including early results.
        removeFuturePending(courseId, timestamp)
        dao.deleteSchedule(courseId)
        dao.updateCourse(course.copy(isPaused = false, updatedAt = timestamp))
    }

    suspend fun deleteCourse(courseId: String) = database.withTransaction {
        course(courseId)
        dao.deleteCourse(courseId)
    }

}
