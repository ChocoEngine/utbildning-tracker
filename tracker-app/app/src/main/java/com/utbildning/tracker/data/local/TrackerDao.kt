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
    suspend fun insertColorReservation(reservation: ColorReservationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTopic(topic: TopicEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSchedule(schedule: ScheduleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScheduleRule(rule: ScheduleRuleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHistory(history: SessionTopicHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCompletion(completion: TopicCompletionEntity)

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

    @Query("SELECT * FROM color_reservations ORDER BY colorId")
    suspend fun getColorReservations(): List<ColorReservationEntity>

    @Query("DELETE FROM color_reservations WHERE courseId = :courseId")
    suspend fun deleteColorReservation(courseId: String)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateTopic(topic: TopicEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateSession(session: SessionEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateSchedule(schedule: ScheduleEntity)

    @Query("SELECT * FROM courses ORDER BY createdAt, id")
    suspend fun getCourses(): List<CourseEntity>

    @Query("SELECT * FROM sessions ORDER BY date, startMinute, id")
    suspend fun getAllSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions ORDER BY date, startMinute, id")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getCourse(id: String): CourseEntity?

    @Query("SELECT * FROM topics WHERE id = :id")
    suspend fun getTopic(id: String): TopicEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: String): SessionEntity?

    @Query("SELECT * FROM topic_completions WHERE topicId = :topicId")
    suspend fun getCompletion(topicId: String): TopicCompletionEntity?

    @Query("SELECT * FROM topic_completions WHERE courseId = :courseId ORDER BY topicId")
    suspend fun getCompletions(courseId: String): List<TopicCompletionEntity>

    @Query("SELECT * FROM session_topic_history WHERE sessionId = :sessionId ORDER BY topicId")
    suspend fun getHistory(sessionId: String): List<SessionTopicHistoryEntity>

    @Query("SELECT * FROM topics WHERE courseId = :courseId ORDER BY position, id")
    suspend fun getTopics(courseId: String): List<TopicEntity>

    @Query("SELECT * FROM sessions WHERE courseId = :courseId ORDER BY date, startMinute, id")
    suspend fun getSessions(courseId: String): List<SessionEntity>

    @Query("SELECT * FROM schedules WHERE courseId = :courseId")
    suspend fun getSchedule(courseId: String): ScheduleEntity?

    @Query("SELECT * FROM schedule_rules WHERE courseId = :courseId ORDER BY dayOfWeek")
    suspend fun getScheduleRules(courseId: String): List<ScheduleRuleEntity>

    @Query("SELECT * FROM color_reservations WHERE courseId = :courseId")
    suspend fun getReservation(courseId: String): ColorReservationEntity?

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteCourse(id: String)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: String)

    @Query("DELETE FROM schedules WHERE courseId = :courseId")
    suspend fun deleteSchedule(courseId: String)

    @Query("DELETE FROM topic_completions WHERE topicId = :topicId")
    suspend fun deleteCompletion(topicId: String)

    @Query("SELECT * FROM courses ORDER BY createdAt, id")
    fun observeCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM categories ORDER BY name, id")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM topics WHERE courseId = :courseId ORDER BY position, id")
    fun observeTopics(courseId: String): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topic_completions WHERE courseId = :courseId ORDER BY topicId")
    fun observeCompletions(courseId: String): Flow<List<TopicCompletionEntity>>
}
