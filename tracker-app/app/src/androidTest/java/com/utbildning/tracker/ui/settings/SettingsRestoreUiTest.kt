package com.utbildning.tracker.ui.settings

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.R
import com.utbildning.tracker.backup.BackupArchive
import com.utbildning.tracker.backup.BackupImporter
import com.utbildning.tracker.backup.BackupMetadata
import com.utbildning.tracker.backup.BackupSnapshot
import com.utbildning.tracker.backup.AutomaticBackupStatus
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.CategoryEntity
import com.utbildning.tracker.data.local.CourseEntity
import com.utbildning.tracker.data.local.ScheduleEntity
import com.utbildning.tracker.data.local.ScheduleRuleEntity
import com.utbildning.tracker.data.local.SessionRecord
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.data.local.TopicEntity
import com.utbildning.tracker.data.local.TrackerDatabase
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRestoreUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun confirmationCancelDoesNotRestoreAndExplicitConfirmDoes() {
        var state by mutableStateOf(RestoreUiState.AwaitingConfirmation)
        var confirmed = 0
        compose.setContent {
            TrackerTheme {
                SettingsContent(
                    selectedLanguage = "en",
                    onLanguageSelected = {},
                    onBack = {},
                    restoreState = state,
                    onConfirmRestore = { confirmed++ },
                    onCancelRestore = { state = RestoreUiState.Cancelled },
                )
            }
        }

        compose.onNodeWithTag("restore_cancel").performClick()
        compose.onNodeWithTag("restore_cancelled").performScrollTo().assertIsDisplayed()
        assertEquals(0, confirmed)

        compose.runOnIdle { state = RestoreUiState.AwaitingConfirmation }
        compose.onNodeWithTag("restore_confirm").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun invalidFileAndSuccessHaveDistinctResultsWithoutConfirmation() {
        var state by mutableStateOf(RestoreUiState.InvalidFile)
        compose.setContent {
            TrackerTheme {
                SettingsContent(
                    selectedLanguage = "en",
                    onLanguageSelected = {},
                    onBack = {},
                    restoreState = state,
                )
            }
        }

        compose.onNodeWithTag("restore_invalid").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("restore_confirm").assertDoesNotExist()
        compose.runOnIdle { state = RestoreUiState.Success }
        compose.onNodeWithTag("restore_success").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun restoreAndGuideTextAreAvailableInEnglishAndRussian() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        var language by mutableStateOf("en")
        compose.setContent {
            val localized = base.createConfigurationContext(
                Configuration(base.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(language))
                },
            )
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
            ) {
                TrackerTheme {
                    SettingsContent(
                        selectedLanguage = language,
                        onLanguageSelected = {},
                        onBack = {},
                        onChooseBackup = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Restore backup").performScrollTo().assertIsDisplayed()
        assertEquals(true, base.createConfigurationContext(Configuration().apply {
            setLocales(LocaleList.forLanguageTags("en"))
        }).getString(R.string.guide_backup).contains("confirm the full replacement"))
        compose.runOnIdle { language = "ru" }
        compose.onNodeWithText("Восстановление").performScrollTo().assertIsDisplayed()
        assertEquals(true, base.createConfigurationContext(Configuration().apply {
            setLocales(LocaleList.forLanguageTags("ru"))
        }).getString(R.string.guide_backup).contains("полную замену"))
    }

    @Test
    fun weeklyBackupShowsToggleDestinationSuccessAndError() {
        var enabled = false
        compose.setContent {
            TrackerTheme {
                SettingsContent(
                    selectedLanguage = "en",
                    onLanguageSelected = {},
                    onBack = {},
                    automaticBackup = AutomaticBackupStatus(enabled, android.net.Uri.parse("content://provider/tree/backups"), 1000L, 2000L),
                    onAutomaticBackupChanged = { enabled = it },
                )
            }
        }

        compose.onNodeWithTag("automatic_backup_switch").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("automatic_backup_success").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("automatic_backup_error").performScrollTo().assertIsDisplayed()
        assertTrue(enabled)
    }

    @Test
    fun explicitUiConfirmationRestoresTopicsHistoryCalendarAndLanguage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        val repository = TrackerRepository(database)
        val wanted = BackupSnapshot(
            languageTag = "ru",
            categories = listOf(CategoryEntity("category", "Category")),
            courses = listOf(CourseEntity("course", "Course", 4, 1, 2, "category")),
            topics = listOf(TopicEntity("topic", "course", 0, "Topic", true, 21_000)),
            schedules = listOf(ScheduleEntity("course", 21_000, 22_000, 21_500)),
            scheduleRules = listOf(ScheduleRuleEntity("course", 2, 600, 690)),
            sessions = listOf(SessionRecord("session", "course", 21_000, 600, 1, 2, 690, SessionResult.DONE)),
        )
        val archive = File.createTempFile("ui-restore-", BackupArchive.EXTENSION, context.cacheDir)
        try {
            BackupArchive.write(
                wanted,
                archive,
                BackupMetadata("4c5a8cc4-9218-4f95-84ea-c46f32430b3a", Instant.parse("2026-10-07T12:00:00Z").toString(), 1, "test"),
            )
            runBlocking {
                repository.replaceBackup(
                    BackupSnapshot(
                        "en",
                        emptyList(),
                        listOf(CourseEntity("old", "Old", 0, 1, 1)),
                        emptyList(), emptyList(), emptyList(), emptyList(),
                    ),
                    "old",
                )
            }
            val restoredLanguages = mutableListOf<String>()
            val importer = BackupImporter(context, repository) { restoredLanguages += it }
            val validated = runBlocking { importer.validate(archive) }
            var state by mutableStateOf(RestoreUiState.AwaitingConfirmation)
            compose.setContent {
                TrackerTheme {
                    SettingsContent(
                        selectedLanguage = "en",
                        onLanguageSelected = {},
                        onBack = {},
                        restoreState = state,
                        onConfirmRestore = {
                            runBlocking { importer.restore(validated) }
                            state = RestoreUiState.Success
                        },
                    )
                }
            }

            compose.onNodeWithTag("restore_confirm").performClick()
            compose.onNodeWithTag("restore_success").performScrollTo().assertIsDisplayed()
            assertEquals(wanted, runBlocking { repository.createBackupSnapshot("ru") })
            assertEquals(listOf("ru"), restoredLanguages)
        } finally {
            database.close()
            archive.delete()
        }
    }
}
