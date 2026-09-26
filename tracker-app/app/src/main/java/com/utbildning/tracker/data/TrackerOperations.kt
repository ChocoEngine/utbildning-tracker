package com.utbildning.tracker.data

import androidx.room.withTransaction
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.ScheduleGenerator
import com.utbildning.tracker.domain.SessionTime
import com.utbildning.tracker.domain.WeeklyRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SessionDetails(
    val session: SessionEntity,
    val selectableTopics: List<TopicEntity>,
    val selectedTopicIds: Set<String>,
)

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
        if (course.isPaused || course.mode != CourseMode.SCHEDULED || rules.isEmpty() || rules.map { it.dayOfWeek }.distinct().size != rules.size) {
            fail(RepositoryError.INVALID_SCHEDULE)
        }
        if (dao.getSchedule(courseId) != null) fail(RepositoryError.SCHEDULE_EXISTS)
        val timestamp = now()
        val date = today(timestamp).toEpochDay()
        if (endsOn != null && endsOn < date) fail(RepositoryError.INVALID_SCHEDULE)
        dao.insertSchedule(ScheduleEntity(courseId, date, endsOn, generationNotBefore = timestamp))
        rules.forEach { dao.insertScheduleRule(ScheduleRuleEntity(courseId, it.dayOfWeek, it.startMinute, it.endMinute, it.endDayOffset)) }
        dao.updateCourse(course.copy(isPaused = false, updatedAt = timestamp))
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
        if (course.isCompleted || course.isPaused || course.exhaustedAt != null || course.mode != CourseMode.SCHEDULED) return
        val schedule = dao.getSchedule(courseId) ?: return
        val first = maxOf(schedule.startsOn, schedule.generatedThrough?.plus(1) ?: schedule.startsOn)
        if (first > through) return
        val rules = dao.getScheduleRules(courseId).map { WeeklyRule(it.dayOfWeek, it.startMinute, it.endMinute, it.endDayOffset) }
        val existing = dao.getSessions(courseId).map { it.date to it.startMinute }.toHashSet()
        ScheduleGenerator.generate(LocalDate.ofEpochDay(schedule.startsOn), schedule.endsOn?.let(LocalDate::ofEpochDay),
            rules, LocalDate.ofEpochDay(first), LocalDate.ofEpochDay(through)).forEach { occurrence ->
            if ((schedule.generationNotBefore == null || occurrence.startAt(zone()).toEpochMilli() >= schedule.generationNotBefore) &&
                (occurrence.date.toEpochDay() to occurrence.startMinute) !in existing) {
                dao.insertSession(SessionEntity(newId(), courseId, occurrence.date.toEpochDay(), occurrence.startMinute,
                    course.name, course.colorId, timestamp, timestamp, occurrence.endMinute, occurrence.endDayOffset))
            }
        }
        dao.updateSchedule(schedule.copy(generatedThrough = through))
    }

    suspend fun getSessionDetails(sessionId: String): SessionDetails? = database.withTransaction {
        val session = dao.getSession(sessionId) ?: return@withTransaction null
        val completions = dao.getCompletions(session.courseId).associateBy { it.topicId }
        val own = completions.values.filter { it.source == CompletionSource.SESSION && it.sessionId == sessionId }.map { it.topicId }.toSet()
        SessionDetails(session, dao.getTopics(session.courseId).filter { it.archivedAt == null && (it.id !in completions || it.id in own) }, own)
    }

    suspend fun setSessionResult(sessionId: String, result: SessionResult, selectedTopicIds: Set<String>? = null) = database.withTransaction {
        val session = dao.getSession(sessionId) ?: fail(RepositoryError.SESSION_NOT_FOUND)
        val topics = dao.getTopics(session.courseId).associateBy { it.id }
        val completions = dao.getCompletions(session.courseId).associateBy { it.topicId }
        val own = completions.values.filter { it.source == CompletionSource.SESSION && it.sessionId == sessionId }
        val selected = if (result == SessionResult.DONE) selectedTopicIds ?: own.map { it.topicId }.toSet() else emptySet()
        selected.forEach { id ->
            val topic = topics[id]
            val completion = completions[id]
            if (topic == null || topic.archivedAt != null || (completion != null && completion !in own)) fail(RepositoryError.INVALID_TOPIC)
        }
        if (session.result == result && selected == own.map { it.topicId }.toSet()) return@withTransaction
        val timestamp = now()
        val course = course(session.courseId)
        own.filter { it.topicId !in selected }.forEach {
            dao.deleteCompletion(it.topicId)
            if (course.isCompleted) {
                dao.insertCompletion(TopicCompletionEntity(it.topicId, course.id,
                    CompletionSource.COURSE_COMPLETION, course.completedAt!!))
            }
        }
        val history = dao.getHistory(sessionId).map { it.topicId }.toSet()
        selected.filter { it !in completions }.forEach { id ->
            if (id !in history) dao.insertHistory(SessionTopicHistoryEntity(sessionId, id, session.courseId, topics.getValue(id).title, timestamp))
            dao.insertCompletion(TopicCompletionEntity(id, session.courseId, CompletionSource.SESSION, timestamp, sessionId))
        }
        if (session.result != result || selectedTopicIds != null) dao.updateSession(session.copy(result = result, updatedAt = timestamp))
        dao.updateCourse(course.copy(updatedAt = timestamp))
        reconcileExhaustion(session.courseId)
    }

    suspend fun reconcileExhaustion(courseId: String) {
        val course = course(courseId)
        if (course.isCompleted) return
        val topics = dao.getTopics(courseId).filter { it.archivedAt == null }
        val complete = dao.getCompletions(courseId).map { it.topicId }.toSet()
        val exhausted = topics.isNotEmpty() && topics.all { it.id in complete }
        val timestamp = now()
        if (exhausted && course.exhaustedAt == null) {
            dao.updateCourse(course.copy(exhaustedAt = timestamp, completionPromptDismissed = false))
            removeFuturePending(courseId, timestamp)
        } else if (!exhausted && (course.exhaustedAt != null || course.completionPromptDismissed)) {
            dao.updateCourse(course.copy(exhaustedAt = null, completionPromptDismissed = false))
            if (course.exhaustedAt != null) {
                dao.getSchedule(courseId)?.let {
                    dao.updateSchedule(it.copy(generatedThrough = today(timestamp).toEpochDay() - 1, generationNotBefore = timestamp))
                }
                generate(courseId, today(timestamp).toEpochDay() + 90, timestamp)
            }
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
            val completed = dao.getCompletions(courseId).map { it.topicId }.toSet()
            dao.getTopics(courseId).filter { it.archivedAt == null && it.id !in completed }.forEach {
                dao.insertCompletion(TopicCompletionEntity(it.id, courseId, CompletionSource.COURSE_COMPLETION, timestamp))
            }
            removeFuturePending(courseId, timestamp)
            dao.deleteSchedule(courseId)
            dao.deleteColorReservation(courseId)
            dao.updateCourse(course.copy(isCompleted = true, isPaused = false, completedAt = timestamp, updatedAt = timestamp))
        }
    }

    suspend fun pauseCourse(courseId: String) = database.withTransaction {
        synchronize()
        val course = course(courseId)
        if (course.isCompleted) fail(RepositoryError.COURSE_COMPLETED)
        if (course.mode != CourseMode.SCHEDULED) fail(RepositoryError.INVALID_SCHEDULE)
        val timestamp = now()
        removeFuturePending(courseId, timestamp)
        dao.deleteSchedule(courseId)
        dao.updateCourse(course.copy(isPaused = true, updatedAt = timestamp))
    }

    suspend fun deleteCourse(courseId: String) = database.withTransaction {
        course(courseId)
        dao.deleteCourse(courseId)
    }

    suspend fun dismissCompletionPrompt(courseId: String) = database.withTransaction {
        reconcileExhaustion(courseId)
        val course = course(courseId)
        if (!course.isCompleted && course.exhaustedAt != null) dao.updateCourse(course.copy(completionPromptDismissed = true))
    }

    suspend fun shouldOfferCompletion(courseId: String): Boolean = database.withTransaction {
        reconcileExhaustion(courseId)
        val course = course(courseId)
        !course.isCompleted && course.exhaustedAt != null && !course.completionPromptDismissed
    }
}
