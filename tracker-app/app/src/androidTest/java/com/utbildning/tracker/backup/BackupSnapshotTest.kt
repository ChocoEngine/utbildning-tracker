package com.utbildning.tracker.backup

import android.content.Context
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.room.Room
import androidx.room.withTransaction
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupSnapshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: TrackerDatabase
    private lateinit var repository: TrackerRepository
    private val dao get() = database.trackerDao()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        repository = TrackerRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun snapshotContainsScheduledUnscheduledPausedCompletedHistoryAndSettings() = runBlocking {
        dao.insertCategory(CategoryEntity("category", "Programming"))
        dao.insertCourse(CourseEntity("scheduled", "Scheduled", 0, 1, 2, "category"))
        dao.insertCourse(CourseEntity("paused", "Paused", 1, 3, 4, isPaused = true))
        dao.insertCourse(CourseEntity("completed", "Completed", null, 5, 6, isCompleted = true, completedAt = 6))
        dao.insertTopic(TopicEntity("done-manually", "scheduled", 0, "Pointers", true, null))
        dao.insertTopic(TopicEntity("done-in-session", "scheduled", 1, "Memory", true, 20_000))
        dao.insertSchedule(ScheduleEntity("scheduled", 19_000, 22_000, 21_000))
        dao.insertScheduleRule(ScheduleRuleEntity("scheduled", 7, 23 * 60, 30))
        dao.insertSessionRecord(SessionRecord("history", "scheduled", 20_000, 23 * 60, 10, 20, 30, SessionResult.DONE))

        val snapshot = repository.createBackupSnapshot("en")

        assertEquals("en", snapshot.languageTag)
        assertEquals(setOf("scheduled", "paused", "completed"), snapshot.courses.map { it.id }.toSet())
        assertEquals(setOf("done-manually", "done-in-session"), snapshot.topics.map { it.id }.toSet())
        assertEquals("scheduled", snapshot.schedules.single().courseId)
        assertEquals(30, snapshot.scheduleRules.single().endMinute)
        assertEquals(SessionResult.DONE, snapshot.sessions.single().result)
    }

    @Test fun concurrentTransactionCannotProduceCourseWithoutItsTopic() = runBlocking {
        val inserted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = async(Dispatchers.IO) {
            database.withTransaction {
                dao.insertCourse(CourseEntity("atomic", "Atomic", 0, 1, 1))
                inserted.complete(Unit)
                release.await()
                dao.insertTopic(TopicEntity("atomic-topic", "atomic", 0, "Together"))
            }
        }
        inserted.await()
        val reader = async(Dispatchers.IO) { repository.createBackupSnapshot("en") }
        release.complete(Unit)
        writer.await()
        val snapshot = reader.await()
        val hasCourse = snapshot.courses.any { it.id == "atomic" }
        val hasTopic = snapshot.topics.any { it.id == "atomic-topic" }
        assertTrue("Snapshot must be entirely before or after the concurrent transaction", hasCourse == hasTopic)
    }

    @Test fun exporterWritesAndReopensProviderDocumentWithoutChangingWorkingData() = runBlocking {
        dao.insertCourse(CourseEntity("exported", "Exported", 0, 1, 1))
        dao.insertTopic(TopicEntity("exported-topic", "exported", 0, "Portable"))
        val before = repository.createBackupSnapshot("en")
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "instrumentation-${System.nanoTime()}${BackupArchive.EXTENSION}")
                put(MediaStore.MediaColumns.MIME_TYPE, BackupArchive.MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            },
        )
        assertNotNull(uri)
        val local = java.io.File.createTempFile("provider-export", BackupArchive.EXTENSION, context.cacheDir)
        try {
            BackupExporter(
                context,
                repository,
                languageTag = { "en" },
                now = { 1_781_006_400_000L },
                newId = { "4c5a8cc4-9218-4f95-84ea-c46f32430b3a" },
            ).export(uri!!)
            resolver.openInputStream(uri)!!.use { input -> local.outputStream().use { input.copyTo(it) } }
            assertEquals(before, BackupArchive.validate(local).snapshot)
            assertEquals(before, repository.createBackupSnapshot("en"))
        } finally {
            local.delete()
            resolver.delete(uri!!, null, null)
        }
    }
}
