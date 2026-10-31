package com.utbildning.tracker.profiling

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.os.LocaleList
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.MainActivity
import com.utbildning.tracker.SystemScenario
import com.utbildning.tracker.backup.*
import com.utbildning.tracker.data.AppContainer
import com.utbildning.tracker.data.local.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, disposable-AVD-only soak; excluded from ordinary regression runs. */
@SystemScenario
@RunWith(AndroidJUnit4::class)
class ResourceProfilingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val output get() = File(context.getExternalFilesDir(null), "resource-profile").apply { mkdirs() }
    private var showAll = false
    private var currentCycle = 0

    @Test fun runResourceProfile() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("resourceProfile") == "true") { "Explicit resourceProfile=true is required" }
        val avd = shell("getprop ro.boot.qemu.avd_name").trim()
        check(avd == "ResourceProfile_API_36") { "Refusing non-profiling AVD: $avd" }
        check(android.os.Build.VERSION.SDK_INT == 36)
        when (val phase = args.getString("profilePhase")) {
            "seed" -> { seed(); return }
            "pilot", "soak" -> check(context.getSharedPreferences("resource_profile", 0).getBoolean("seeded", false))
            else -> error("Unknown phase $phase")
        }
        val pilot = args.getString("profilePhase") == "pilot"
        val cycles = if (pilot) 12 else 120
        val duration = if (pilot) 0L else 2 * 60 * 60 * 1000L
        event("started", 0, mapOf("pilot" to pilot, "cycles" to cycles, "durationMs" to duration))
        shell("wm user-rotation lock 0")
        val overallDeadline = SystemClock.elapsedRealtime() + 2 * 60 * 60 * 1000L
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            cycle(-1, false)
            heap(if (pilot) "pilot" else "baseline")
            val start = SystemClock.elapsedRealtime()
            val scheduleDuration = if (pilot) 0L else overallDeadline - start - 60_000L
            repeat(cycles) { index ->
                currentCycle = index + 1
                event("cycle_start", currentCycle)
                cycle(index, true)
                event("cycle_done", index + 1)
                if (!pilot && index + 1 == cycles / 2) heap("midpoint")
                val deadline = start + scheduleDuration * (index + 1) / cycles
                while (SystemClock.elapsedRealtime() < deadline) Thread.sleep(minOf(1000L, deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
            }
            if (!pilot) heap("final")
        } catch (error: Throwable) {
            runCatching {
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(output, "failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            event("failed", -1, mapOf("error" to error.toString()))
            throw error
        } finally { shell("wm user-rotation lock 0"); shell("wm user-rotation free") }
        event("completed", cycles)
    }

    private fun cycle(index: Int, extras: Boolean) {
        click("nav_today"); click("nav_calendar"); click("nav_courses")
        if (extras && index % 5 == 0) { click("courses_filter"); showAll = !showAll }
        if (showAll) scroll("course_list_scroll", "course_row_resource-archived-59")
        scroll("course_list_scroll", "course_row_resource-active-0")
        click("course_row_resource-active-0")
        scroll("course_topics_scroll", "topic_resource-topic-0-999")
        scroll("course_topics_scroll", "topic_resource-topic-0-500")
        compose.onNodeWithTag("topic_resource-topic-0-500").performTouchInput { longClick() }
        click("course_cancel")
        click("settings")
        if (extras && index % 10 == 0) {
            step("manual_backup") { compose.onNodeWithTag("backup_create").performScrollTo().performClick() }
            compose.waitUntil(60_000) { compose.onAllNodesWithTag("backup_success").fetchSemanticsNodes().isNotEmpty() }
            step("validate_import") { validateImport() }
            step("open_picker") { compose.onNodeWithTag("backup_restore").performScrollTo().performClick() }
            SystemClock.sleep(1000)
            shell("input keyevent KEYCODE_BACK")
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("screen_settings").fetchSemanticsNodes().isNotEmpty() }
            event("backup_and_import_validation", index + 1)
        }
        if (extras && index % 20 == 0) {
            compose.onNodeWithTag(if (index % 40 == 0) "language_ru" else "language_en").performScrollTo().performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("screen_settings").fetchSemanticsNodes().isNotEmpty() }
        }
        if (extras && index % 10 == 0) {
            shell("wm user-rotation lock 1")
            step("rotation_landscape_idle") { compose.waitForIdle() }
            shell("wm user-rotation lock 0")
            step("rotation_portrait_idle") { compose.waitForIdle() }
        }
        click("back")
        if (extras && index % 10 == 0) {
            shell("input keyevent KEYCODE_HOME")
            SystemClock.sleep(1000)
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("nav_today").fetchSemanticsNodes().isNotEmpty() }
        }
        click("nav_today")
        step("cycle_idle") { compose.waitForIdle() }
    }

    private fun <T> step(name: String, block: () -> T): T {
        event("step_start", currentCycle, mapOf("step" to name))
        val result = block()
        event("step_done", currentCycle, mapOf("step" to name))
        return result
    }

    private fun click(tag: String) = step("click:$tag") {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).performClick()
    }
    private fun scroll(container: String, tag: String) = step("scroll:$tag") {
        var lastError: Throwable? = null
        try {
            compose.waitUntil(20_000) {
                runCatching {
                    val bounds = compose.onNodeWithTag(container).fetchSemanticsNode().boundsInRoot
                    check(bounds.height > 1f) { "No visible viewport: $container bounds=$bounds" }
                    compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag))
                    compose.onNodeWithTag(tag).assertIsDisplayed()
                }.onFailure { lastError = it }.isSuccess
            }
        } catch (error: Throwable) { throw AssertionError("Scroll failed: $tag; $lastError", lastError ?: error) }
    }
    private fun validateImport() = runBlocking {
        val repository = AppContainer.repository(context)
        val candidate = File(output, "candidate${BackupArchive.EXTENSION}")
        try {
            BackupExporter(context, repository).export(Uri.fromFile(candidate))
            val validated = BackupImporter(context, repository).validate(Uri.fromFile(candidate))
            assertEquals(70, validated.snapshot.courses.size)
            assertEquals(16_000, validated.snapshot.topics.size)
            // Discarding this validated value is the pre-restore cancellation path; no DB replacement.
        } finally { candidate.delete() }
    }
    private fun heap(name: String) {
        click("nav_today")
        Runtime.getRuntime().gc(); System.runFinalization(); Runtime.getRuntime().gc()
        SystemClock.sleep(2000)
        event("heap_start", -1, mapOf("name" to name))
        Debug.dumpHprofData(File(output, "$name.hprof").path)
        event("heap_done", -1, mapOf("name" to name))
    }
    private fun event(kind: String, cycle: Int, extra: Map<String, Any> = emptyMap()) {
        val value = JSONObject(mapOf("event" to kind, "cycle" to cycle, "pid" to Process.myPid(),
            "wallMillis" to System.currentTimeMillis(), "elapsedMillis" to SystemClock.elapsedRealtime()) + extra)
        File(output, "events.jsonl").appendText("$value\n")
        println("RESOURCE_PROFILE $value")
    }
    private fun shell(command: String): String {
        return step("shell:$command") {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
    }

    private fun seed() = runBlocking {
        val database = TrackerDatabase.open(context)
        try {
            check(database.trackerDao().getCourses().isEmpty()) { "Refusing to replace an existing course database" }
            val today = LocalDate.now().toEpochDay()
            val timestamp = System.currentTimeMillis()
            database.withTransaction {
                val dao = database.trackerDao()
                repeat(70) { courseIndex ->
                    val active = courseIndex < 10
                    val id = if (active) "resource-active-$courseIndex" else "resource-archived-${courseIndex - 10}"
                    dao.insertCourse(CourseEntity(id, "Resource course $courseIndex", if (active) courseIndex else null,
                        timestamp + courseIndex, timestamp, isCompleted = !active, completedAt = if (active) null else timestamp))
                    repeat(if (active) 1000 else 100) { topic ->
                        dao.insertTopic(TopicEntity("resource-topic-$courseIndex-$topic", id, topic,
                            "Topic $topic: pointers, memory and long course descriptions", !active || topic < 100))
                    }
                    repeat(if (active) 365 else 100) { day ->
                        dao.insertSessionRecord(SessionRecord("history-$courseIndex-$day", id, today - day - 1, 600,
                            timestamp, timestamp, 660, if (day % 3 == 0) SessionResult.SKIPPED else SessionResult.DONE))
                    }
                    if (active) {
                        dao.insertSchedule(ScheduleEntity(id, today - 365, today + 180, today + 90))
                        (1..7).forEach { dao.insertScheduleRule(ScheduleRuleEntity(id, it, 1200, 1260)) }
                        (0..90).forEach { day ->
                            dao.insertSessionRecord(SessionRecord("future-$courseIndex-$day", id, today + day, 1200, timestamp, timestamp, 1260))
                        }
                    }
                }
            }
            context.getSharedPreferences("settings", 0).edit().putBoolean("reminders_enabled", false).commit()
            context.getSharedPreferences("resource_profile", 0).edit().putBoolean("seeded", true).commit()
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("en")
            event("seeded", 0, mapOf("courses" to 70, "topics" to 16000, "sessions" to 10560))
        } finally { database.close() }
    }
}
