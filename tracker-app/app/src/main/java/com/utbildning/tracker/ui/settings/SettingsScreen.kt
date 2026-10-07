package com.utbildning.tracker.ui.settings

import android.app.LocaleManager
import android.os.LocaleList
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import com.utbildning.tracker.notifications.ReminderScheduler
import com.utbildning.tracker.backup.BackupArchive
import com.utbildning.tracker.backup.BackupExporter
import com.utbildning.tracker.backup.BackupImporter
import com.utbildning.tracker.backup.ValidatedBackup
import com.utbildning.tracker.data.TrackerRepository
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.utbildning.tracker.R
import com.utbildning.tracker.backup.AutomaticBackupManager
import com.utbildning.tracker.backup.AutomaticBackupStatus
import com.utbildning.tracker.ui.AppHeader
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

internal enum class BackupUiState { Idle, AwaitingDestination, Creating, Success, Cancelled, Error }
internal enum class RestoreUiState { Idle, Selecting, Validating, AwaitingConfirmation, Restoring, Success, Cancelled, InvalidFile, Error }

@Composable
internal fun SettingsScreen(repository: TrackerRepository?, onBack: () -> Unit, onGuide: () -> Unit = {}) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val localeManager = remember(context) { context.getSystemService(LocaleManager::class.java) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var remindersAllowed by remember { mutableStateOf(ReminderScheduler.allowed(context)) }
    val permissionPreferences = remember(context) { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionPreferences.edit().putBoolean("notification_requested", true).apply()
        remindersAllowed = ReminderScheduler.allowed(context)
    }
    var selectedLanguage by remember(localeManager) {
        mutableStateOf(localeManager.applicationLocales.toLanguageTags())
    }
    val scope = rememberCoroutineScope()
    val exporter = remember(context, repository) { repository?.let { BackupExporter(context.applicationContext, it) } }
    val importer = remember(context, repository) { repository?.let { BackupImporter(context.applicationContext, it) } }
    val automaticBackupManager = remember(context) { AutomaticBackupManager(context.applicationContext) }
    var automaticBackupStatus by remember { mutableStateOf(automaticBackupManager.status()) }
    var enableAfterDestination by remember { mutableStateOf(false) }
    var backupState by remember { mutableStateOf(BackupUiState.Idle) }
    var restoreState by remember { mutableStateOf(RestoreUiState.Idle) }
    var validatedBackup by remember { mutableStateOf<ValidatedBackup?>(null) }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupArchive.MIME)) { uri ->
        if (uri == null) {
            backupState = BackupUiState.Cancelled
        } else if (exporter != null) {
            backupState = BackupUiState.Creating
            scope.launch {
                backupState = try {
                    exporter.export(uri)
                    BackupUiState.Success
                } catch (_: Exception) {
                    BackupUiState.Error
                }
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            restoreState = RestoreUiState.Cancelled
        } else if (importer != null) {
            restoreState = RestoreUiState.Validating
            scope.launch {
                try {
                    validatedBackup = importer.validate(uri)
                    restoreState = RestoreUiState.AwaitingConfirmation
                } catch (_: Exception) {
                    validatedBackup = null
                    restoreState = RestoreUiState.InvalidFile
                }
            }
        }
    }
    val chooseAutomaticDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                automaticBackupManager.selectDestination(uri)
                if (enableAfterDestination) automaticBackupManager.enable()
            } catch (_: Exception) {
                automaticBackupManager.recordError()
            }
        }
        enableAfterDestination = false
        automaticBackupStatus = automaticBackupManager.status()
    }
    // An override can change without changing the effective language or recreating the Activity.
    DisposableEffect(localeManager, lifecycleOwner, configuration) {
        selectedLanguage = localeManager.applicationLocales.toLanguageTags()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                selectedLanguage = localeManager.applicationLocales.toLanguageTags()
                remindersAllowed = ReminderScheduler.allowed(context)
                automaticBackupStatus = automaticBackupManager.status()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SettingsContent(
        selectedLanguage = selectedLanguage,
        onLanguageSelected = { languageTag ->
            val locales = LocaleList.forLanguageTags(languageTag)
            if (localeManager.applicationLocales != locales) {
                localeManager.applicationLocales = locales
            }
            selectedLanguage = localeManager.applicationLocales.toLanguageTags()
        },
        onBack = onBack,
        remindersAllowed = remindersAllowed,
        onNotifications = {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                !permissionPreferences.getBoolean("notification_requested", false))
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        },
        onExact = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) },
        onGuide = onGuide,
        backupState = backupState,
        onCreateBackup = exporter?.let {
            {
                backupState = BackupUiState.AwaitingDestination
                createBackup.launch("study-tracker-${LocalDate.now(ZoneOffset.UTC)}${BackupArchive.EXTENSION}")
            }
        },
        restoreState = restoreState,
        onChooseBackup = importer?.let {
            {
                validatedBackup = null
                restoreState = RestoreUiState.Selecting
                openBackup.launch(arrayOf(BackupArchive.MIME, "application/zip", "application/octet-stream"))
            }
        },
        onConfirmRestore = {
            val backup = validatedBackup
            if (backup != null && importer != null) {
                restoreState = RestoreUiState.Restoring
                scope.launch {
                    restoreState = try {
                        importer.restore(backup)
                        validatedBackup = null
                        RestoreUiState.Success
                    } catch (_: Exception) {
                        RestoreUiState.Error
                    }
                }
            }
        },
        onCancelRestore = {
            validatedBackup = null
            restoreState = RestoreUiState.Cancelled
        },
        automaticBackup = automaticBackupStatus,
        onAutomaticBackupChanged = { enabled ->
            if (!enabled) {
                automaticBackupManager.disable()
                automaticBackupStatus = automaticBackupManager.status()
            } else if (automaticBackupStatus.destination == null) {
                enableAfterDestination = true
                chooseAutomaticDestination.launch(null)
            } else {
                automaticBackupManager.enable()
                automaticBackupStatus = automaticBackupManager.status()
            }
        },
        onChooseAutomaticDestination = {
            enableAfterDestination = automaticBackupStatus.enabled
            chooseAutomaticDestination.launch(automaticBackupStatus.destination)
        },
    )
}

@Composable
internal fun SettingsContent(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onBack: () -> Unit,
    remindersAllowed: Boolean = false,
    onNotifications: () -> Unit = {},
    onExact: () -> Unit = {},
    onGuide: () -> Unit = {},
    backupState: BackupUiState = BackupUiState.Idle,
    onCreateBackup: (() -> Unit)? = null,
    restoreState: RestoreUiState = RestoreUiState.Idle,
    onChooseBackup: (() -> Unit)? = null,
    onConfirmRestore: () -> Unit = {},
    onCancelRestore: () -> Unit = {},
    automaticBackup: AutomaticBackupStatus = AutomaticBackupStatus(false, null, null, null),
    onAutomaticBackupChanged: (Boolean) -> Unit = {},
    onChooseAutomaticDestination: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().testTag("screen_settings")) {
        AppHeader(title = stringResource(R.string.settings), onBack = onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .selectableGroup().padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.language),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            listOf(
                Triple("", R.string.language_system, "language_system"),
                Triple("ru", R.string.language_russian, "language_ru"),
                Triple("en", R.string.language_english, "language_en"),
            ).forEach { (languageTag, label, tag) ->
                val selected = selectedLanguage == languageTag
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(tag)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onLanguageSelected(languageTag) },
                        ).padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
            Text(stringResource(R.string.reminder_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            Text(stringResource(if (remindersAllowed) R.string.reminder_active else R.string.reminder_explanation), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onNotifications, modifier = Modifier.testTag("reminder_permission")) { Text(stringResource(R.string.reminder_enable)) }
            TextButton(onClick = onExact, modifier = Modifier.testTag("reminder_exact")) { Text(stringResource(R.string.reminder_exact)) }
            TextButton(onClick = onGuide, modifier = Modifier.testTag("guide_open")) { Text(stringResource(R.string.guide_title)) }
            Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            Text(stringResource(R.string.backup_explanation), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { onCreateBackup?.invoke() },
                enabled = onCreateBackup != null && backupState !in setOf(BackupUiState.AwaitingDestination, BackupUiState.Creating),
                modifier = Modifier.padding(vertical = 8.dp).testTag("backup_create"),
            ) {
                Text(stringResource(if (backupState == BackupUiState.Creating) R.string.backup_creating else R.string.backup_create))
            }
            when (backupState) {
                BackupUiState.Success -> Text(stringResource(R.string.backup_success), modifier = Modifier.testTag("backup_success"))
                BackupUiState.Cancelled -> Text(stringResource(R.string.backup_cancelled), modifier = Modifier.testTag("backup_cancelled"))
                BackupUiState.Error -> Text(stringResource(R.string.backup_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("backup_error"))
                else -> Unit
            }
            Text(stringResource(R.string.automatic_backup_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            Text(stringResource(R.string.automatic_backup_explanation), style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("automatic_backup_toggle"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(if (automaticBackup.enabled) R.string.automatic_backup_enabled else R.string.automatic_backup_disabled),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = automaticBackup.enabled,
                    onCheckedChange = onAutomaticBackupChanged,
                    modifier = Modifier.testTag("automatic_backup_switch"),
                )
            }
            TextButton(onClick = onChooseAutomaticDestination, modifier = Modifier.testTag("automatic_backup_destination")) {
                Text(stringResource(if (automaticBackup.destination == null) R.string.automatic_backup_choose_folder else R.string.automatic_backup_change_folder))
            }
            if (automaticBackup.destination != null) {
                Text(stringResource(R.string.automatic_backup_folder_selected), style = MaterialTheme.typography.bodySmall)
            }
            automaticBackup.lastSuccessAt?.let {
                Text(stringResource(R.string.automatic_backup_last_success, formatBackupTime(it)), modifier = Modifier.testTag("automatic_backup_success"))
            }
            automaticBackup.lastErrorAt?.let {
                Text(
                    stringResource(R.string.automatic_backup_last_error, formatBackupTime(it)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("automatic_backup_error"),
                )
            }
            Text(stringResource(R.string.restore_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            Text(stringResource(R.string.restore_explanation), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { onChooseBackup?.invoke() },
                enabled = onChooseBackup != null && restoreState !in setOf(
                    RestoreUiState.Selecting,
                    RestoreUiState.Validating,
                    RestoreUiState.AwaitingConfirmation,
                    RestoreUiState.Restoring,
                ),
                modifier = Modifier.padding(vertical = 8.dp).testTag("backup_restore"),
            ) {
                Text(stringResource(when (restoreState) {
                    RestoreUiState.Validating -> R.string.restore_checking
                    RestoreUiState.Restoring -> R.string.restore_restoring
                    else -> R.string.restore_choose
                }))
            }
            when (restoreState) {
                RestoreUiState.Success -> Text(stringResource(R.string.restore_success), modifier = Modifier.testTag("restore_success"))
                RestoreUiState.Cancelled -> Text(stringResource(R.string.restore_cancelled), modifier = Modifier.testTag("restore_cancelled"))
                RestoreUiState.InvalidFile -> Text(stringResource(R.string.restore_invalid), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("restore_invalid"))
                RestoreUiState.Error -> Text(stringResource(R.string.restore_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("restore_error"))
                else -> Unit
            }
        }
    }
    if (restoreState == RestoreUiState.AwaitingConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelRestore,
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = { Text(stringResource(R.string.restore_confirm_message)) },
            confirmButton = {
                Button(onClick = onConfirmRestore, modifier = Modifier.testTag("restore_confirm")) {
                    Text(stringResource(R.string.restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelRestore, modifier = Modifier.testTag("restore_cancel")) {
                    Text(stringResource(R.string.restore_cancel))
                }
            },
        )
    }
}

@Composable
private fun formatBackupTime(timestamp: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(timestamp, locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
    }
}

@Preview(name = "Settings · English", locale = "en", showBackground = true)
@Preview(name = "Настройки · Русский", locale = "ru", showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    TrackerTheme {
        Surface {
            SettingsContent(selectedLanguage = "", onLanguageSelected = {}, onBack = {}, onCreateBackup = {}, onChooseBackup = {})
        }
    }
}
