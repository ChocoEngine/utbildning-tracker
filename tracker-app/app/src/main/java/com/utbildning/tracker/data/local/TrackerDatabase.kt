package com.utbildning.tracker.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CategoryEntity::class,
        CourseEntity::class,
        ColorReservationEntity::class,
        TopicEntity::class,
        ScheduleEntity::class,
        ScheduleRuleEntity::class,
        SessionEntity::class,
        SessionTopicHistoryEntity::class,
        TopicCompletionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class TrackerDatabase : RoomDatabase() {
    abstract fun trackerDao(): TrackerDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN exhaustedAt INTEGER")
                db.execSQL("ALTER TABLE courses ADD COLUMN completionPromptDismissed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE schedules ADD COLUMN generationNotBefore INTEGER")
            }
        }

        fun open(context: Context, name: String = "tracker.db"): TrackerDatabase =
            Room.databaseBuilder(context.applicationContext, TrackerDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
