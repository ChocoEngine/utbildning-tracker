package com.utbildning.tracker.data.local

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SessionResult { PENDING, DONE, SKIPPED }

/** Internal restore marker. It is deliberately excluded from portable backup data. */
@Entity(tableName = "backup_state")
data class BackupStateEntity(
    @PrimaryKey val key: String = "committed_backup_id",
    val value: String,
) {
    init {
        require(key == "committed_backup_id")
        validateId(value)
    }
}

private fun validateId(id: String) = require(id.isNotBlank())
private fun validateTime(start: Int, end: Int?) {
    require(start in 0..1439)
    require(end == null || end in 0..1439)
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
        Index(value = ["colorId"], unique = true, name = "index_courses_active_color"),
    ],
)
data class CourseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorId: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    val categoryId: String? = null,
    val isCompleted: Boolean = false,
    val isPaused: Boolean = false,
    val completedAt: Long? = null,
) {
    init {
        validateId(id)
        require(name.isNotBlank())
        require(colorId == null || colorId in 0..9)
        categoryId?.let(::validateId)
        require(isCompleted == (completedAt != null))
        require(isCompleted == (colorId == null))
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
    @ColumnInfo(defaultValue = "0") val isCompleted: Boolean = false,
    val completionDate: Long? = null,
) {
    init {
        validateId(id)
        validateId(courseId)
        require(position >= 0)
        require(title.isNotBlank())
        require(isCompleted || completionDate == null)
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
) {
    init {
        validateId(courseId)
        require(dayOfWeek in 1..7)
        validateTime(startMinute, endMinute)
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
        Index(value = ["date", "startMinute"]),
        Index(value = ["result", "date"]),
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
    val result: SessionResult = SessionResult.PENDING,
) {
    init {
        validateId(id)
        validateId(courseId)
        validateTime(startMinute, endMinute)
    }
}

data class SessionEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val date: Long,
    val startMinute: Int,
    val courseName: String,
    val colorId: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    val endMinute: Int? = null,
    val result: SessionResult = SessionResult.PENDING,
    val courseCompleted: Boolean = false,
) {
    init {
        validateId(id)
        validateId(courseId)
        validateTime(startMinute, endMinute)
        require(courseName.isNotBlank())
        require(colorId == null || colorId in 0..9)
    }
    fun record() = SessionRecord(id, courseId, date, startMinute, createdAt, updatedAt, endMinute, result)
}
