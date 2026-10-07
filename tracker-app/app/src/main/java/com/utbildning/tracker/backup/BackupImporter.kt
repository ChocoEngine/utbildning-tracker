package com.utbildning.tracker.backup

import android.app.LocaleManager
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.LocaleList
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.notifications.ReminderScheduler
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Validates first, atomically replaces portable rows, and resumes post-commit work after a crash. */
class BackupImporter(
    context: Context,
    private val repository: TrackerRepository,
    private val postRestore: suspend (String) -> Unit = { languageTag ->
        val app = context.applicationContext
        app.getSystemService(LocaleManager::class.java).applicationLocales =
            LocaleList.forLanguageTags(languageTag)
        ReminderScheduler.resetAfterRestore(app, repository)
    },
) {
    private val app = context.applicationContext
    private val journal = RestoreJournal(app)

    suspend fun validate(file: File): ValidatedBackup = withContext(Dispatchers.IO) {
        BackupArchive.validate(file)
    }

    /** Copies a picker URI into private storage so validation never depends on a long-lived URI grant. */
    suspend fun validate(uri: Uri): ValidatedBackup = withContext(Dispatchers.IO) {
        val staged = File.createTempFile("study-tracker-import-", BackupArchive.EXTENSION, app.cacheDir)
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_ARCHIVE_BYTES) throw IOException("Backup is too large")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("Backup cannot be read")
            BackupArchive.validate(staged)
        } finally {
            staged.delete()
        }
    }

    /** Call only after validation and any caller-owned confirmation step. */
    suspend fun restore(backup: ValidatedBackup) = restoreMutex.withLock {
        withContext(Dispatchers.IO) {
            val prepared = PreparedRestore(
                backupId = backup.metadata.backupId,
                payloadSha256 = backup.payloadSha256,
                languageTag = backup.snapshot.languageTag,
            )
            journal.write(prepared)
            repository.replaceBackup(backup.snapshot, backup.metadata.backupId)
            finish(prepared)
        }
    }

    suspend fun restore(file: File) {
        val backup = validate(file)
        restore(backup)
    }

    /** Safe to call on every start, before reminder observation begins. */
    suspend fun recoverInterruptedRestore() = restoreMutex.withLock {
        withContext(Dispatchers.IO) {
            val prepared = journal.read() ?: return@withContext
            if (repository.committedBackupId() != prepared.backupId) {
                journal.clear()
                return@withContext
            }
            finish(prepared)
        }
    }

    private suspend fun finish(prepared: PreparedRestore) {
        postRestore(prepared.languageTag)
        journal.clear()
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 50L * 1024 * 1024
        private val restoreMutex = Mutex()
    }
}

private data class PreparedRestore(
    val backupId: String,
    val payloadSha256: String,
    val languageTag: String,
)

/** One committed SharedPreferences edit is the durable PREPARED journal record. */
@SuppressLint("UseKtx") // commit() is required: PREPARED must be durable before Room changes.
private class RestoreJournal(context: Context) {
    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun write(value: PreparedRestore) {
        check(preferences.edit().clear()
            .putString(KEY_STATE, STATE_PREPARED)
            .putString(KEY_ID, value.backupId)
            .putString(KEY_HASH, value.payloadSha256)
            .putString(KEY_LANGUAGE, value.languageTag)
            .commit()) { "Cannot persist restore journal" }
    }

    fun read(): PreparedRestore? {
        if (preferences.getString(KEY_STATE, null) != STATE_PREPARED) return null
        val id = preferences.getString(KEY_ID, null)
        val hash = preferences.getString(KEY_HASH, null)
        val language = preferences.getString(KEY_LANGUAGE, null)
        if (id.isNullOrBlank() || hash?.matches(Regex("[0-9a-f]{64}")) != true || language !in setOf("", "ru", "en")) {
            clear()
            return null
        }
        return PreparedRestore(id, hash, language!!)
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Cannot clear restore journal" }
    }

    companion object {
        private const val NAME = "backup_restore"
        private const val STATE_PREPARED = "PREPARED"
        private const val KEY_STATE = "state"
        private const val KEY_ID = "backupId"
        private const val KEY_HASH = "payloadSha256"
        private const val KEY_LANGUAGE = "languageTag"
    }
}
