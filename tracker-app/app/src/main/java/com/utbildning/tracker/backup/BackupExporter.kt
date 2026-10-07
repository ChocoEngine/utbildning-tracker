package com.utbildning.tracker.backup

import android.app.LocaleManager
import android.content.Context
import android.net.Uri
import com.utbildning.tracker.data.TrackerRepository
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BackupExporter(
    private val context: Context,
    private val repository: TrackerRepository,
    private val languageTag: () -> String = {
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    },
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun export(destination: Uri) = exportMutex.withLock {
        val resolver = context.contentResolver
        exportLocked(
            openOutput = { resolver.openOutputStream(destination, "w") ?: throw IOException("Destination is not writable") },
            openInput = { resolver.openInputStream(destination) ?: throw IOException("Destination is not readable") },
        )
    }

    internal suspend fun export(
        openOutput: () -> OutputStream,
        openInput: () -> InputStream,
    ) = exportMutex.withLock {
        exportLocked(openOutput, openInput)
    }

    private suspend fun exportLocked(
        openOutput: () -> OutputStream,
        openInput: () -> InputStream,
    ) {
        withContext(Dispatchers.IO) {
            val archive = File.createTempFile("study-tracker-export-", BackupArchive.EXTENSION, context.cacheDir)
            val verification = File.createTempFile("study-tracker-verify-", BackupArchive.EXTENSION, context.cacheDir)
            try {
                val snapshot = consistentSnapshot()
                val app = context.packageManager.getPackageInfo(context.packageName, 0)
                BackupArchive.write(
                    snapshot,
                    archive,
                    BackupMetadata(newId(), Instant.ofEpochMilli(now()).toString(), app.longVersionCode, app.versionName.orEmpty()),
                )
                copyAndValidate(
                    archive,
                    verification,
                    openOutput = openOutput,
                    openInput = openInput,
                )
            } finally {
                archive.delete()
                verification.delete()
            }
        }
    }

    private suspend fun consistentSnapshot(): BackupSnapshot {
        repeat(2) {
            val before = languageTag()
            val snapshot = repository.createBackupSnapshot(before)
            if (languageTag() == before) return snapshot
        }
        throw IOException("Language changed while creating backup")
    }

    companion object {
        private val exportMutex = Mutex()

        internal fun copyAndValidate(
            source: File,
            verification: File,
            openOutput: () -> OutputStream,
            openInput: () -> InputStream,
        ) {
            openOutput().use { output -> source.inputStream().buffered().use { it.copyTo(output) } }
            openInput().use { input ->
                verification.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 50L * 1024 * 1024) throw IOException("Written backup is too large")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!source.inputStream().use(::digest).contentEquals(verification.inputStream().use(::digest))) {
                throw IOException("Written backup differs from source")
            }
            BackupArchive.validate(verification)
        }

        private fun digest(input: InputStream): ByteArray {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            return digest.digest()
        }
    }
}
