package com.utbildning.tracker.backup

import android.content.Context
import androidx.room.Room
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
import com.utbildning.tracker.notifications.ReminderScheduler
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: TrackerDatabase
    private lateinit var repository: TrackerRepository
    private val files = mutableListOf<File>()
    private var hadDelivered = false
    private var originalDelivered: Set<String> = emptySet()

    @Before fun setUp() {
        context.getSharedPreferences("backup_restore", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("reminders", Context.MODE_PRIVATE).let {
            hadDelivered = it.contains("delivered")
            originalDelivered = it.getStringSet("delivered", emptySet()).orEmpty().toSet()
        }
        database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        repository = TrackerRepository(database)
    }

    @After fun tearDown() {
        database.close()
        files.forEach(File::delete)
        context.getSharedPreferences("backup_restore", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("reminders", Context.MODE_PRIVATE).edit().apply {
            if (hadDelivered) putStringSet("delivered", originalDelivered) else remove("delivered")
        }.commit()
    }

    @Test fun fullRoundTripReplacesEveryPortableRowWithoutMixing() = runBlocking {
        repository.replaceBackup(oldSnapshot(), "old-backup")
        val wanted = fullSnapshot()
        val archive = archive(wanted)
        val appliedLanguages = mutableListOf<String>()
        val importer = BackupImporter(context, repository) { appliedLanguages += it }

        importer.restore(archive)

        assertEquals(wanted.sorted(), repository.createBackupSnapshot("ru").sorted())
        assertEquals(listOf("ru"), appliedLanguages)
        assertEquals(METADATA.backupId, repository.committedBackupId())
        importer.recoverInterruptedRestore()
        assertEquals("Completed recovery must not run twice", listOf("ru"), appliedLanguages)
    }

    @Test fun corruptionUnsupportedVersionAndMissingReferenceLeaveWorkingDataUntouched() = runBlocking {
        val original = oldSnapshot()
        repository.replaceBackup(original, "old-backup")
        val valid = archive(fullSnapshot())
        val corrupted = rewrite(valid, "data.json") { bytes -> bytes.clone().also { it[it.lastIndex] = (it.last() + 1).toByte() } }
        val unsupported = rewrite(valid, "manifest.json") { bytes ->
            bytes.toString(Charsets.UTF_8).replace("\"major\":1", "\"major\":2").toByteArray()
        }
        val brokenReference = temp("missing-reference")
        expectFailure<BackupFormatException> {
            BackupArchive.write(
                fullSnapshot().copy(topics = listOf(TopicEntity("orphan", "missing", 0, "Orphan"))),
                brokenReference,
                METADATA,
            )
        }

        val importer = BackupImporter(context, repository) { error("post-restore must not run") }
        listOf(corrupted, unsupported, brokenReference).forEach { invalid ->
            expectFailure<BackupFormatException> { importer.restore(invalid) }
            assertEquals(original.sorted(), repository.createBackupSnapshot("en"))
            assertEquals("old-backup", repository.committedBackupId())
        }
    }

    @Test fun roomFailureRollsBackDeletionAndStartupDiscardsUncommittedJournal() = runBlocking {
        val original = oldSnapshot()
        repository.replaceBackup(original, "old-backup")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_restore_session BEFORE INSERT ON sessions BEGIN SELECT RAISE(ABORT, 'injected restore failure'); END",
        )
        var postRestoreCalls = 0
        val importer = BackupImporter(context, repository) { postRestoreCalls++ }

        expectFailure<Exception> { importer.restore(archive(fullSnapshot())) }
        assertEquals(original.sorted(), repository.createBackupSnapshot("en"))
        assertEquals("old-backup", repository.committedBackupId())

        importer.recoverInterruptedRestore()
        importer.recoverInterruptedRestore()
        assertEquals(0, postRestoreCalls)
        assertEquals(original.sorted(), repository.createBackupSnapshot("en"))
    }

    @Test fun committedRestoreResumesSynchronizationOnceAndRepeatedSynchronizationDoesNotDuplicate() = runBlocking {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 10, 7)
        val now = today.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        database.close()
        database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        repository = TrackerRepository(database, now = { now }, zone = { zone })
        repository.replaceBackup(oldSnapshot(), "old-backup")
        val restorable = generatedSnapshot(today)
        val validated = BackupArchive.validate(archive(restorable))
        var firstAttempt = true
        val interrupted = BackupImporter(context, repository) {
            repository.synchronize()
            if (firstAttempt) {
                firstAttempt = false
                throw IOException("process stopped after database commit")
            }
        }

        expectFailure<IOException> { interrupted.restore(validated) }
        val afterInterruptedPostAction = repository.createBackupSnapshot("ru")
        assertTrue(afterInterruptedPostAction.sessions.isNotEmpty())
        interrupted.recoverInterruptedRestore()
        val afterRecovery = repository.createBackupSnapshot("ru")
        interrupted.recoverInterruptedRestore()

        assertEquals(afterInterruptedPostAction, afterRecovery)
        assertEquals(afterRecovery, repository.createBackupSnapshot("ru"))
        assertEquals(afterRecovery.sessions.size, afterRecovery.sessions.map { Triple(it.courseId, it.date, it.startMinute) }.toSet().size)
    }

    @Test fun v1BackupFromOlderDatabaseSchemaIsRestoredByFormatRules() = runBlocking {
        repository.replaceBackup(oldSnapshot(), "old-backup")
        val oldSchemaArchive = rewrite(archive(fullSnapshot()), "manifest.json") { bytes ->
            bytes.toString(Charsets.UTF_8).replace("\"databaseSchemaVersion\":8", "\"databaseSchemaVersion\":1").toByteArray()
        }

        BackupImporter(context, repository) { }.restore(oldSchemaArchive)

        assertEquals(fullSnapshot().sorted(), repository.createBackupSnapshot("ru").sorted())
    }

    @Test fun postRestoreClearsOldDeliveryStateAndBuildsCurrentCalendar() = runBlocking {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 10, 7)
        val now = today.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        database.close()
        database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        repository = TrackerRepository(database, now = { now }, zone = { zone })
        repository.replaceBackup(generatedSnapshot(today), METADATA.backupId)
        val preferences = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        preferences.edit().putStringSet("delivered", setOf("old-session", "question:old-session")).commit()

        ReminderScheduler.resetAfterRestore(context, repository)

        assertTrue(preferences.getStringSet("delivered", emptySet()).orEmpty().isEmpty())
        assertTrue(repository.createBackupSnapshot("ru").sessions.isNotEmpty())
    }

    private fun fullSnapshot() = BackupSnapshot(
        languageTag = "ru",
        categories = listOf(CategoryEntity("category", "Programming"), CategoryEntity("unused", "Unused")),
        courses = listOf(
            CourseEntity("active", "Active", 2, 10, 20, "category"),
            CourseEntity("paused", "Paused", 3, 30, 40, isPaused = true),
            CourseEntity("completed", "Completed", null, 50, 70, isCompleted = true, completedAt = 70),
        ),
        topics = listOf(
            TopicEntity("manual", "active", 0, "A long legacy topic that is restored without UI normalization", true, null),
            TopicEntity("calendar", "active", 1, "Memory", true, 21_000),
            TopicEntity("pending-topic", "active", 2, "Ownership"),
        ),
        schedules = listOf(ScheduleEntity("active", 20_000, 22_000, 21_500)),
        scheduleRules = listOf(ScheduleRuleEntity("active", 1, 600, 30)),
        sessions = listOf(
            SessionRecord("done", "active", 21_000, 600, 100, 110, 30, SessionResult.DONE),
            SessionRecord("skipped", "active", 21_007, 600, 120, 130, null, SessionResult.SKIPPED),
            SessionRecord("pending", "active", 21_014, 600, 140, 140, 690, SessionResult.PENDING),
        ),
    )

    private fun oldSnapshot() = BackupSnapshot(
        languageTag = "en",
        categories = listOf(CategoryEntity("old-category", "Old")),
        courses = listOf(CourseEntity("old-course", "Old course", 0, 1, 1, "old-category")),
        topics = listOf(TopicEntity("old-topic", "old-course", 0, "Keep on failure")),
        schedules = emptyList(), scheduleRules = emptyList(),
        sessions = listOf(SessionRecord("old-session", "old-course", 10, 100, 1, 1)),
    )

    private fun generatedSnapshot(today: LocalDate) = BackupSnapshot(
        languageTag = "ru",
        categories = emptyList(),
        courses = listOf(CourseEntity("generated", "Generated", 4, 1, 1)),
        topics = emptyList(),
        schedules = listOf(ScheduleEntity("generated", today.toEpochDay())),
        scheduleRules = listOf(ScheduleRuleEntity("generated", today.dayOfWeek.value, 9 * 60)),
        sessions = emptyList(),
    )

    private fun BackupSnapshot.sorted() = copy(
        categories = categories.sortedBy { it.id }, courses = courses.sortedBy { it.id },
        topics = topics.sortedBy { it.id }, schedules = schedules.sortedBy { it.courseId },
        scheduleRules = scheduleRules.sortedWith(compareBy({ it.courseId }, { it.dayOfWeek })),
        sessions = sessions.sortedBy { it.id },
    )

    private fun archive(snapshot: BackupSnapshot): File = temp("import").also {
        BackupArchive.write(snapshot, it, METADATA)
    }

    private fun rewrite(source: File, entryName: String, transform: (ByteArray) -> ByteArray): File {
        val target = temp("rewritten")
        ZipFile(source).use { input ->
            ZipOutputStream(target.outputStream()).use { output ->
                input.entries().toList().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                    val bytes = input.getInputStream(entry).use { it.readBytes() }
                    output.write(if (entry.name == entryName) transform(bytes) else bytes)
                    output.closeEntry()
                }
            }
        }
        return target
    }

    private fun temp(prefix: String) = File.createTempFile(prefix, BackupArchive.EXTENSION, context.cacheDir).also(files::add)

    private suspend inline fun <reified T : Throwable> expectFailure(noinline block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }

    companion object {
        private val METADATA = BackupMetadata(
            "4c5a8cc4-9218-4f95-84ea-c46f32430b3a",
            Instant.parse("2026-10-07T12:00:00Z").toString(),
            1,
            "0.1.0",
        )
    }
}
