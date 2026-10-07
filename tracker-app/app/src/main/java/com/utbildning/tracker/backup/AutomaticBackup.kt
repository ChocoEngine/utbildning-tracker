package com.utbildning.tracker.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.utbildning.tracker.data.AppContainer
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
        preferences.edit().putString(KEY_DESTINATION, destination.toString()).remove(KEY_LAST_ERROR).apply()
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

    fun enable() {
        check(preferences.status().destination != null) { "Backup destination is not selected" }
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
        val tree = status.destination ?: return failure(preferences)
        var document: Uri? = null
        return try {
            document = createDocument(tree)
            BackupExporter(applicationContext, AppContainer.repository(applicationContext)).export(document)
            preferences.recordSuccess(System.currentTimeMillis())
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            document?.let(::deleteIncomplete)
            throw cancel
        } catch (_: Exception) {
            document?.let(::deleteIncomplete)
            failure(preferences)
        }
    }

    private fun createDocument(tree: Uri): Uri {
        val resolver = applicationContext.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC).format(Instant.now())
        return DocumentsContract.createDocument(
            resolver,
            parent,
            BackupArchive.MIME,
            "study-tracker-$timestamp${BackupArchive.EXTENSION}",
        ) ?: throw IOException("Storage provider did not create a document")
    }

    private fun deleteIncomplete(document: Uri) {
        runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, document) }
    }

    private fun failure(preferences: AutomaticBackupPreferences): Result {
        preferences.recordError(System.currentTimeMillis())
        return Result.failure()
    }
}
