package com.utbildning.tracker.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.local.CategoryEntity
import com.utbildning.tracker.data.local.CourseEntity
import com.utbildning.tracker.data.local.ScheduleEntity
import com.utbildning.tracker.data.local.ScheduleRuleEntity
import com.utbildning.tracker.data.local.SessionRecord
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.data.local.TopicEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupArchiveTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun archiveRoundTripPreservesEveryPortableField() {
        val snapshot = fullSnapshot()
        val file = temp("round-trip")
        try {
            val validated = BackupArchive.write(snapshot, file, metadata())
            assertEquals(metadata(), validated.metadata)
            assertEquals(
                snapshot.copy(
                    categories = snapshot.categories.sortedBy { it.id },
                    courses = snapshot.courses.sortedBy { it.id },
                    topics = snapshot.topics.sortedBy { it.id },
                    schedules = snapshot.schedules.sortedBy { it.courseId },
                    scheduleRules = snapshot.scheduleRules.sortedWith(compareBy({ it.courseId }, { it.dayOfWeek })),
                    sessions = snapshot.sessions.sortedBy { it.id },
                ),
                validated.snapshot,
            )
        } finally {
            file.delete()
        }
    }

    @Test fun copiedArchiveIsCheckedAgainstTheCompletedSource() {
        val source = temp("source")
        val verification = temp("verification")
        try {
            BackupArchive.write(fullSnapshot(), source, metadata())
            val destination = ByteArrayOutputStream()
            BackupExporter.copyAndValidate(
                source,
                verification,
                openOutput = { destination },
                openInput = { ByteArrayInputStream(destination.toByteArray()) },
            )
            assertEquals(source.readBytes().toList(), destination.toByteArray().toList())

            assertThrows(IOException::class.java) {
                BackupExporter.copyAndValidate(
                    source,
                    verification,
                    openOutput = { ByteArrayOutputStream() },
                    openInput = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                )
            }
        } finally {
            source.delete()
            verification.delete()
        }
    }

    @Test fun destinationRefusalAndWriteFailureAreNotSuccess() {
        val source = temp("source-failure")
        val verification = temp("verification-failure")
        try {
            BackupArchive.write(fullSnapshot(), source, metadata())
            val original = source.readBytes()
            assertThrows(SecurityException::class.java) {
                BackupExporter.copyAndValidate(source, verification, { throw SecurityException("denied") }, { source.inputStream() })
            }
            assertThrows(IOException::class.java) {
                BackupExporter.copyAndValidate(
                    source,
                    verification,
                    openOutput = { object : OutputStream() { override fun write(value: Int) = throw IOException("disk full") } },
                    openInput = { source.inputStream() },
                )
            }
            assertEquals(original.toList(), source.readBytes().toList())
        } finally {
            source.delete()
            verification.delete()
        }
    }

    private fun fullSnapshot() = BackupSnapshot(
        languageTag = "ru",
        categories = listOf(CategoryEntity("category", "Programming")),
        courses = listOf(
            CourseEntity("active", "Active", 2, 10, 20, "category", false, false, null),
            CourseEntity("paused", "Paused", 3, 30, 40, null, false, true, null),
            CourseEntity("completed", "Completed", null, 50, 70, null, true, false, 70),
        ),
        topics = listOf(
            TopicEntity("topic-1", "active", 0, "Pointers", true, 21_000),
            TopicEntity("topic-2", "active", 1, "Memory", true, null),
        ),
        schedules = listOf(ScheduleEntity("active", 20_000, 22_000, 21_500)),
        scheduleRules = listOf(ScheduleRuleEntity("active", 1, 600, 690)),
        sessions = listOf(
            SessionRecord("done", "active", 21_000, 600, 100, 110, 690, SessionResult.DONE),
            SessionRecord("skipped", "active", 21_007, 600, 120, 130, null, SessionResult.SKIPPED),
            SessionRecord("pending", "active", 21_014, 600, 140, 140, 690, SessionResult.PENDING),
        ),
    )

    private fun metadata() = BackupMetadata(
        backupId = "4c5a8cc4-9218-4f95-84ea-c46f32430b3a",
        createdAt = "2026-10-07T12:00:00Z",
        versionCode = 1,
        versionName = "0.1.0",
    )

    private fun temp(name: String) = java.io.File.createTempFile(name, BackupArchive.EXTENSION, context.cacheDir)
}
