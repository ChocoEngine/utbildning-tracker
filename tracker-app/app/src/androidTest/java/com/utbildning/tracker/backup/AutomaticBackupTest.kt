package com.utbildning.tracker.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.CourseEntity
import com.utbildning.tracker.data.local.TrackerDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticBackupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: TrackerDatabase
    private lateinit var repository: TrackerRepository

    @Before fun setUp() {
        context.getSharedPreferences("automatic_backup", Context.MODE_PRIVATE).edit().clear().commit()
        WorkManager.getInstance(context).cancelUniqueWork(AutomaticBackupManager.WORK_NAME).result.get(10, TimeUnit.SECONDS)
        database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        repository = TrackerRepository(database)
    }

    @After fun tearDown() {
        WorkManager.getInstance(context).cancelUniqueWork(AutomaticBackupManager.WORK_NAME).result.get(10, TimeUnit.SECONDS)
        context.getSharedPreferences("automatic_backup", Context.MODE_PRIVATE).edit().clear().commit()
        database.close()
    }

    @Test fun repeatedEnableKeepsOnePeriodicJobAndDisableCancelsIt() {
        val preferences = AutomaticBackupPreferences(context)
        preferences.selectDestination(Uri.parse("content://example/tree/backups"))
        val manager = AutomaticBackupManager(context, preferences)

        manager.enable()
        manager.enable()
        val scheduled = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(AutomaticBackupManager.WORK_NAME).get(10, TimeUnit.SECONDS)
        assertEquals(1, scheduled.count { !it.state.isFinished })
        assertTrue(scheduled.single { !it.state.isFinished }.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING))

        manager.disable()
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(AutomaticBackupManager.WORK_NAME).get(10, TimeUnit.SECONDS)
        assertFalse(manager.status().enabled)
        assertEquals(
            0,
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(AutomaticBackupManager.WORK_NAME)
                .get(10, TimeUnit.SECONDS).count { !it.state.isFinished },
        )
    }

    @Test fun inaccessibleProviderRecordsErrorWithoutForgettingPreviousSuccess() {
        val preferences = AutomaticBackupPreferences(context)
        preferences.selectDestination(Uri.parse("content://missing.provider/tree/backups"))
        preferences.setEnabled(true)
        preferences.recordSuccess(1234L)

        val worker = TestListenableWorkerBuilder<AutomaticBackupWorker>(context).build()
        val result = worker.startWork().get(10, TimeUnit.SECONDS)

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(1234L, preferences.status().lastSuccessAt)
        assertNotNull(preferences.status().lastErrorAt)
    }

    @Test fun automaticArchiveCanBeValidatedAndRestored() = runBlocking {
        val wanted = BackupSnapshot(
            languageTag = "en",
            categories = emptyList(),
            courses = listOf(CourseEntity("automatic", "Automatic", 2, 10, 20)),
            topics = emptyList(), schedules = emptyList(), scheduleRules = emptyList(), sessions = emptyList(),
        )
        repository.replaceBackup(wanted, "wanted")
        val bytes = ByteArrayOutputStream()
        BackupExporter(context, repository).export(
            openOutput = { bytes },
            openInput = { ByteArrayInputStream(bytes.toByteArray()) },
        )
        repository.replaceBackup(
            wanted.copy(courses = listOf(CourseEntity("old", "Old", 1, 1, 1))),
            "old",
        )
        val file = File.createTempFile("automatic-restore-", BackupArchive.EXTENSION, context.cacheDir)
        try {
            file.writeBytes(bytes.toByteArray())
            val importer = BackupImporter(context, repository, postRestore = {})
            importer.restore(importer.validate(file))
            assertEquals(wanted, repository.createBackupSnapshot("en"))
        } finally {
            file.delete()
        }
    }

    @Test fun manualAndAutomaticExportsDoNotWriteConcurrently() = runBlocking {
        repository.replaceBackup(
            BackupSnapshot("en", emptyList(), listOf(CourseEntity("course", "Course", 0, 1, 1)), emptyList(), emptyList(), emptyList(), emptyList()),
            "source",
        )
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = AtomicBoolean(false)
        val firstBytes = ByteArrayOutputStream()
        val secondBytes = ByteArrayOutputStream()
        val exporter = BackupExporter(context, repository)
        val first = async(Dispatchers.Default) {
            exporter.export(
                openOutput = {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(10, TimeUnit.SECONDS))
                    firstBytes
                },
                openInput = { ByteArrayInputStream(firstBytes.toByteArray()) },
            )
        }
        assertTrue(firstEntered.await(10, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) {
            exporter.export(
                openOutput = { secondEntered.set(true); secondBytes },
                openInput = { ByteArrayInputStream(secondBytes.toByteArray()) },
            )
        }
        Thread.sleep(150)
        assertFalse(secondEntered.get())
        releaseFirst.countDown()
        first.await()
        second.await()
        assertTrue(secondEntered.get())
    }
}
