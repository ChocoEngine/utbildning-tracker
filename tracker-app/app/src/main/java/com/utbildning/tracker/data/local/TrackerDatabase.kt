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
        SessionRecord::class,
        SessionTopicHistoryEntity::class,
        TopicCompletionEntity::class,
    ],
    version = 3,
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TEMP TABLE backup_sessions AS SELECT * FROM sessions")
                db.execSQL("CREATE TEMP TABLE backup_session_topic_history AS SELECT * FROM session_topic_history")
                db.execSQL("CREATE TEMP TABLE backup_topic_completions AS SELECT * FROM topic_completions")
                db.execSQL("DROP TABLE topic_completions")
                db.execSQL("DROP TABLE session_topic_history")
                db.execSQL("DROP TABLE sessions")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sessions` (`id` TEXT NOT NULL, `courseId` TEXT NOT NULL, `date` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `endMinute` INTEGER, `endDayOffset` INTEGER NOT NULL, `result` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sessions_courseId_date_startMinute` ON `sessions` (`courseId`, `date`, `startMinute`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sessions_id_courseId` ON `sessions` (`id`, `courseId`)")
                db.execSQL("INSERT INTO sessions (`id`,`courseId`,`date`,`startMinute`,`createdAt`,`updatedAt`,`endMinute`,`endDayOffset`,`result`) SELECT `id`,`courseId`,`date`,`startMinute`,`createdAt`,`updatedAt`,`endMinute`,`endDayOffset`,`result` FROM backup_sessions")
                db.execSQL("DROP TABLE backup_sessions")
                db.execSQL("CREATE TABLE IF NOT EXISTS `session_topic_history` (`sessionId` TEXT NOT NULL, `topicId` TEXT NOT NULL, `courseId` TEXT NOT NULL, `topicTitleSnapshot` TEXT NOT NULL, `recordedAt` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `topicId`), FOREIGN KEY(`sessionId`, `courseId`) REFERENCES `sessions`(`id`, `courseId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`topicId`, `courseId`) REFERENCES `topics`(`id`, `courseId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_topic_history_sessionId_courseId` ON `session_topic_history` (`sessionId`, `courseId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_topic_history_topicId_courseId` ON `session_topic_history` (`topicId`, `courseId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_session_topic_history_sessionId_topicId_courseId` ON `session_topic_history` (`sessionId`, `topicId`, `courseId`)")
                db.execSQL("INSERT INTO session_topic_history (`sessionId`,`topicId`,`courseId`,`topicTitleSnapshot`,`recordedAt`) SELECT `sessionId`,`topicId`,`courseId`,`topicTitleSnapshot`,`recordedAt` FROM backup_session_topic_history")
                db.execSQL("DROP TABLE backup_session_topic_history")
                db.execSQL("CREATE TABLE IF NOT EXISTS `topic_completions` (`topicId` TEXT NOT NULL, `courseId` TEXT NOT NULL, `source` TEXT NOT NULL, `completedAt` INTEGER NOT NULL, `sessionId` TEXT, PRIMARY KEY(`topicId`), FOREIGN KEY(`topicId`, `courseId`) REFERENCES `topics`(`id`, `courseId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`sessionId`, `topicId`, `courseId`) REFERENCES `session_topic_history`(`sessionId`, `topicId`, `courseId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_topic_completions_topicId_courseId` ON `topic_completions` (`topicId`, `courseId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_topic_completions_sessionId_topicId_courseId` ON `topic_completions` (`sessionId`, `topicId`, `courseId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_topic_completions_courseId` ON `topic_completions` (`courseId`)")
                db.execSQL("INSERT INTO topic_completions (`topicId`,`courseId`,`source`,`completedAt`,`sessionId`) SELECT `topicId`,`courseId`,`source`,`completedAt`,`sessionId` FROM backup_topic_completions")
                db.execSQL("DROP TABLE backup_topic_completions")
            }
        }

        fun open(context: Context, name: String = "tracker.db"): TrackerDatabase =
            Room.databaseBuilder(context.applicationContext, TrackerDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
