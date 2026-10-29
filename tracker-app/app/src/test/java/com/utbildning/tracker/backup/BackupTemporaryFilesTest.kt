package com.utbildning.tracker.backup

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupTemporaryFilesTest {
    @Test fun cleanupRemovesOnlyOrphanBackupFiles() = withDirectory { directory ->
        val orphan = File(directory, "study-tracker-import-old${BackupArchive.EXTENSION}").apply { writeText("partial") }
        val unrelated = File(directory, "other${BackupArchive.EXTENSION}").apply { writeText("keep") }
        val otherExtension = File(directory, "study-tracker-export-other.txt").apply { writeText("keep") }
        BackupTemporaryFiles.cleanup(directory)
        assertFalse(orphan.exists()); assertTrue(unrelated.exists()); assertTrue(otherExtension.exists())
    }

    @Test fun concurrentCleanupPreservesEveryActiveOperation() = withDirectory { directory -> runBlocking {
        BackupTemporaryFiles.withFile(directory, "study-tracker-export-") { first ->
            BackupTemporaryFiles.withFile(directory, "study-tracker-import-") { second ->
                BackupTemporaryFiles.cleanup(directory)
                assertTrue(first.exists()); assertTrue(second.exists())
            }
            assertTrue(first.exists())
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    } }

    @Test fun exceptionReleasesAndDeletesFile() = withDirectory { directory ->
        assertThrows(IllegalStateException::class.java) { runBlocking {
            BackupTemporaryFiles.withFile(directory, "study-tracker-import-") { error("read failed") }
        } }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun cancellationReleasesAndDeletesFile() = withDirectory { directory ->
        assertThrows(CancellationException::class.java) { runBlocking {
            BackupTemporaryFiles.withFile(directory, "study-tracker-import-") { throw CancellationException("cancel") }
        } }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun secondFileCreationFailureStillDeletesFirst() = withDirectory { directory ->
        assertThrows(java.io.IOException::class.java) { runBlocking {
            BackupTemporaryFiles.withFile(directory, "study-tracker-export-") { first ->
                BackupTemporaryFiles.withFile(first, "study-tracker-verify-") { fail("Not a directory") }
            }
        } }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("backup-cleanup-test-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
