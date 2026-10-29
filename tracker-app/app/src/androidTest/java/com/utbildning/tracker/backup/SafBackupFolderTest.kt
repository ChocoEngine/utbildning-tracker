package com.utbildning.tracker.backup

import android.app.LocaleManager
import android.content.Context
import android.net.Uri
import android.os.LocaleList
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.MainActivity
import com.utbildning.tracker.SystemScenario
import com.utbildning.tracker.data.AppContainer
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Run with scripts/test-saf-folder.py: verify must run in a new app process. */
@SystemScenario
class SafBackupFolderTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val probe = context.getSharedPreferences("saf_folder_probe", Context.MODE_PRIVATE)
    private val repository = AppContainer.repository(context)
    private var activity: ActivityScenario<MainActivity>? = null

    @Test fun selectedFolderRemainsReadableAndWritableAfterProcessRestart() = runBlocking {
        try {
            when (val phase = InstrumentationRegistry.getArguments().getString("saf_phase")) {
                "prepare" -> prepare()
                "verify" -> verifyAfterRestart()
                "cleanup" -> cleanup()
                "check_cleanup" -> {
                    assertTrue("Probe journal must stay cleared after process exit", probe.all.isEmpty())
                    assertTrue("Default backup settings must stay restored after process exit",
                        context.getSharedPreferences("automatic_backup", Context.MODE_PRIVATE).all.isEmpty())
                }
                else -> error("Use scripts/test-saf-folder.py; unsupported saf_phase=$phase")
            }
        } finally {
            activity?.close()
        }
    }

    private suspend fun prepare() {
        check(probe.all.isEmpty()) { "Previous SAF probe needs cleanup" }
        check(context.getSharedPreferences("automatic_backup", Context.MODE_PRIVATE).all.isEmpty()) {
            "Use an isolated AVD with default backup settings"
        }
        val folder = "StudyTrackerSaf-${UUID.randomUUID()}"
        val locales = context.getSystemService(LocaleManager::class.java)
        check(probe.edit().putString("folder", folder)
            .putString("language", locales.applicationLocales.toLanguageTags()).commit())
        locales.applicationLocales = LocaleList.forLanguageTags("en")
        val course = repository.createCourse("SAF folder probe", repository.availableColors().first(), topics = listOf("Portable topic"))
        check(probe.edit().putString("course", course.id).commit())
        openSettings()
        compose.onNodeWithTag("automatic_backup_destination").performScrollTo().performClick()

        // Create and select a real directory in DocumentsUI, independent of its
        // last-used location. No intent-result injection or pre-granted URI.
        clickSystem("New folder", "Новая папка")
        setSystemFolderName(folder)
        clickSystem("OK", "Create", "ОК", "Создать")
        enterCreatedFolder(folder)
        clickSystem("Use this folder", "Использовать эту папку")
        clickSystem("Allow", "Разрешить")
        compose.waitUntil(15_000) { AutomaticBackupPreferences(context).status().destination != null }
        val tree = AutomaticBackupPreferences(context).status().destination!!
        check(probe.edit().putString("tree", tree.toString()).commit())
        testDirectoryId(tree, folder)
        assertGrant(tree)
        assertEquals(emptyList<Uri>(), documents(tree))

        val document = createBackupThroughUi(tree, emptySet())
        val backup = BackupImporter(context, repository).validate(document)
        assertEquals("SAF folder probe", backup.snapshot.courses.single { it.id == course.id }.name)
        assertEquals(listOf("Portable topic"), backup.snapshot.topics.filter { it.courseId == course.id }.map { it.title })
        check(probe.edit().putString("first", document.toString())
            .putString("payload", backup.payloadSha256).putString("process", processToken)
            .putBoolean("prepared", true).commit())
    }

    private suspend fun verifyAfterRestart() {
        check(probe.getBoolean("prepared", false)) { "Prepare must finish successfully first" }
        assertNotEquals("Activity recreation alone is insufficient", probe.getString("process", null), processToken)
        val tree = Uri.parse(probe.getString("tree", null)!!)
        val first = Uri.parse(probe.getString("first", null)!!)
        val courseId = probe.getString("course", null)!!
        assertEquals(tree, AutomaticBackupPreferences(context).status().destination)
        assertGrant(tree)
        val oldBackup = BackupImporter(context, repository).validate(first)
        assertEquals(probe.getString("payload", null), oldBackup.payloadSha256)
        assertEquals("SAF folder probe", oldBackup.snapshot.courses.single { it.id == courseId }.name)

        val course = repository.getCourse(courseId)!!
        repository.updateCourse(courseId, "SAF after process restart", course.colorId!!)
        openSettings()
        compose.onNodeWithTag("backup_folder", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val second = createBackupThroughUi(tree, setOf(first))
        assertNotEquals(first, second)
        val newBackup = BackupImporter(context, repository).validate(second)
        assertEquals("SAF after process restart", newBackup.snapshot.courses.single { it.id == courseId }.name)
        assertEquals(probe.getString("payload", null), BackupImporter(context, repository).validate(first).payloadSha256)
        assertEquals(setOf(first, second), documents(tree).toSet())
        assertGrant(tree)
    }

    private suspend fun cleanup() {
        val folder = probe.getString("folder", null) ?: return
        check(folder.matches(Regex("StudyTrackerSaf-[0-9a-f-]{36}")))
        val tree = probe.getString("tree", null)?.let(Uri::parse)
        if (tree != null) {
            val documentId = testDirectoryId(tree, folder)
            val directory = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
            assertTrue("Remove only the test-owned folder", DocumentsContract.deleteDocument(context.contentResolver, directory))
            AutomaticBackupManager(context).useDefaultDestination()
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == tree })
        } else {
            // A failed picker interaction may have created an empty directory before
            // granting access. Locate only this UUID directory and remove it if empty.
            val paths = shell("find /storage/emulated/0 -maxdepth 12 -type d -name $folder")
                .lineSequence().filter { it.startsWith("/storage/emulated/0/") && it.endsWith("/$folder") }.toList()
            check(paths.size <= 1)
            for (path in paths) {
                val quoted = "'" + path.replace("'", "'\"'\"'") + "'"
                assertEquals("Remove unselected fixture folder", "", shell("rmdir $quoted").trim())
            }
        }
        probe.getString("course", null)?.let { repository.deleteCourse(it) }
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            LocaleList.forLanguageTags(probe.getString("language", ""))
        assertNull(AutomaticBackupPreferences(context).status().destination)
        // Cleanup has no Activity stop to flush SharedPreferences.apply before
        // instrumentation exits. Commit a barrier so the next run sees defaults.
        check(context.getSharedPreferences("automatic_backup", Context.MODE_PRIVATE).edit().commit())
        check(probe.edit().clear().commit())
    }

    private fun openSettings() {
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("screen_settings").assertIsDisplayed()
    }

    private fun createBackupThroughUi(tree: Uri, before: Set<Uri>): Uri {
        compose.onNodeWithTag("backup_create").performScrollTo().performClick()
        compose.waitUntil(20_000) { documents(tree).toSet().minus(before).size == 1 &&
            compose.onAllNodesWithTag("backup_success").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("backup_success").performScrollTo().assertIsDisplayed()
        return documents(tree).single { it !in before }
    }

    private fun assertGrant(tree: Uri) {
        val grant = context.contentResolver.persistedUriPermissions.singleOrNull { it.uri == tree }
        assertNotNull("The real picker must grant persisted access", grant)
        assertTrue(grant!!.isReadPermission)
        assertTrue(grant.isWritePermission)
    }

    private fun testDirectoryId(tree: Uri, folder: String): String {
        val documentId = DocumentsContract.getTreeDocumentId(tree)
        // A directory at the volume root is primary:NAME, without a slash.
        check(tree.authority == "com.android.externalstorage.documents" &&
            documentId.substringAfter(':').substringAfterLast('/') == folder) {
            "Picker must select exactly the test-owned folder: $tree"
        }
        return documentId
    }

    private fun documents(tree: Uri): List<Uri> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))) }
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) +
            (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    private fun setSystemFolderName(folder: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val field = nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { it.isEditable && it.isVisibleToUser }
            if (field != null && field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, folder)
                })) return
            SystemClock.sleep(100)
        }
        fail("DocumentsUI new-folder field is not editable")
    }

    private fun enterCreatedFolder(folder: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var entered = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val all = nodes(instrumentation.uiAutomation.rootInActiveWindow)
            if (all.any { it.viewIdResourceName?.endsWith(":id/breadcrumb_text") == true && it.text?.toString() == folder }) return
            var item = all.firstOrNull { it.viewIdResourceName == "android:id/title" && it.text?.toString() == folder }
            while (item != null && !item.isClickable) item = item.parent
            if (!entered && item?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) entered = true
            SystemClock.sleep(100)
        }
        fail("DocumentsUI did not open newly created folder $folder")
    }

    private fun clickSystem(vararg labels: String) {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && labels.any { it.equals(node.text?.toString(), true) ||
                    it.equals(node.contentDescription?.toString(), true) }) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var node = find(instrumentation.uiAutomation.rootInActiveWindow)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            SystemClock.sleep(100)
        }
        fun tree(node: AccessibilityNodeInfo?): String = if (node == null) "null" else
            "${node.viewIdResourceName}: ${node.text}: ${node.contentDescription}\n" +
                (0 until node.childCount).joinToString("") { tree(node.getChild(it)) }
        fail("System picker action missing: ${labels.toList()}\n${tree(instrumentation.uiAutomation.rootInActiveWindow)}")
    }

    companion object {
        private val processToken = UUID.randomUUID().toString()
    }
}
