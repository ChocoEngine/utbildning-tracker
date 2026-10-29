package com.utbildning.tracker.backup

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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

    @Test fun defaultWeeklyBackupUsesSharedDownloadsAndCanRemoveItsPreviousFile() {
        val storage = AutomaticBackupStorage(context, null)
        val first = storage.create()
        var second: Uri? = null
        try {
            context.contentResolver.openOutputStream(first, "w")!!.use { it.write("first".toByteArray()) }
            storage.publish(first)
            second = storage.create()
            context.contentResolver.openOutputStream(second, "w")!!.use { it.write("second".toByteArray()) }
            storage.publish(second)
            context.contentResolver.query(second, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals("Download/Study Tracker/", it.getString(0))
            }
            assertTrue(storage.deleteOrMissing(first))
            assertEquals("second", context.contentResolver.openInputStream(second)!!.bufferedReader().use { it.readText() })
        } finally {
            storage.deleteOrMissing(first)
            second?.let(storage::deleteOrMissing)
        }
    }

    @Test fun manualBackupInDefaultFolderCanBeReadForRestore() = runBlocking {
        val storage = AutomaticBackupStorage(context, null)
        val document = storage.createManual()
        try {
            BackupExporter(context, repository).export(document)
            storage.publish(document)
            val validated = BackupImporter(context, repository, postRestore = {}).validate(document)
            assertNotNull(validated)
        } finally {
            storage.deleteOrMissing(document)
        }
    }

    @Test fun twoWorkerRunsKeepOnlyTheLatestWeeklyCopy() {
        val preferences = AutomaticBackupPreferences(context)
        val storage = AutomaticBackupStorage(context, null)
        preferences.setEnabled(true)
        var first: Uri? = null
        var second: Uri? = null
        try {
            assertEquals(ListenableWorker.Result.success(),
                TestListenableWorkerBuilder<AutomaticBackupWorker>(context).build().startWork().get(30, TimeUnit.SECONDS))
            first = preferences.lastDocument()
            assertNotNull(first)
            assertEquals(ListenableWorker.Result.success(),
                TestListenableWorkerBuilder<AutomaticBackupWorker>(context).build().startWork().get(30, TimeUnit.SECONDS))
            second = preferences.lastDocument()
            assertNotNull(second)
            assertFalse(first == second)
            assertTrue(runCatching { context.contentResolver.openInputStream(first!!)?.use { it.read() } }.isFailure)
            assertNotNull(context.contentResolver.openInputStream(second!!)?.use { it.read() })
        } finally {
            first?.let(storage::deleteOrMissing)
            second?.let(storage::deleteOrMissing)
        }
    }

    @Test fun manualCopyDoesNotReplaceWeeklyCopy() = runBlocking {
        val preferences = AutomaticBackupPreferences(context)
        val storage = AutomaticBackupStorage(context, null)
        preferences.setEnabled(true)
        var firstWeekly: Uri? = null
        var manual: Uri? = null
        var secondWeekly: Uri? = null
        try {
            assertEquals(ListenableWorker.Result.success(),
                TestListenableWorkerBuilder<AutomaticBackupWorker>(context).build().startWork().get(30, TimeUnit.SECONDS))
            firstWeekly = preferences.lastDocument()
            assertNotNull(firstWeekly)

            val manualDocument = storage.createManual()
            manual = manualDocument
            BackupExporter(context, repository).export(manualDocument)
            storage.publish(manualDocument)
            assertEquals(firstWeekly, preferences.lastDocument())
            assertNotNull(context.contentResolver.openInputStream(firstWeekly!!)?.use { it.read() })
            assertNotNull(context.contentResolver.openInputStream(manualDocument)?.use { it.read() })

            assertEquals(ListenableWorker.Result.success(),
                TestListenableWorkerBuilder<AutomaticBackupWorker>(context).build().startWork().get(30, TimeUnit.SECONDS))
            secondWeekly = preferences.lastDocument()
            assertNotNull(secondWeekly)
            assertFalse(secondWeekly == firstWeekly)
            assertNotNull(context.contentResolver.openInputStream(manualDocument)?.use { it.read() })
        } finally {
            firstWeekly?.let(storage::deleteOrMissing)
            manual?.let(storage::deleteOrMissing)
            secondWeekly?.let(storage::deleteOrMissing)
        }
    }

    @Test fun interruptedRotationKeepsValidatedCopyWhenOldFileIsAlreadyGone() = runBlocking {
        val preferences = AutomaticBackupPreferences(context)
        val storage = AutomaticBackupStorage(context, null)
        preferences.setEnabled(true)
        var first: Uri? = null
        var pending: Uri? = null
        var final: Uri? = null
        try {
            assertEquals(ListenableWorker.Result.success(),
                TestListenableWorkerBuilder<AutomaticBackupWorker>(context).build().startWork().get(30, TimeUnit.SECONDS))
            first = preferences.lastDocument()
            pending = storage.create()
            preferences.setPendingDocument(pending)
            BackupExporter(context, repository).export(pending)
            storage.publish(pending)
            preferences.markPendingReady()
            assertTrue(storage.deleteOrMissing(first!!))

            assertEquals(ListenableWorker.Result.success(),
                TestListenableWorkerBuilder<AutomaticBackupWorker>(context).build().startWork().get(30, TimeUnit.SECONDS))
            final = preferences.lastDocument()
            assertNotNull(final)
            assertTrue(context.contentResolver.openInputStream(final!!)?.use { it.read() >= 0 } == true)
            assertEquals(null, preferences.pendingDocument())
        } finally {
            first?.let(storage::deleteOrMissing)
            pending?.let(storage::deleteOrMissing)
            final?.let(storage::deleteOrMissing)
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
