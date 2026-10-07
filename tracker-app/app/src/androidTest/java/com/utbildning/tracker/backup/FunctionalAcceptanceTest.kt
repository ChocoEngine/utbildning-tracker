package com.utbildning.tracker.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.CategoryEntity
import com.utbildning.tracker.data.local.CourseEntity
import com.utbildning.tracker.data.local.ScheduleEntity
import com.utbildning.tracker.data.local.ScheduleRuleEntity
import com.utbildning.tracker.data.local.SessionRecord
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.data.local.TopicEntity
import com.utbildning.tracker.data.local.TrackerDatabase
import com.utbildning.tracker.domain.WeeklyRule
import com.utbildning.tracker.maintenance.RetentionPolicy
import com.utbildning.tracker.notifications.ReminderScheduler
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** F17 acceptance: one data set crosses every destructive feature and two restore points. */
@RunWith(AndroidJUnit4::class)
class FunctionalAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("UTC")
    private val firstDay = LocalDate.parse("2026-10-05")
    private var clock = Instant.parse("2026-10-05T10:00:00Z").toEpochMilli()

    @Test fun backupLifecycleCleanupAndTwoRestoresSurviveDatabaseRestartWithoutDuplicates() = runBlocking {
        val name = "f17-acceptance.db"
        context.deleteDatabase(name)
        context.getSharedPreferences("backup_restore", Context.MODE_PRIVATE).edit().clear().commit()
        val files = mutableListOf<File>()
        var database = TrackerDatabase.open(context, name)
        var repository = repository(database)
        try {
            repository.replaceBackup(initialSnapshot(), "initial-state")
            val oldSnapshot = repository.createBackupSnapshot("en").sorted()
            val oldBackup = archive(oldSnapshot, OLD_METADATA).also(files::add)

            repository.pauseCourse("course")
            val paused = repository.createBackupSnapshot("en")
            assertTrue(paused.courses.single { it.id == "course" }.isPaused)
            assertNull(paused.schedules.singleOrNull { it.courseId == "course" })
            assertEquals(listOf("old-history"), paused.sessions.filter { it.courseId == "course" }.map { it.id })
            assertEquals(1, paused.topics.count { it.courseId == "course" && it.isCompleted })

            repository.saveInitialSchedule("course", listOf(WeeklyRule(2, 9 * 60)))
            assertFalse(repository.getCourse("course")!!.isPaused)
            assertNotNull(repository.getSchedule("course"))

            repository.completeCourse("course")
            assertTrue(repository.getCourse("course")!!.isCompleted)
            assertNull(repository.getCourse("course")!!.colorId)
            assertTrue(repository.createBackupSnapshot("en").topics.filter { it.courseId == "course" }.all { it.isCompleted })

            repository.restartCourse("course")
            val restarted = repository.createBackupSnapshot("en")
            assertEquals(1, restarted.courses.single { it.id == "course" }.colorId)
            assertTrue(restarted.topics.filter { it.courseId == "course" }.none { it.isCompleted })
            assertTrue(restarted.sessions.none { it.courseId == "course" })
            assertNull(restarted.schedules.singleOrNull { it.courseId == "course" })

            repository.saveInitialSchedule("course", listOf(WeeklyRule(2, 9 * 60)))
            clock = Instant.parse("2026-10-06T10:00:00Z").toEpochMilli()
            val firstGenerated = repository.createBackupSnapshot("en").sessions
                .single { it.courseId == "course" && it.date == firstDay.plusDays(1).toEpochDay() }
            repository.setSessionResult(firstGenerated.id, SessionResult.DONE, setOf("topic-1"))
            repository.disableSchedule("course")
            val withoutSchedule = repository.createBackupSnapshot("en")
            assertNull(withoutSchedule.schedules.singleOrNull { it.courseId == "course" })
            assertEquals(SessionResult.DONE, withoutSchedule.sessions.single { it.id == firstGenerated.id }.result)
            assertTrue(withoutSchedule.topics.single { it.id == "topic-1" }.isCompleted)

            repository.saveInitialSchedule("course", listOf(WeeklyRule(2, 9 * 60)))
            clock = Instant.parse("2026-10-20T10:00:00Z").toEpochMilli()
            assertEquals(1, repository.cleanupOldSessions())
            val afterCleanup = repository.createBackupSnapshot("ru")
            assertTrue(afterCleanup.sessions.none { it.id == firstGenerated.id })
            assertTrue(afterCleanup.topics.single { it.id == "topic-1" }.isCompleted)
            assertEquals(1, afterCleanup.courses.single { it.id == "course" }.colorId)
            assertNotNull(afterCleanup.schedules.singleOrNull { it.courseId == "course" })

            val newSnapshot = afterCleanup.sorted()
            val newBackup = archive(newSnapshot, NEW_METADATA).also(files::add)
            val importer = BackupImporter(context, repository, postRestore = {})
            importer.restore(oldBackup)
            assertEquals(oldSnapshot, repository.createBackupSnapshot("en").sorted())
            importer.restore(newBackup)
            assertEquals(newSnapshot, repository.createBackupSnapshot("ru").sorted())

            database.close()
            database = TrackerDatabase.open(context, name)
            repository = repository(database)
            assertEquals(newSnapshot, repository.createBackupSnapshot("ru").sorted())

            context.getSharedPreferences("reminders", Context.MODE_PRIVATE).edit()
                .putStringSet("delivered", setOf("obsolete", "question:obsolete")).commit()
            ReminderScheduler.resetAfterRestore(context, repository)
            assertTrue(context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
                .getStringSet("delivered", emptySet()).orEmpty().isEmpty())
            assertTrue(repository.getReminderCandidates().any { it.courseId == "course" })

            val afterFirstRestartSync = repository.createBackupSnapshot("ru").sorted()
            repository.synchronize()
            val afterSecondRestartSync = repository.createBackupSnapshot("ru").sorted()
            assertEquals(afterFirstRestartSync, afterSecondRestartSync)
            assertEquals(
                afterSecondRestartSync.sessions.size,
                afterSecondRestartSync.sessions.map { Triple(it.courseId, it.date, it.startMinute) }.toSet().size,
            )
        } finally {
            if (database.isOpen) database.close()
            files.forEach(File::delete)
            context.deleteDatabase(name)
            context.getSharedPreferences("backup_restore", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("reminders", Context.MODE_PRIVATE).edit().remove("delivered").commit()
        }
    }

    private fun repository(database: TrackerDatabase) = TrackerRepository(
        database = database,
        now = { clock },
        zone = { zone },
        retentionPolicy = { RetentionPolicy(7) },
    )

    private fun initialSnapshot() = BackupSnapshot(
        languageTag = "en",
        categories = listOf(CategoryEntity("category", "Languages")),
        courses = listOf(
            CourseEntity("occupied", "Other", 0, 1, 1),
            CourseEntity("course", "Swedish", 1, 2, 2, "category"),
        ),
        topics = listOf(
            TopicEntity("topic-1", "course", 0, "Greetings", true, firstDay.minusDays(4).toEpochDay()),
            TopicEntity("topic-2", "course", 1, "Travel"),
        ),
        schedules = listOf(ScheduleEntity("course", firstDay.toEpochDay(), generatedThrough = firstDay.plusDays(7).toEpochDay())),
        scheduleRules = listOf(ScheduleRuleEntity("course", 2, 9 * 60)),
        sessions = listOf(
            SessionRecord("old-history", "course", firstDay.minusDays(4).toEpochDay(), 9 * 60, 1, 1, result = SessionResult.DONE),
            SessionRecord("future", "course", firstDay.plusDays(1).toEpochDay(), 9 * 60, 2, 2),
        ),
    )

    private fun archive(snapshot: BackupSnapshot, metadata: BackupMetadata): File =
        File.createTempFile("f17-", BackupArchive.EXTENSION, context.cacheDir).also {
            BackupArchive.write(snapshot, it, metadata)
            assertEquals(snapshot.sorted(), BackupArchive.validate(it).snapshot.sorted())
        }

    private fun BackupSnapshot.sorted() = copy(
        categories = categories.sortedBy { it.id },
        courses = courses.sortedBy { it.id },
        topics = topics.sortedBy { it.id },
        schedules = schedules.sortedBy { it.courseId },
        scheduleRules = scheduleRules.sortedWith(compareBy({ it.courseId }, { it.dayOfWeek })),
        sessions = sessions.sortedBy { it.id },
    )

    companion object {
        private val OLD_METADATA = BackupMetadata(
            "11111111-1111-4111-8111-111111111111", "2026-10-05T10:00:00Z", 1, "0.1.0",
        )
        private val NEW_METADATA = BackupMetadata(
            "22222222-2222-4222-8222-222222222222", "2026-10-20T10:00:00Z", 1, "0.1.0",
        )
    }
}
