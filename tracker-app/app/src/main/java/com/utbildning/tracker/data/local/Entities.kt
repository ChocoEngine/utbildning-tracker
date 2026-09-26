package com.utbildning.tracker.data.local

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class CourseMode { SCHEDULED, UNSCHEDULED }
enum class SessionResult { PENDING, DONE, SKIPPED }
enum class CompletionSource { MANUAL, SESSION, COURSE_COMPLETION }

private fun validateId(id: String) = require(id.isNotBlank())
private fun validateTime(start: Int, end: Int?, offset: Int) {
    require(start in 0..1439)
    require(end == null || end in 0..1439)
    require(offset in 0..1)
    require(end != null || offset == 0)
}

@Entity(tableName = "categories")
data class CategoryEntity(@PrimaryKey val id: String, val name: String) {
    init {
        validateId(id)
        require(name.isNotBlank())
    }
}

@Entity(
    tableName = "courses",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("categoryId"),
    ],
)
data class CourseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorId: Int,
    val mode: CourseMode,
    val createdAt: Long,
    val updatedAt: Long,
    val categoryId: String? = null,
    val isCompleted: Boolean = false,
    val isPaused: Boolean = false,
    val completedAt: Long? = null,
    val exhaustedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val completionPromptDismissed: Boolean = false,
) {
    init {
        validateId(id)
        require(name.isNotBlank())
        require(colorId in 0..9)
        categoryId?.let(::validateId)
        require(isCompleted == (completedAt != null))
    }
}

@Entity(
    tableName = "color_reservations",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["courseId"], unique = true),
    ],
)
data class ColorReservationEntity(@PrimaryKey val colorId: Int, val courseId: String) {
    init {
        require(colorId in 0..9)
        validateId(courseId)
    }
}

@Entity(
    tableName = "topics",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["courseId", "position"]),
        Index(value = ["id", "courseId"], unique = true),
    ],
)
data class TopicEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val position: Int,
    val title: String,
    val archivedAt: Long? = null,
) {
    init {
        validateId(id)
        validateId(courseId)
        require(position >= 0)
        require(title.isNotBlank())
    }
}

@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ScheduleEntity(
    @PrimaryKey val courseId: String,
    val startsOn: Long,
    val endsOn: Long? = null,
    val generatedThrough: Long? = null,
    val generationNotBefore: Long? = null,
) {
    init {
        validateId(courseId)
        require(endsOn == null || endsOn >= startsOn)
    }
}

@Entity(
    tableName = "schedule_rules",
    primaryKeys = ["courseId", "dayOfWeek"],
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["courseId"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ScheduleRuleEntity(
    val courseId: String,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int? = null,
    val endDayOffset: Int = 0,
) {
    init {
        validateId(courseId)
        require(dayOfWeek in 1..7)
        validateTime(startMinute, endMinute, endDayOffset)
    }
}

@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["courseId", "date", "startMinute"], unique = true),
        Index(value = ["id", "courseId"], unique = true),
    ],
)
data class SessionRecord(
    @PrimaryKey val id: String,
    val courseId: String,
    val date: Long,
    val startMinute: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val endMinute: Int? = null,
    val endDayOffset: Int = 0,
    val result: SessionResult = SessionResult.PENDING,
) {
    init {
        validateId(id)
        validateId(courseId)
        validateTime(startMinute, endMinute, endDayOffset)
    }
}

data class SessionEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val date: Long,
    val startMinute: Int,
    val courseName: String,
    val colorId: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val endMinute: Int? = null,
    val endDayOffset: Int = 0,
    val result: SessionResult = SessionResult.PENDING,
) {
    init {
        validateId(id)
        validateId(courseId)
        validateTime(startMinute, endMinute, endDayOffset)
        require(courseName.isNotBlank())
        require(colorId in 0..9)
    }
    fun record() = SessionRecord(id, courseId, date, startMinute, createdAt, updatedAt, endMinute, endDayOffset, result)
}

@Entity(
    tableName = "session_topic_history",
    primaryKeys = ["sessionId", "topicId"],
    foreignKeys = [
        ForeignKey(
            entity = SessionRecord::class,
            parentColumns = ["id", "courseId"],
            childColumns = ["sessionId", "courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TopicEntity::class,
            parentColumns = ["id", "courseId"],
            childColumns = ["topicId", "courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "courseId"]),
        Index(value = ["topicId", "courseId"]),
        Index(value = ["sessionId", "topicId", "courseId"], unique = true),
    ],
)
data class SessionTopicHistoryEntity(
    val sessionId: String,
    val topicId: String,
    val courseId: String,
    val topicTitleSnapshot: String,
    val recordedAt: Long,
) {
    init {
        validateId(sessionId)
        validateId(topicId)
        validateId(courseId)
        require(topicTitleSnapshot.isNotBlank())
    }
}

@Entity(
    tableName = "topic_completions",
    foreignKeys = [
        ForeignKey(
            entity = TopicEntity::class,
            parentColumns = ["id", "courseId"],
            childColumns = ["topicId", "courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SessionTopicHistoryEntity::class,
            parentColumns = ["sessionId", "topicId", "courseId"],
            childColumns = ["sessionId", "topicId", "courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["topicId", "courseId"]),
        Index(value = ["sessionId", "topicId", "courseId"]),
        Index("courseId"),
    ],
)
data class TopicCompletionEntity(
    @PrimaryKey val topicId: String,
    val courseId: String,
    val source: CompletionSource,
    val completedAt: Long,
    val sessionId: String? = null,
) {
    init {
        validateId(topicId)
        validateId(courseId)
        sessionId?.let(::validateId)
        require((source == CompletionSource.SESSION) == (sessionId != null))
    }
}
