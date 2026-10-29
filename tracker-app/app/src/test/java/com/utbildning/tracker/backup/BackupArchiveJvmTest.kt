package com.utbildning.tracker.backup

import com.utbildning.tracker.data.local.CategoryEntity
import com.utbildning.tracker.data.local.CourseEntity
import com.utbildning.tracker.data.local.ScheduleEntity
import com.utbildning.tracker.data.local.ScheduleRuleEntity
import com.utbildning.tracker.data.local.SessionRecord
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.data.local.TopicEntity
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupArchiveJvmTest {
    private val metadata = BackupMetadata("4c5a8cc4-9218-4f95-84ea-c46f32430b3a", "2026-10-07T12:00:00Z", 1, "0.1.0")

    // Keep this frozen input independent of the current writer when the export
    // format or Room schema changes; it is the v1 reader compatibility guard.
    @Test fun savedV1FixtureRestoresEveryPortableField() = withFixture { file ->
        val result = BackupArchive.validate(file)
        assertEquals(metadata, result.metadata)
        assertEquals(
            BackupSnapshot(
                "ru",
                listOf(CategoryEntity("category", "Programming"), CategoryEntity("unused", "Unused")),
                listOf(
                    CourseEntity("active", "Active", 2, 10, 20, "category", false, false, null),
                    CourseEntity("completed", "Completed", null, 50, 70, null, true, false, 70),
                    CourseEntity("paused", "Paused", 3, 30, 40, null, false, true, null),
                ),
                listOf(
                    TopicEntity("calendar", "active", 1, "Memory", true, 21_000),
                    TopicEntity("manual", "active", 0, "A long legacy topic", true, null),
                    TopicEntity("pending-topic", "active", 2, "Ownership", false, null),
                ),
                listOf(ScheduleEntity("active", 20_000, 22_000, 21_500)),
                listOf(ScheduleRuleEntity("active", 1, 600, 30)),
                listOf(
                    SessionRecord("done", "active", 21_000, 600, 100, 110, 30, SessionResult.DONE),
                    SessionRecord("pending", "active", 21_014, 600, 140, 140, 690, SessionResult.PENDING),
                    SessionRecord("skipped", "active", 21_007, 600, 120, 130, null, SessionResult.SKIPPED),
                ),
            ),
            result.snapshot,
        )
    }

    @Test fun currentWriterRoundTripsTheFixtureSnapshot() = withFixture { fixture ->
        val original = BackupArchive.validate(fixture)
        withTemp { target ->
            val rewritten = BackupArchive.write(original.snapshot, target, original.metadata)
            assertEquals(original.snapshot, rewritten.snapshot)
            assertEquals(original.metadata, rewritten.metadata)
        }
    }

    @Test fun truncatedZipIsRejected() = withFixture { file ->
        withTemp { target ->
            target.writeBytes(file.readBytes().dropLast(22).toByteArray())
            rejects(target)
        }
    }

    @Test fun damagedZipPayloadIsRejected() = withFixture { file ->
        withTemp { target ->
            val bytes = file.readBytes()
            val index = bytes.indexOfSlice("manifest.json".toByteArray())
            bytes[index + 14] = (bytes[index + 14].toInt() xor 0x7f).toByte()
            target.writeBytes(bytes)
            rejects(target)
        }
    }

    @Test fun repeatedZipEntryIsRejected() = withFixture { file ->
        withTemp { target ->
            rewrite(file, target, extra = "data.json" to "{}".toByteArray())
            rejects(target)
        }
    }

    @Test fun unsupportedVersionIsRejected() = withFixture { file ->
        mutateManifest(file, "\"major\":1", "\"major\":2")
    }

    @Test fun unknownRequiredFeatureIsRejected() = withFixture { file ->
        mutateManifest(file, "\"requiredFeatures\":[]", "\"requiredFeatures\":[\"future-feature\"]")
    }

    @Test fun newerMinorAndDiagnosticDatabaseSchemaRemainReadable() = withFixture { file ->
        val manifest = entry(file, "manifest.json").toString(Charsets.UTF_8)
            .replace("\"minor\":0", "\"minor\":99")
            .replace(Regex("\"databaseSchemaVersion\":[0-9]+"), "\"databaseSchemaVersion\":999")
        withTemp { target ->
            rewrite(file, target, manifest = manifest.toByteArray())
            assertEquals(BackupArchive.validate(file).snapshot, BackupArchive.validate(target).snapshot)
        }
    }

    @Test fun unknownOptionalManifestAndDataFieldsAreIgnored() = withFixture { file ->
        withTemp { target ->
            val manifest = entry(file, "manifest.json").toString(Charsets.UTF_8)
            rewrite(file, target, manifest = manifest.replaceFirst("{", "{\"future\":{\"nested\":[1,true]},").toByteArray())
            assertEquals(BackupArchive.validate(file).snapshot, BackupArchive.validate(target).snapshot)
        }
        mutateData(file, "{", "{\"future\":{\"nested\":[1,true]},", expectValid = true)
    }

    @Test fun duplicateKnownJsonFieldIsRejectedEvenWithMatchingChecksum() = withFixture { file ->
        mutateData(file, "\"languageTag\":\"ru\"", "\"languageTag\":\"ru\",\"languageTag\":\"en\"")
    }

    @Test fun invalidSessionResultIsRejectedEvenWithMatchingChecksum() = withFixture { file ->
        mutateData(file, "\"result\":\"PENDING\"", "\"result\":\"UNKNOWN\"")
    }

    @Test fun missingRequiredDataFieldIsRejectedEvenWithMatchingChecksum() = withFixture { file ->
        mutateData(file, "\"languageTag\":\"ru\"", "\"optionalLanguage\":\"ru\"")
    }

    @Test fun incorrectPayloadSizeIsRejected() = withFixture { file ->
        val manifest = entry(file, "manifest.json").toString(Charsets.UTF_8)
        val bytes = Regex("\"bytes\":([0-9]+)").find(manifest)!!.groupValues[1]
        mutateManifest(file, "\"bytes\":$bytes", "\"bytes\":${bytes.toLong() + 1}")
    }

    @Test fun incorrectSha256IsRejected() = withFixture { file ->
        val manifest = entry(file, "manifest.json").toString(Charsets.UTF_8)
        val hash = Regex("\"sha256\":\"([0-9a-f]{64})\"").find(manifest)!!.groupValues[1]
        mutateManifest(file, hash, "0".repeat(64))
    }

    @Test fun duplicateIdIsRejected() = withFixture { file ->
        mutateData(file, "\"id\":\"unused\"", "\"id\":\"category\"")
    }

    @Test fun emptyIdIsRejected() = withFixture { file ->
        mutateData(file, "\"id\":\"unused\"", "\"id\":\"\"")
    }

    @Test fun missingReferenceIsRejected() = withFixture { file ->
        mutateData(file, "\"courseId\":\"active\"", "\"courseId\":\"missing\"")
    }

    @Test fun oversizedDecompressedManifestIsRejected() = withFixture { file ->
        withTemp { target ->
            rewrite(file, target, manifest = ByteArray(65 * 1024) { ' '.code.toByte() })
            rejects(target)
        }
    }

    @Test fun oversizedDecompressedDataIsRejected() = withFixture { file ->
        withTemp { target ->
            rewrite(file, target, data = ByteArray(50 * 1024 * 1024 + 1) { ' '.code.toByte() })
            rejects(target)
        }
    }

    private fun mutateManifest(file: File, before: String, after: String) = withTemp { target ->
        val manifest = entry(file, "manifest.json").toString(Charsets.UTF_8)
        check(before in manifest)
        rewrite(file, target, manifest = manifest.replaceFirst(before, after).toByteArray())
        rejects(target)
    }

    private fun mutateData(file: File, before: String, after: String, expectValid: Boolean = false) = withTemp { target ->
        val data = entry(file, "data.json").toString(Charsets.UTF_8)
        check(before in data)
        val altered = data.replaceFirst(before, after).toByteArray()
        val manifest = entry(file, "manifest.json").toString(Charsets.UTF_8)
            .replace(Regex("\"bytes\":[0-9]+"), "\"bytes\":${altered.size}")
            .replace(Regex("\"sha256\":\"[0-9a-f]{64}\""), "\"sha256\":\"${sha256(altered)}\"")
        rewrite(file, target, manifest = manifest.toByteArray(), data = altered)
        if (expectValid) assertEquals(BackupArchive.validate(file).snapshot, BackupArchive.validate(target).snapshot)
        else rejects(target)
    }

    private fun rewrite(source: File, target: File, manifest: ByteArray = entry(source, "manifest.json"),
        data: ByteArray = entry(source, "data.json"), extra: Pair<String, ByteArray>? = null) {
        ZipOutputStream(target.outputStream()).use { zip ->
            listOf("manifest.json" to manifest, "data.json" to data).plus(listOfNotNull(extra)).forEach { (name, bytes) ->
                if (extra?.first == name && name == "data.json") {
                    // ZipOutputStream rejects duplicate names; patch a same-length placeholder below.
                    if (bytes === extra.second) zip.putNextEntry(ZipEntry("temp.json")) else zip.putNextEntry(ZipEntry(name))
                } else zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        if (extra?.first == "data.json") {
            val bytes = target.readBytes()
            val old = "temp.json".toByteArray()
            val replacement = "data.json".toByteArray()
            var start = 0
            while (true) {
                val index = bytes.indexOfSlice(old, start)
                if (index < 0) break
                replacement.copyInto(bytes, index)
                start = index + old.size
            }
            target.writeBytes(bytes)
        }
    }

    private fun entry(file: File, name: String): ByteArray = ZipFile(file).use { zip ->
        zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
    }

    private fun rejects(file: File) {
        assertThrows(BackupFormatException::class.java) { BackupArchive.validate(file) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ByteArray.indexOfSlice(needle: ByteArray, from: Int = 0): Int {
        for (i in from..size - needle.size) if (needle.indices.all { this[i + it] == needle[it] }) return i
        return -1
    }

    private fun withFixture(block: (File) -> Unit) = withTemp { file ->
        javaClass.getResourceAsStream("/backup/v1-synthetic.studytracker-backup")!!.use { input -> file.outputStream().use(input::copyTo) }
        block(file)
    }

    private fun withTemp(block: (File) -> Unit) {
        val file = File.createTempFile("backup-jvm-", BackupArchive.EXTENSION)
        try { block(file) } finally { file.delete() }
    }
}
