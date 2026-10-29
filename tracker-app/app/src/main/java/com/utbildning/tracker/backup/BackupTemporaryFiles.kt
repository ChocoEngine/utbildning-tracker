package com.utbildning.tracker.backup

import java.io.File

/** Process-local leases protect running operations while orphan files from prior runs are removed. */
internal object BackupTemporaryFiles {
    private val active = mutableSetOf<File>()
    private val prefixes = setOf("study-tracker-export-", "study-tracker-verify-", "study-tracker-import-")

    @Synchronized fun cleanup(directory: File) {
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file !in active && file.extension == BackupArchive.EXTENSION.removePrefix(".") &&
                prefixes.any { file.name.startsWith(it) }) {
                file.delete() // An unsuccessful deletion is retried at the next operation/start.
            }
        }
    }

    suspend fun <T> withFile(directory: File, prefix: String, block: suspend (File) -> T): T {
        val file = synchronized(this) {
            require(prefix in prefixes)
            cleanup(directory)
            File.createTempFile(prefix, BackupArchive.EXTENSION, directory).also { active += it }
        }
        try {
            return block(file)
        } finally {
            synchronized(this) {
                file.delete()
                active -= file
            }
        }
    }
}
