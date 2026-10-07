package com.utbildning.tracker.backup

import com.utbildning.tracker.data.local.CategoryEntity
import com.utbildning.tracker.data.local.CourseEntity
import com.utbildning.tracker.data.local.ScheduleEntity
import com.utbildning.tracker.data.local.ScheduleRuleEntity
import com.utbildning.tracker.data.local.SessionRecord
import com.utbildning.tracker.data.local.TopicEntity

data class BackupSnapshot(
    val languageTag: String,
    val categories: List<CategoryEntity>,
    val courses: List<CourseEntity>,
    val topics: List<TopicEntity>,
    val schedules: List<ScheduleEntity>,
    val scheduleRules: List<ScheduleRuleEntity>,
    val sessions: List<SessionRecord>,
)

data class BackupMetadata(
    val backupId: String,
    val createdAt: String,
    val versionCode: Long,
    val versionName: String,
)

data class ValidatedBackup(
    val metadata: BackupMetadata,
    val snapshot: BackupSnapshot,
)

class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
