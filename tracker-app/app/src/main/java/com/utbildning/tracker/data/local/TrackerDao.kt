package com.utbildning.tracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Storage primitives. Business invariants belong to repository transactions. */
@Dao
interface TrackerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategory(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCourse(course: CourseEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTopic(topic: TopicEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSchedule(schedule: ScheduleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScheduleRule(rule: ScheduleRuleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSessionRecord(session: SessionRecord)

    suspend fun insertSession(session: SessionEntity) = insertSessionRecord(session.record())

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateCourse(course: CourseEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateCategory(category: CategoryEntity)

    @Query("SELECT * FROM categories ORDER BY name, id")
    suspend fun getCategories(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM courses WHERE isCompleted = 0")
    suspend fun countUnfinishedCourses(): Int

    @Query("SELECT COUNT(*) FROM courses WHERE categoryId = :categoryId")
    suspend fun countCoursesInCategory(categoryId: String): Int

    @Query("DELETE FROM topics WHERE id = :id")
    suspend fun deleteTopic(id: String)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateTopic(topic: TopicEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateSessionRecord(session: SessionRecord)

    suspend fun updateSession(session: SessionEntity) = updateSessionRecord(session.record())

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateSchedule(schedule: ScheduleEntity)

    @Query("SELECT * FROM courses ORDER BY createdAt, id")
    suspend fun getCourses(): List<CourseEntity>

    @Query("SELECT s.*, c.name AS courseName, c.colorId AS colorId, c.isCompleted AS courseCompleted FROM sessions s JOIN courses c ON c.id = s.courseId ORDER BY s.date, s.startMinute, s.id")
    suspend fun getAllSessions(): List<SessionEntity>

    @Query("SELECT s.*, c.name AS courseName, c.colorId AS colorId, c.isCompleted AS courseCompleted FROM sessions s JOIN courses c ON c.id = s.courseId ORDER BY s.date, s.startMinute, s.id")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getCourse(id: String): CourseEntity?

    @Query("SELECT * FROM topics WHERE id = :id")
    suspend fun getTopic(id: String): TopicEntity?

    @Query("SELECT s.*, c.name AS courseName, c.colorId AS colorId, c.isCompleted AS courseCompleted FROM sessions s JOIN courses c ON c.id = s.courseId WHERE s.id = :id")
    suspend fun getSession(id: String): SessionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM topics WHERE courseId = :courseId) AND NOT EXISTS(SELECT 1 FROM topics WHERE courseId = :courseId AND isCompleted = 0)")
    suspend fun hasExhaustedTopics(courseId: String): Boolean

    @Query("SELECT * FROM courses WHERE isCompleted = 0 AND isPaused = 0 AND (NOT EXISTS(SELECT 1 FROM topics WHERE courseId = courses.id) OR EXISTS(SELECT 1 FROM topics WHERE courseId = courses.id AND isCompleted = 0))")
    suspend fun getReminderCourses(): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE isCompleted = 0 AND isPaused = 0 AND (NOT EXISTS(SELECT 1 FROM topics WHERE courseId = courses.id) OR EXISTS(SELECT 1 FROM topics WHERE courseId = courses.id AND isCompleted = 0))")
    fun observeReminderCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM topics WHERE courseId = :courseId ORDER BY position, id")
    suspend fun getTopics(courseId: String): List<TopicEntity>

    @Query("SELECT s.*, c.name AS courseName, c.colorId AS colorId, c.isCompleted AS courseCompleted FROM sessions s JOIN courses c ON c.id = s.courseId WHERE s.courseId = :courseId ORDER BY s.date, s.startMinute, s.id")
    suspend fun getSessions(courseId: String): List<SessionEntity>

    @Query("SELECT * FROM schedules WHERE courseId = :courseId")
    suspend fun getSchedule(courseId: String): ScheduleEntity?

    @Query("SELECT * FROM schedule_rules WHERE courseId = :courseId ORDER BY dayOfWeek")
    suspend fun getScheduleRules(courseId: String): List<ScheduleRuleEntity>

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteCourse(id: String)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: String)

    @Query("DELETE FROM schedules WHERE courseId = :courseId")
    suspend fun deleteSchedule(courseId: String)

    @Query("SELECT * FROM courses ORDER BY createdAt, id")
    fun observeCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM categories ORDER BY name, id")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM topics WHERE courseId = :courseId ORDER BY position, id")
    fun observeTopics(courseId: String): Flow<List<TopicEntity>>

}
