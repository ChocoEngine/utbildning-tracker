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
        TopicEntity::class,
        ScheduleEntity::class,
        ScheduleRuleEntity::class,
        SessionRecord::class,
    ],
    version = 7,
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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE topics ADD COLUMN isCompleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE topics ADD COLUMN completionDate INTEGER")
                db.execSQL("UPDATE topics SET isCompleted = 1, completionDate = (SELECT s.date FROM topic_completions tc JOIN sessions s ON s.id = tc.sessionId AND s.courseId = tc.courseId WHERE tc.topicId = topics.id AND tc.source = 'SESSION') WHERE EXISTS (SELECT 1 FROM topic_completions tc WHERE tc.topicId = topics.id)")
                db.execSQL("DROP TABLE topic_completions")
                db.execSQL("DROP TABLE session_topic_history")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TEMP TABLE backup_courses_v5 AS SELECT * FROM courses")
                db.execSQL("CREATE TEMP TABLE backup_topics_v5 AS SELECT * FROM topics")
                db.execSQL("CREATE TEMP TABLE backup_schedules_v5 AS SELECT * FROM schedules")
                db.execSQL("CREATE TEMP TABLE backup_schedule_rules_v5 AS SELECT * FROM schedule_rules")
                db.execSQL("CREATE TEMP TABLE backup_sessions_v5 AS SELECT * FROM sessions")
                db.execSQL("DROP TABLE sessions")
                db.execSQL("DROP TABLE schedule_rules")
                db.execSQL("DROP TABLE schedules")
                db.execSQL("DROP TABLE topics")
                db.execSQL("DROP TABLE color_reservations")
                db.execSQL("DROP TABLE courses")
                db.execSQL("CREATE TABLE `courses` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `colorId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `categoryId` TEXT, `isCompleted` INTEGER NOT NULL, `isPaused` INTEGER NOT NULL, `completedAt` INTEGER, `exhaustedAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_courses_categoryId` ON `courses` (`categoryId`)")
                db.execSQL("INSERT INTO courses (`id`,`name`,`colorId`,`createdAt`,`updatedAt`,`categoryId`,`isCompleted`,`isPaused`,`completedAt`,`exhaustedAt`) SELECT `id`,`name`,CASE WHEN isCompleted = 1 THEN NULL ELSE colorId END,`createdAt`,`updatedAt`,`categoryId`,`isCompleted`,`isPaused`,`completedAt`,`exhaustedAt` FROM backup_courses_v5")
                db.execSQL("DROP TABLE backup_courses_v5")
                db.execSQL("CREATE TABLE `topics` (`id` TEXT NOT NULL, `courseId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL DEFAULT 0, `completionDate` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_topics_courseId_position` ON `topics` (`courseId`, `position`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_topics_id_courseId` ON `topics` (`id`, `courseId`)")
                db.execSQL("INSERT INTO topics (`id`,`courseId`,`position`,`title`,`isCompleted`,`completionDate`) SELECT `id`,`courseId`,`position`,`title`,`isCompleted`,`completionDate` FROM backup_topics_v5 WHERE archivedAt IS NULL OR isCompleted = 1")
                db.execSQL("DROP TABLE backup_topics_v5")
                db.execSQL("CREATE TABLE `schedules` (`courseId` TEXT NOT NULL, `startsOn` INTEGER NOT NULL, `endsOn` INTEGER, `generatedThrough` INTEGER, PRIMARY KEY(`courseId`), FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO schedules (`courseId`,`startsOn`,`endsOn`,`generatedThrough`) SELECT `courseId`,`startsOn`,`endsOn`,`generatedThrough` FROM backup_schedules_v5")
                db.execSQL("DROP TABLE backup_schedules_v5")
                db.execSQL("CREATE TABLE `schedule_rules` (`courseId` TEXT NOT NULL, `dayOfWeek` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `endMinute` INTEGER, PRIMARY KEY(`courseId`,`dayOfWeek`), FOREIGN KEY(`courseId`) REFERENCES `schedules`(`courseId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO schedule_rules (`courseId`,`dayOfWeek`,`startMinute`,`endMinute`) SELECT `courseId`,`dayOfWeek`,`startMinute`,`endMinute` FROM backup_schedule_rules_v5")
                db.execSQL("DROP TABLE backup_schedule_rules_v5")
                db.execSQL("CREATE TABLE `sessions` (`id` TEXT NOT NULL, `courseId` TEXT NOT NULL, `date` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `endMinute` INTEGER, `result` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sessions_courseId_date_startMinute` ON `sessions` (`courseId`, `date`, `startMinute`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sessions_id_courseId` ON `sessions` (`id`, `courseId`)")
                db.execSQL("INSERT INTO sessions (`id`,`courseId`,`date`,`startMinute`,`createdAt`,`updatedAt`,`endMinute`,`result`) SELECT `id`,`courseId`,`date`,`startMinute`,`createdAt`,`updatedAt`,`endMinute`,`result` FROM backup_sessions_v5")
                db.execSQL("DROP TABLE backup_sessions_v5")
                db.execSQL("CREATE UNIQUE INDEX index_courses_active_color ON courses(colorId) WHERE isCompleted = 0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TEMP TABLE backup_courses_v6 AS SELECT * FROM courses")
                db.execSQL("CREATE TEMP TABLE backup_topics_v6 AS SELECT * FROM topics")
                db.execSQL("CREATE TEMP TABLE backup_schedules_v6 AS SELECT * FROM schedules")
                db.execSQL("CREATE TEMP TABLE backup_schedule_rules_v6 AS SELECT * FROM schedule_rules")
                db.execSQL("CREATE TEMP TABLE backup_sessions_v6 AS SELECT * FROM sessions")
                db.execSQL("UPDATE backup_schedules_v6 SET generatedThrough = NULL WHERE courseId IN (SELECT id FROM backup_courses_v6 WHERE exhaustedAt IS NOT NULL) OR (EXISTS (SELECT 1 FROM backup_topics_v6 t WHERE t.courseId = backup_schedules_v6.courseId) AND NOT EXISTS (SELECT 1 FROM backup_topics_v6 t WHERE t.courseId = backup_schedules_v6.courseId AND t.isCompleted = 0))")
                db.execSQL("DROP TABLE sessions")
                db.execSQL("DROP TABLE schedule_rules")
                db.execSQL("DROP TABLE schedules")
                db.execSQL("DROP TABLE topics")
                db.execSQL("DROP TABLE courses")
                db.execSQL("CREATE TABLE `courses` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `colorId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `categoryId` TEXT, `isCompleted` INTEGER NOT NULL, `isPaused` INTEGER NOT NULL, `completedAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_courses_categoryId` ON `courses` (`categoryId`)")
                db.execSQL("INSERT INTO courses (`id`,`name`,`colorId`,`createdAt`,`updatedAt`,`categoryId`,`isCompleted`,`isPaused`,`completedAt`) SELECT `id`,`name`,`colorId`,`createdAt`,`updatedAt`,`categoryId`,`isCompleted`,`isPaused`,`completedAt` FROM backup_courses_v6")
                db.execSQL("DROP TABLE backup_courses_v6")
                db.execSQL("CREATE TABLE `topics` (`id` TEXT NOT NULL, `courseId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL DEFAULT 0, `completionDate` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_topics_courseId_position` ON `topics` (`courseId`, `position`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_topics_id_courseId` ON `topics` (`id`, `courseId`)")
                db.execSQL("INSERT INTO topics (`id`,`courseId`,`position`,`title`,`isCompleted`,`completionDate`) SELECT `id`,`courseId`,`position`,`title`,`isCompleted`,`completionDate` FROM backup_topics_v6")
                db.execSQL("DROP TABLE backup_topics_v6")
                db.execSQL("CREATE TABLE `schedules` (`courseId` TEXT NOT NULL, `startsOn` INTEGER NOT NULL, `endsOn` INTEGER, `generatedThrough` INTEGER, PRIMARY KEY(`courseId`), FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO schedules (`courseId`,`startsOn`,`endsOn`,`generatedThrough`) SELECT `courseId`,`startsOn`,`endsOn`,`generatedThrough` FROM backup_schedules_v6")
                db.execSQL("DROP TABLE backup_schedules_v6")
                db.execSQL("CREATE TABLE `schedule_rules` (`courseId` TEXT NOT NULL, `dayOfWeek` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `endMinute` INTEGER, PRIMARY KEY(`courseId`,`dayOfWeek`), FOREIGN KEY(`courseId`) REFERENCES `schedules`(`courseId`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO schedule_rules (`courseId`,`dayOfWeek`,`startMinute`,`endMinute`) SELECT `courseId`,`dayOfWeek`,`startMinute`,`endMinute` FROM backup_schedule_rules_v6")
                db.execSQL("DROP TABLE backup_schedule_rules_v6")
                db.execSQL("CREATE TABLE `sessions` (`id` TEXT NOT NULL, `courseId` TEXT NOT NULL, `date` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `endMinute` INTEGER, `result` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sessions_courseId_date_startMinute` ON `sessions` (`courseId`, `date`, `startMinute`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sessions_id_courseId` ON `sessions` (`id`, `courseId`)")
                db.execSQL("INSERT INTO sessions (`id`,`courseId`,`date`,`startMinute`,`createdAt`,`updatedAt`,`endMinute`,`result`) SELECT `id`,`courseId`,`date`,`startMinute`,`createdAt`,`updatedAt`,`endMinute`,`result` FROM backup_sessions_v6")
                db.execSQL("DROP TABLE backup_sessions_v6")
                db.execSQL("CREATE UNIQUE INDEX index_courses_active_color ON courses(colorId) WHERE isCompleted = 0")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_date_startMinute` ON `sessions` (`date`, `startMinute`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_result_date` ON `sessions` (`result`, `date`)")
            }
        }

        fun open(context: Context, name: String = "tracker.db"): TrackerDatabase =
            Room.databaseBuilder(context.applicationContext, TrackerDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Room has no annotation for a partial index; its structural index
                        // declaration is replaced with the active-course predicate.
                        db.execSQL("DROP INDEX IF EXISTS index_courses_active_color")
                        db.execSQL("CREATE UNIQUE INDEX index_courses_active_color ON courses(colorId) WHERE isCompleted = 0")
                    }
                })
                .build()
    }
}
