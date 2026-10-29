package com.utbildning.tracker.backup

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.utbildning.tracker.data.AppContainer
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class AutomaticBackupStatus(
    val enabled: Boolean,
    val destination: Uri?,
    val lastSuccessAt: Long?,
    val lastErrorAt: Long?,
)

internal class AutomaticBackupPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun status(): AutomaticBackupStatus = AutomaticBackupStatus(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        destination = preferences.getString(KEY_DESTINATION, null)?.let(Uri::parse),
        lastSuccessAt = preferences.longOrNull(KEY_LAST_SUCCESS),
        lastErrorAt = preferences.longOrNull(KEY_LAST_ERROR),
    )

    fun selectDestination(destination: Uri) {
        val editor = preferences.edit().putString(KEY_DESTINATION, destination.toString()).remove(KEY_LAST_ERROR)
        if (status().destination != destination) editor.remove(KEY_LAST_DOCUMENT)
        editor.apply()
    }

    fun useDefaultDestination() {
        preferences.edit().remove(KEY_DESTINATION).remove(KEY_LAST_DOCUMENT).remove(KEY_LAST_ERROR).apply()
    }

    fun lastDocument(): Uri? = preferences.getString(KEY_LAST_DOCUMENT, null)?.let(Uri::parse)

    fun pendingDocument(): Uri? = preferences.getString(KEY_PENDING_DOCUMENT, null)?.let(Uri::parse)

    fun pendingReady(): Boolean = preferences.getBoolean(KEY_PENDING_READY, false)

    fun setPendingDocument(document: Uri?) {
        val editor = preferences.edit()
        if (document == null) editor.remove(KEY_PENDING_DOCUMENT)
        else editor.putString(KEY_PENDING_DOCUMENT, document.toString())
        editor.remove(KEY_PENDING_READY)
        check(editor.commit()) { "Could not save pending backup URI" }
    }

    fun markPendingReady() {
        check(preferences.edit().putBoolean(KEY_PENDING_READY, true).commit()) { "Could not save validated backup state" }
    }

    fun finishPendingDocument(document: Uri) {
        check(preferences.edit().putString(KEY_LAST_DOCUMENT, document.toString())
            .remove(KEY_PENDING_DOCUMENT).remove(KEY_PENDING_READY).commit()) {
            "Could not save latest backup URI"
        }
    }

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun recordSuccess(at: Long) {
        preferences.edit().putLong(KEY_LAST_SUCCESS, at).remove(KEY_LAST_ERROR).apply()
    }

    fun recordError(at: Long) {
        preferences.edit().putLong(KEY_LAST_ERROR, at).apply()
    }

    private fun android.content.SharedPreferences.longOrNull(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private companion object {
        const val NAME = "automatic_backup"
        const val KEY_ENABLED = "enabled"
        const val KEY_DESTINATION = "destination"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_LAST_DOCUMENT = "last_document"
        const val KEY_PENDING_DOCUMENT = "pending_document"
        const val KEY_PENDING_READY = "pending_ready"
    }
}

private fun recoverPendingBackup(preferences: AutomaticBackupPreferences, storage: AutomaticBackupStorage) {
    val pending = preferences.pendingDocument() ?: return
    if (preferences.pendingReady() && storage.isReadable(pending)) {
        val previous = preferences.lastDocument()
        if (previous != null && previous != pending && !storage.deleteOrMissing(previous)) {
            throw IOException("Could not remove previous weekly backup")
        }
        preferences.finishPendingDocument(pending)
    } else {
        if (!storage.deleteOrMissing(pending)) throw IOException("Could not remove interrupted backup")
        preferences.setPendingDocument(null)
    }
}

internal class AutomaticBackupManager(
    private val context: Context,
    private val preferences: AutomaticBackupPreferences = AutomaticBackupPreferences(context),
    private val workManager: WorkManager = WorkManager.getInstance(context),
) {
    fun status(): AutomaticBackupStatus = preferences.status()

    fun selectDestination(destination: Uri) {
        val previous = preferences.status().destination
        recoverPendingBackup(preferences, AutomaticBackupStorage(context, previous))
        context.contentResolver.takePersistableUriPermission(
            destination,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        preferences.selectDestination(destination)
        if (previous != null && previous != destination) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    previous,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    fun useDefaultDestination() {
        val previous = preferences.status().destination
        recoverPendingBackup(preferences, AutomaticBackupStorage(context, previous))
        preferences.useDefaultDestination()
        if (previous != null) runCatching {
            context.contentResolver.releasePersistableUriPermission(
                previous,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun enable() {
        preferences.setEnabled(true)
        enqueueUnique()
    }

    fun disable() {
        preferences.setEnabled(false)
        workManager.cancelUniqueWork(WORK_NAME)
    }

    fun recordError() {
        preferences.recordError(System.currentTimeMillis())
    }

    fun reconcile() {
        if (preferences.status().enabled) enqueueUnique() else workManager.cancelUniqueWork(WORK_NAME)
    }

    private fun enqueueUnique() {
        val request = PeriodicWorkRequestBuilder<AutomaticBackupWorker>(7, TimeUnit.DAYS).build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        internal const val WORK_NAME = "weekly-backup"
    }
}

class AutomaticBackupWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val preferences = AutomaticBackupPreferences(applicationContext)
        val status = preferences.status()
        if (!status.enabled) return Result.success()
        val storage = AutomaticBackupStorage(applicationContext, status.destination)
        var document: Uri? = null
        return try {
            recoverPendingBackup(preferences, storage)
            document = storage.create()
            preferences.setPendingDocument(document)
            BackupExporter(applicationContext, AppContainer.repository(applicationContext)).export(document)
            storage.publish(document)
            preferences.markPendingReady()
            val previous = preferences.lastDocument()
            if (previous != null && previous != document && !storage.deleteOrMissing(previous)) {
                throw IOException("Could not remove previous weekly backup")
            }
            preferences.finishPendingDocument(document)
            document = null
            preferences.recordSuccess(System.currentTimeMillis())
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            if (!preferences.pendingReady() && document?.let(storage::deleteOrMissing) == true) preferences.setPendingDocument(null)
            throw cancel
        } catch (_: Exception) {
            if (!preferences.pendingReady() && document?.let(storage::deleteOrMissing) == true) preferences.setPendingDocument(null)
            failure(preferences)
        }
    }

    private fun failure(preferences: AutomaticBackupPreferences): Result {
        preferences.recordError(System.currentTimeMillis())
        return Result.failure()
    }
}

/** A new document is verified before the previous weekly document is removed. */
internal class AutomaticBackupStorage(private val context: Context, private val tree: Uri?) {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun create(): Uri {
        val name = "study-tracker-weekly-${UUID.randomUUID()}${BackupArchive.EXTENSION}"
        if (tree != null) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            return DocumentsContract.createDocument(resolver, parent, BackupArchive.MIME, name)
                ?: throw IOException("Storage provider did not create a document")
        }
        return createInDownloads(name)
    }

    fun createManual(): Uri {
        val name = "study-tracker-${LocalDate.now(ZoneOffset.UTC)}-${UUID.randomUUID()}${BackupArchive.EXTENSION}"
        if (tree != null) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            return DocumentsContract.createDocument(resolver, parent, BackupArchive.MIME, name)
                ?: throw IOException("Storage provider did not create a document")
        }
        return createInDownloads(name)
    }

    private fun createInDownloads(name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, BackupArchive.MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Study Tracker/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, values) ?: throw IOException("Could not create backup in Downloads")
    }

    fun publish(document: Uri) {
        if (tree == null) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(document, values, null, null) != 1) throw IOException("Could not publish backup")
        }
    }

    fun isReadable(document: Uri): Boolean = try {
        resolver.openInputStream(document)?.use { it.read() >= 0 } == true
    } catch (_: Exception) {
        false
    }

    fun deleteOrMissing(document: Uri): Boolean = try {
        val deleted = if (document.authority == MediaStore.AUTHORITY) {
            resolver.delete(document, null, null) > 0
        } else {
            DocumentsContract.deleteDocument(resolver, document)
        }
        deleted || resolver.openInputStream(document)?.use { false } != false
    } catch (_: java.io.FileNotFoundException) {
        true
    } catch (_: Exception) {
        false
    }
}
