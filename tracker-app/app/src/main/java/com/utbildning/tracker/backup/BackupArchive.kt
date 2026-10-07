package com.utbildning.tracker.backup

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.utbildning.tracker.data.local.CategoryEntity
import com.utbildning.tracker.data.local.CourseEntity
import com.utbildning.tracker.data.local.ScheduleEntity
import com.utbildning.tracker.data.local.ScheduleRuleEntity
import com.utbildning.tracker.data.local.SessionRecord
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.data.local.TopicEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object BackupArchive {
    const val MIME = "application/vnd.utbildning.tracker.backup+zip"
    const val EXTENSION = ".studytracker-backup"
    private const val MAX_ARCHIVE_BYTES = 50L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_DATA_BYTES = 50 * 1024 * 1024

    fun write(snapshot: BackupSnapshot, target: File, metadata: BackupMetadata): ValidatedBackup {
        val data = encodeData(snapshot)
        require(data.size <= MAX_DATA_BYTES) { "Backup data is too large" }
        val manifest = encodeManifest(metadata, data.size, sha256(data))
        require(manifest.size <= MAX_MANIFEST_BYTES) { "Backup manifest is too large" }
        target.outputStream().buffered().use { output ->
            ZipOutputStream(output).use { zip ->
                writeEntry(zip, "manifest.json", manifest)
                writeEntry(zip, "data.json", data)
            }
        }
        if (target.length() > MAX_ARCHIVE_BYTES) throw BackupFormatException("Backup is too large")
        return validate(target)
    }

    fun validate(file: File): ValidatedBackup {
        if (!file.isFile || file.length() > MAX_ARCHIVE_BYTES) throw BackupFormatException("Invalid backup size")
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList()
                if (entries.size != 2 || entries.any { it.isDirectory || '/' in it.name || '\\' in it.name }) {
                    throw BackupFormatException("Backup must contain exactly manifest.json and data.json")
                }
                val names = entries.map { it.name }
                if (names.toSet() != setOf("manifest.json", "data.json") || names.size != names.toSet().size) {
                    throw BackupFormatException("Unexpected or duplicate backup entry")
                }
                val manifestBytes = readLimited(zip.getInputStream(zip.getEntry("manifest.json")), MAX_MANIFEST_BYTES)
                val dataBytes = readLimited(zip.getInputStream(zip.getEntry("data.json")), MAX_DATA_BYTES)
                val manifest = parseManifest(manifestBytes)
                if (manifest.payloadBytes != dataBytes.size.toLong() || manifest.payloadHash != sha256(dataBytes)) {
                    throw BackupFormatException("Backup payload checksum does not match")
                }
                return ValidatedBackup(manifest.metadata, parseData(dataBytes), manifest.payloadHash)
            }
        } catch (error: BackupFormatException) {
            throw error
        } catch (error: Exception) {
            throw BackupFormatException("Cannot read backup", error)
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun encodeManifest(metadata: BackupMetadata, payloadBytes: Int, payloadHash: String): ByteArray = jsonBytes { json ->
        json.beginObject()
        json.name("format").value("utbildning-tracker-backup")
        json.name("formatVersion").beginObject().name("major").value(1).name("minor").value(0).endObject()
        json.name("backupId").value(metadata.backupId)
        json.name("createdAt").value(metadata.createdAt)
        json.name("app").beginObject().name("versionCode").value(metadata.versionCode).name("versionName").value(metadata.versionName).endObject()
        json.name("databaseSchemaVersion").value(8)
        json.name("requiredFeatures").beginArray().endArray()
        json.name("payload").beginObject()
            .name("path").value("data.json")
            .name("bytes").value(payloadBytes.toLong())
            .name("sha256").value(payloadHash)
            .endObject()
        json.endObject()
    }

    private fun encodeData(snapshot: BackupSnapshot): ByteArray = jsonBytes { json ->
        json.beginObject()
        json.name("settings").beginObject().name("languageTag").value(snapshot.languageTag).endObject()
        json.name("categories").beginArray()
        snapshot.categories.sortedBy { it.id }.forEach { value ->
            json.beginObject().name("id").value(value.id).name("name").value(value.name).endObject()
        }
        json.endArray().name("courses").beginArray()
        snapshot.courses.sortedBy { it.id }.forEach { value ->
            json.beginObject().name("id").value(value.id).name("name").value(value.name)
                .name("colorId").nullable(value.colorId).name("createdAt").value(value.createdAt)
                .name("updatedAt").value(value.updatedAt).name("categoryId").nullable(value.categoryId)
                .name("isCompleted").value(value.isCompleted).name("isPaused").value(value.isPaused)
                .name("completedAt").nullable(value.completedAt).endObject()
        }
        json.endArray().name("topics").beginArray()
        snapshot.topics.sortedBy { it.id }.forEach { value ->
            json.beginObject().name("id").value(value.id).name("courseId").value(value.courseId)
                .name("position").value(value.position.toLong()).name("title").value(value.title)
                .name("isCompleted").value(value.isCompleted).name("completionDate").nullable(value.completionDate).endObject()
        }
        json.endArray().name("schedules").beginArray()
        snapshot.schedules.sortedBy { it.courseId }.forEach { value ->
            json.beginObject().name("courseId").value(value.courseId).name("startsOn").value(value.startsOn)
                .name("endsOn").nullable(value.endsOn).name("generatedThrough").nullable(value.generatedThrough).endObject()
        }
        json.endArray().name("scheduleRules").beginArray()
        snapshot.scheduleRules.sortedWith(compareBy({ it.courseId }, { it.dayOfWeek })).forEach { value ->
            json.beginObject().name("courseId").value(value.courseId).name("dayOfWeek").value(value.dayOfWeek.toLong())
                .name("startMinute").value(value.startMinute.toLong()).name("endMinute").nullable(value.endMinute).endObject()
        }
        json.endArray().name("sessions").beginArray()
        snapshot.sessions.sortedBy { it.id }.forEach { value ->
            json.beginObject().name("id").value(value.id).name("courseId").value(value.courseId)
                .name("date").value(value.date).name("startMinute").value(value.startMinute.toLong())
                .name("createdAt").value(value.createdAt).name("updatedAt").value(value.updatedAt)
                .name("endMinute").nullable(value.endMinute).name("result").value(value.result.name).endObject()
        }
        json.endArray().endObject()
    }

    private fun jsonBytes(block: (JsonWriter) -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        JsonWriter(OutputStreamWriter(output, StandardCharsets.UTF_8)).use { writer ->
            block(writer)
        }
        return output.toByteArray()
    }

    private fun JsonWriter.nullable(value: String?): JsonWriter = if (value == null) nullValue() else value(value)
    private fun JsonWriter.nullable(value: Long?): JsonWriter = if (value == null) nullValue() else value(value)
    private fun JsonWriter.nullable(value: Int?): JsonWriter = if (value == null) nullValue() else value(value.toLong())

    private data class ParsedManifest(val metadata: BackupMetadata, val payloadBytes: Long, val payloadHash: String)

    private fun parseManifest(bytes: ByteArray): ParsedManifest {
        var format: String? = null
        var major: Long? = null
        var minor: Long? = null
        var backupId: String? = null
        var createdAt: String? = null
        var versionCode: Long? = null
        var versionName: String? = null
        var schemaVersion: Long? = null
        var payloadPath: String? = null
        var payloadBytes: Long? = null
        var payloadHash: String? = null
        reader(bytes).use { json ->
            readObject(json, setOf("format", "formatVersion", "backupId", "createdAt", "app", "databaseSchemaVersion", "requiredFeatures", "payload")) { name ->
                when (name) {
                    "format" -> format = readString(json)
                    "formatVersion" -> readObject(json, setOf("major", "minor")) { field -> when (field) {
                        "major" -> major = readLong(json)
                        "minor" -> minor = readLong(json)
                        else -> json.skipValue()
                    } }
                    "backupId" -> backupId = readString(json)
                    "createdAt" -> createdAt = readString(json)
                    "app" -> readObject(json, setOf("versionCode", "versionName")) { field -> when (field) {
                        "versionCode" -> versionCode = readLong(json)
                        "versionName" -> versionName = readString(json)
                        else -> json.skipValue()
                    } }
                    "databaseSchemaVersion" -> schemaVersion = readLong(json)
                    "requiredFeatures" -> {
                        json.beginArray()
                        if (json.hasNext()) throw BackupFormatException("Unsupported required feature")
                        json.endArray()
                    }
                    "payload" -> readObject(json, setOf("path", "bytes", "sha256")) { field -> when (field) {
                        "path" -> payloadPath = readString(json)
                        "bytes" -> payloadBytes = readLong(json)
                        "sha256" -> payloadHash = readString(json)
                        else -> json.skipValue()
                    } }
                    else -> json.skipValue()
                }
            }
            requireEnd(json)
        }
        if (format != "utbildning-tracker-backup" || major != 1L || minor == null || minor!! < 0L || payloadPath != "data.json") {
            throw BackupFormatException("Unsupported backup format")
        }
        if (schemaVersion == null || schemaVersion!! < 1L || payloadBytes == null || payloadBytes!! < 0L || !payloadHash.orEmpty().matches(Regex("[0-9a-f]{64}"))) {
            throw BackupFormatException("Invalid backup manifest")
        }
        try {
            UUID.fromString(backupId)
            OffsetDateTime.parse(createdAt)
        } catch (error: Exception) {
            throw BackupFormatException("Invalid backup identity or timestamp", error)
        }
        return ParsedManifest(
            BackupMetadata(backupId!!, createdAt!!, versionCode ?: throw BackupFormatException("Missing app version"), versionName ?: throw BackupFormatException("Missing app version")),
            payloadBytes!!,
            payloadHash!!,
        )
    }

    private fun parseData(bytes: ByteArray): BackupSnapshot {
        var languageTag: String? = null
        var categories: List<CategoryEntity>? = null
        var courses: List<CourseEntity>? = null
        var topics: List<TopicEntity>? = null
        var schedules: List<ScheduleEntity>? = null
        var rules: List<ScheduleRuleEntity>? = null
        var sessions: List<SessionRecord>? = null
        try {
            reader(bytes).use { json ->
                readObject(json, setOf("settings", "categories", "courses", "topics", "schedules", "scheduleRules", "sessions")) { name ->
                    when (name) {
                        "settings" -> readObject(json, setOf("languageTag")) { field -> if (field == "languageTag") languageTag = readString(json) else json.skipValue() }
                        "categories" -> categories = readArray(json) { readCategory(json) }
                        "courses" -> courses = readArray(json) { readCourse(json) }
                        "topics" -> topics = readArray(json) { readTopic(json) }
                        "schedules" -> schedules = readArray(json) { readSchedule(json) }
                        "scheduleRules" -> rules = readArray(json) { readRule(json) }
                        "sessions" -> sessions = readArray(json) { readSession(json) }
                        else -> json.skipValue()
                    }
                }
                requireEnd(json)
            }
        } catch (error: BackupFormatException) {
            throw error
        } catch (error: Exception) {
            throw BackupFormatException("Invalid backup data", error)
        }
        val snapshot = BackupSnapshot(languageTag!!, categories!!, courses!!, topics!!, schedules!!, rules!!, sessions!!)
        validateRelations(snapshot)
        return snapshot
    }

    private fun readCategory(json: JsonReader): CategoryEntity {
        var id: String? = null; var name: String? = null
        readObject(json, setOf("id", "name")) { field -> when (field) { "id" -> id = readString(json); "name" -> name = readString(json); else -> json.skipValue() } }
        return CategoryEntity(id!!, name!!)
    }

    private fun readCourse(json: JsonReader): CourseEntity {
        var id: String? = null; var name: String? = null; var colorId: Int? = null; var createdAt: Long? = null
        var updatedAt: Long? = null; var categoryId: String? = null; var completed = false; var paused = false; var completedAt: Long? = null
        readObject(json, setOf("id", "name", "colorId", "createdAt", "updatedAt", "categoryId", "isCompleted", "isPaused", "completedAt")) { field -> when (field) {
            "id" -> id = readString(json); "name" -> name = readString(json); "colorId" -> colorId = readNullableInt(json)
            "createdAt" -> createdAt = readLong(json); "updatedAt" -> updatedAt = readLong(json); "categoryId" -> categoryId = readNullableString(json)
            "isCompleted" -> completed = readBoolean(json); "isPaused" -> paused = readBoolean(json); "completedAt" -> completedAt = readNullableLong(json)
            else -> json.skipValue()
        } }
        return CourseEntity(id!!, name!!, colorId, createdAt!!, updatedAt!!, categoryId, completed, paused, completedAt)
    }

    private fun readTopic(json: JsonReader): TopicEntity {
        var id: String? = null; var courseId: String? = null; var position: Int? = null; var title: String? = null; var completed = false; var completionDate: Long? = null
        readObject(json, setOf("id", "courseId", "position", "title", "isCompleted", "completionDate")) { field -> when (field) {
            "id" -> id = readString(json); "courseId" -> courseId = readString(json); "position" -> position = readInt(json)
            "title" -> title = readString(json); "isCompleted" -> completed = readBoolean(json); "completionDate" -> completionDate = readNullableLong(json)
            else -> json.skipValue()
        } }
        return TopicEntity(id!!, courseId!!, position!!, title!!, completed, completionDate)
    }

    private fun readSchedule(json: JsonReader): ScheduleEntity {
        var courseId: String? = null; var startsOn: Long? = null; var endsOn: Long? = null; var generatedThrough: Long? = null
        readObject(json, setOf("courseId", "startsOn", "endsOn", "generatedThrough")) { field -> when (field) {
            "courseId" -> courseId = readString(json); "startsOn" -> startsOn = readLong(json); "endsOn" -> endsOn = readNullableLong(json)
            "generatedThrough" -> generatedThrough = readNullableLong(json); else -> json.skipValue()
        } }
        return ScheduleEntity(courseId!!, startsOn!!, endsOn, generatedThrough)
    }

    private fun readRule(json: JsonReader): ScheduleRuleEntity {
        var courseId: String? = null; var day: Int? = null; var start: Int? = null; var end: Int? = null
        readObject(json, setOf("courseId", "dayOfWeek", "startMinute", "endMinute")) { field -> when (field) {
            "courseId" -> courseId = readString(json); "dayOfWeek" -> day = readInt(json); "startMinute" -> start = readInt(json)
            "endMinute" -> end = readNullableInt(json); else -> json.skipValue()
        } }
        return ScheduleRuleEntity(courseId!!, day!!, start!!, end)
    }

    private fun readSession(json: JsonReader): SessionRecord {
        var id: String? = null; var courseId: String? = null; var date: Long? = null; var start: Int? = null
        var createdAt: Long? = null; var updatedAt: Long? = null; var end: Int? = null; var result: String? = null
        readObject(json, setOf("id", "courseId", "date", "startMinute", "createdAt", "updatedAt", "endMinute", "result")) { field -> when (field) {
            "id" -> id = readString(json); "courseId" -> courseId = readString(json); "date" -> date = readLong(json)
            "startMinute" -> start = readInt(json); "createdAt" -> createdAt = readLong(json); "updatedAt" -> updatedAt = readLong(json)
            "endMinute" -> end = readNullableInt(json); "result" -> result = readString(json); else -> json.skipValue()
        } }
        return SessionRecord(id!!, courseId!!, date!!, start!!, createdAt!!, updatedAt!!, end, SessionResult.valueOf(result!!))
    }

    private fun validateRelations(snapshot: BackupSnapshot) {
        if (snapshot.languageTag !in setOf("", "ru", "en")) throw BackupFormatException("Unsupported language")
        unique(snapshot.categories.map { it.id }, "category")
        unique(snapshot.courses.map { it.id }, "course")
        unique(snapshot.topics.map { it.id }, "topic")
        unique(snapshot.schedules.map { it.courseId }, "schedule")
        unique(snapshot.scheduleRules.map { it.courseId to it.dayOfWeek }, "schedule rule")
        unique(snapshot.sessions.map { it.id }, "session")
        unique(snapshot.sessions.map { Triple(it.courseId, it.date, it.startMinute) }, "session time")
        val categoryIds = snapshot.categories.map { it.id }.toSet()
        val courseIds = snapshot.courses.map { it.id }.toSet()
        val scheduleIds = snapshot.schedules.map { it.courseId }.toSet()
        if (snapshot.courses.any { it.categoryId != null && it.categoryId !in categoryIds } ||
            snapshot.topics.any { it.courseId !in courseIds } || snapshot.schedules.any { it.courseId !in courseIds } ||
            snapshot.scheduleRules.any { it.courseId !in scheduleIds } || snapshot.sessions.any { it.courseId !in courseIds }) {
            throw BackupFormatException("Backup contains a missing reference")
        }
        val active = snapshot.courses.filter { !it.isCompleted }
        if (active.size > 10 || active.mapNotNull { it.colorId }.size != active.mapNotNull { it.colorId }.toSet().size) {
            throw BackupFormatException("Invalid active course colors")
        }
    }

    private fun <T> unique(values: List<T>, label: String) {
        if (values.size != values.toSet().size) throw BackupFormatException("Duplicate $label")
    }

    private fun readObject(json: JsonReader, required: Set<String>, value: (String) -> Unit) {
        json.beginObject()
        val seen = mutableSetOf<String>()
        while (json.hasNext()) {
            val name = json.nextName()
            if (!seen.add(name)) throw BackupFormatException("Duplicate JSON field: $name")
            value(name)
        }
        json.endObject()
        if (!seen.containsAll(required)) throw BackupFormatException("Missing JSON fields: ${required - seen}")
    }

    private fun <T> readArray(json: JsonReader, item: () -> T): List<T> {
        val values = mutableListOf<T>()
        json.beginArray()
        while (json.hasNext()) values += item()
        json.endArray()
        return values
    }

    private fun readString(json: JsonReader): String {
        if (json.peek() != JsonToken.STRING) throw BackupFormatException("Expected string")
        return json.nextString()
    }
    private fun readNullableString(json: JsonReader): String? = if (json.peek() == JsonToken.NULL) { json.nextNull(); null } else readString(json)
    private fun readBoolean(json: JsonReader): Boolean {
        if (json.peek() != JsonToken.BOOLEAN) throw BackupFormatException("Expected boolean")
        return json.nextBoolean()
    }
    private fun readLong(json: JsonReader): Long {
        if (json.peek() != JsonToken.NUMBER) throw BackupFormatException("Expected integer")
        val raw = json.nextString()
        if (!raw.matches(Regex("-?(0|[1-9][0-9]*)"))) throw BackupFormatException("Expected integer")
        return raw.toLongOrNull() ?: throw BackupFormatException("Integer is out of range")
    }
    private fun readInt(json: JsonReader): Int = readLong(json).let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else throw BackupFormatException("Integer is out of range") }
    private fun readNullableLong(json: JsonReader): Long? = if (json.peek() == JsonToken.NULL) { json.nextNull(); null } else readLong(json)
    private fun readNullableInt(json: JsonReader): Int? = if (json.peek() == JsonToken.NULL) { json.nextNull(); null } else readInt(json)
    private fun reader(bytes: ByteArray) = JsonReader(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8)).apply { isLenient = false }
    private fun requireEnd(json: JsonReader) { if (json.peek() != JsonToken.END_DOCUMENT) throw BackupFormatException("Trailing JSON content") }

    private fun readLimited(input: InputStream, limit: Int): ByteArray = input.use { source ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw BackupFormatException("Backup entry is too large")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
