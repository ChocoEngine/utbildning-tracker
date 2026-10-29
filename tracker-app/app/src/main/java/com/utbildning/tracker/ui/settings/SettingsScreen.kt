package com.utbildning.tracker.ui.settings

import android.app.LocaleManager
import android.os.LocaleList
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import com.utbildning.tracker.backup.BackupArchive
import com.utbildning.tracker.backup.BackupExporter
import com.utbildning.tracker.backup.BackupImporter
import com.utbildning.tracker.backup.AutomaticBackupStorage
import com.utbildning.tracker.backup.ValidatedBackup
import com.utbildning.tracker.data.TrackerRepository
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.utbildning.tracker.R
import com.utbildning.tracker.backup.AutomaticBackupManager
import com.utbildning.tracker.backup.AutomaticBackupStatus
import com.utbildning.tracker.maintenance.RetentionPolicy
import com.utbildning.tracker.maintenance.RetentionPreferences
import com.utbildning.tracker.notifications.ReminderScheduler
import com.utbildning.tracker.ui.AppHeader
import com.utbildning.tracker.ui.theme.TrackerTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal enum class BackupUiState { Idle, Creating, Success, Error }
internal enum class RestoreUiState { Idle, Selecting, Validating, AwaitingConfirmation, Restoring, Success, Cancelled, InvalidFile, Error }

private fun hasNotificationAccess(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

@Composable
internal fun SettingsScreen(repository: TrackerRepository?, onBack: () -> Unit, onGuide: () -> Unit = {}) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val localeManager = remember(context) { context.getSystemService(LocaleManager::class.java) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsEnabled by remember { mutableStateOf(hasNotificationAccess(context)) }
    var exactAlarmAllowed by remember { mutableStateOf(context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) }
    var selectedLanguage by remember(localeManager) {
        mutableStateOf(localeManager.applicationLocales.toLanguageTags())
    }
    val scope = rememberCoroutineScope()
    val exporter = remember(context, repository) { repository?.let { BackupExporter(context.applicationContext, it) } }
    val importer = remember(context, repository) { repository?.let { BackupImporter(context.applicationContext, it) } }
    val automaticBackupManager = remember(context) { AutomaticBackupManager(context.applicationContext) }
    val retentionPreferences = remember(context) { RetentionPreferences(context.applicationContext) }
    var retentionPolicy by remember { mutableStateOf(retentionPreferences.policy()) }
    var automaticBackupStatus by remember { mutableStateOf(automaticBackupManager.status()) }
    var backupState by remember { mutableStateOf(BackupUiState.Idle) }
    var restoreState by remember { mutableStateOf(RestoreUiState.Idle) }
    var validatedBackup by remember { mutableStateOf<ValidatedBackup?>(null) }
    val permissionPreferences = remember(context) { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var remindersRequested by remember { mutableStateOf(permissionPreferences.getBoolean("reminders_enabled", true)) }
    fun reconcileReminders() {
        repository?.let {
            scope.launch {
                try { ReminderScheduler.reconcile(context.applicationContext, it) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { android.util.Log.e("Tracker", "Reminder reconciliation failed", error) }
            }
        }
    }
    val requestExactAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        notificationsEnabled = hasNotificationAccess(context)
        exactAlarmAllowed = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        reconcileReminders()
    }
    val openNotificationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        notificationsEnabled = hasNotificationAccess(context)
        exactAlarmAllowed = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        if (notificationsEnabled && !exactAlarmAllowed) {
            requestExactAccess.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        } else {
            reconcileReminders()
        }
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionPreferences.edit().putBoolean("notification_requested", true).apply()
        notificationsEnabled = hasNotificationAccess(context)
        exactAlarmAllowed = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        if (notificationsEnabled && !exactAlarmAllowed) {
            requestExactAccess.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        } else {
            reconcileReminders()
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
    val chooseBackupDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                automaticBackupManager.selectDestination(uri)
            } catch (_: Exception) {
                automaticBackupManager.recordError()
            }
        }
        automaticBackupStatus = automaticBackupManager.status()
    }
    // An override can change without changing the effective language or recreating the Activity.
    DisposableEffect(localeManager, lifecycleOwner, configuration, repository) {
        selectedLanguage = localeManager.applicationLocales.toLanguageTags()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                selectedLanguage = localeManager.applicationLocales.toLanguageTags()
                val wasAllowed = remindersRequested && notificationsEnabled && exactAlarmAllowed
                remindersRequested = permissionPreferences.getBoolean("reminders_enabled", true)
                notificationsEnabled = hasNotificationAccess(context)
                exactAlarmAllowed = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                if (wasAllowed != (remindersRequested && notificationsEnabled && exactAlarmAllowed)) reconcileReminders()
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
        remindersAllowed = remindersRequested && notificationsEnabled && exactAlarmAllowed,
        onRemindersChanged = { enabled ->
            remindersRequested = enabled
            permissionPreferences.edit().putBoolean("reminders_enabled", enabled).apply()
            if (!enabled) {
                reconcileReminders()
            } else if (!hasNotificationAccess(context)) {
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    !permissionPreferences.getBoolean("notification_requested", false)) {
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            } else if (!context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                requestExactAccess.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            } else {
                reconcileReminders()
            }
        },
        onGuide = onGuide,
        backupState = backupState,
        onCreateBackup = exporter?.let {
            {
                backupState = BackupUiState.Creating
                scope.launch {
                    backupState = try {
                        withContext(Dispatchers.IO) {
                            val storage = AutomaticBackupStorage(context.applicationContext, automaticBackupManager.status().destination)
                            val document = storage.createManual()
                            try {
                                exporter.export(document)
                                storage.publish(document)
                            } catch (error: Exception) {
                                storage.deleteOrMissing(document)
                                throw error
                            }
                        }
                        BackupUiState.Success
                    } catch (_: Exception) {
                        BackupUiState.Error
                    }
                }
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
            } else {
                automaticBackupManager.enable()
            }
            automaticBackupStatus = automaticBackupManager.status()
        },
        onChooseBackupDestination = {
            chooseBackupDestination.launch(automaticBackupStatus.destination)
        },
        onUseDefaultDestination = {
            try {
                automaticBackupManager.useDefaultDestination()
            } catch (_: Exception) {
                automaticBackupManager.recordError()
            }
            automaticBackupStatus = automaticBackupManager.status()
        },
        retentionPolicy = retentionPolicy,
        onRetentionPolicyChanged = {
            retentionPreferences.save(it)
            retentionPolicy = it
        },
    )
}

@Composable
internal fun SettingsContent(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onBack: () -> Unit,
    remindersAllowed: Boolean = false,
    onRemindersChanged: (Boolean) -> Unit = {},
    onGuide: () -> Unit = {},
    backupState: BackupUiState = BackupUiState.Idle,
    onCreateBackup: (() -> Unit)? = null,
    restoreState: RestoreUiState = RestoreUiState.Idle,
    onChooseBackup: (() -> Unit)? = null,
    onConfirmRestore: () -> Unit = {},
    onCancelRestore: () -> Unit = {},
    automaticBackup: AutomaticBackupStatus = AutomaticBackupStatus(false, null, null, null),
    onAutomaticBackupChanged: (Boolean) -> Unit = {},
    onChooseBackupDestination: () -> Unit = {},
    onUseDefaultDestination: () -> Unit = {},
    retentionPolicy: RetentionPolicy = RetentionPolicy.Default,
    onRetentionPolicyChanged: (RetentionPolicy) -> Unit = {},
) {
    var editingRetention by remember { mutableStateOf(false) }
    var proposedRetention by remember { mutableStateOf<RetentionPolicy?>(null) }
    val context = LocalContext.current
    val selectedFolderPath by produceState<String?>(initialValue = null, automaticBackup.destination) {
        value = automaticBackup.destination?.let { destination ->
            withContext(Dispatchers.IO) { selectedBackupFolderPath(context, destination) }
        }
    }
    val backupPath = if (automaticBackup.destination == null) stringResource(R.string.backup_default_path)
        else selectedFolderPath ?: stringResource(R.string.backup_selected_path)
    val sectionStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    val itemStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal)
    Column(Modifier.fillMaxSize().testTag("screen_settings")) {
        AppHeader(title = stringResource(R.string.settings), onBack = onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Text(
                stringResource(R.string.guide_title),
                style = sectionStyle,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    .clickable(role = Role.Button, onClick = onGuide)
                    .padding(vertical = 8.dp).testTag("guide_open"),
            )
            SettingsSectionDivider()
            Text(
                text = stringResource(R.string.language),
                style = sectionStyle,
                modifier = Modifier.padding(top = 12.dp),
            )
            Column(Modifier.selectableGroup()) {
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
                            ).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(
                            text = stringResource(label),
                            style = itemStyle,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
            SettingsSectionDivider()
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_notifications_title), style = sectionStyle, modifier = Modifier.weight(1f))
                Switch(checked = remindersAllowed, onCheckedChange = onRemindersChanged, modifier = Modifier.testTag("reminders_toggle"))
            }
            SettingsSectionDivider()
            Text(
                stringResource(R.string.retention_title),
                style = sectionStyle,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                if (retentionPolicy.days == null) stringResource(R.string.retention_disabled)
                else stringResource(R.string.retention_current, retentionPolicy.days),
                style = itemStyle,
                modifier = Modifier.padding(top = 16.dp).testTag("retention_current"),
            )
            TextButton(
                onClick = { editingRetention = true },
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.textButtonColors(containerColor = Color(0xFF718376), contentColor = Color(0xFFFCFAF6)),
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).testTag("retention_configure"),
            ) { Text(stringResource(R.string.retention_configure)) }
            SettingsSectionDivider()
            Text(stringResource(R.string.backup_title), style = sectionStyle, modifier = Modifier.padding(top = 12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("automatic_backup_toggle"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.automatic_backup_title),
                    style = itemStyle,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = automaticBackup.enabled,
                    onCheckedChange = onAutomaticBackupChanged,
                    modifier = Modifier.testTag("automatic_backup_switch"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onChooseBackupDestination)
                    .padding(vertical = 8.dp).testTag("automatic_backup_destination"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.backup_folder_location, backupPath),
                    style = itemStyle,
                    modifier = Modifier.weight(1f, fill = false).testTag("backup_folder"),
                )
                Icon(
                    painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(if (automaticBackup.destination == null) R.string.automatic_backup_choose_folder else R.string.automatic_backup_change_folder),
                    modifier = Modifier.padding(start = 8.dp).size(20.dp),
                )
            }
            if (automaticBackup.destination != null) {
                TextButton(
                    onClick = onUseDefaultDestination,
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.textButtonColors(containerColor = Color(0xFF718376), contentColor = Color(0xFFFCFAF6)),
                    modifier = Modifier.testTag("automatic_backup_default"),
                ) {
                    Text(stringResource(R.string.automatic_backup_use_default))
                }
            }
            Button(
                onClick = { onCreateBackup?.invoke() },
                enabled = onCreateBackup != null && backupState != BackupUiState.Creating,
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF718376), contentColor = Color(0xFFFCFAF6)),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
                modifier = Modifier.padding(vertical = 8.dp).testTag("backup_create"),
            ) {
                Text(stringResource(if (backupState == BackupUiState.Creating) R.string.backup_creating else R.string.backup_create))
            }
            when (backupState) {
                BackupUiState.Success -> Text(stringResource(R.string.backup_success), modifier = Modifier.padding(bottom = 8.dp).testTag("backup_success"))
                BackupUiState.Error -> Text(stringResource(R.string.backup_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp).testTag("backup_error"))
                else -> Unit
            }
            SettingsSectionDivider()
            Text(stringResource(R.string.restore_title), style = sectionStyle, modifier = Modifier.padding(top = 12.dp))
            Button(
                onClick = { onChooseBackup?.invoke() },
                enabled = onChooseBackup != null && restoreState !in setOf(
                    RestoreUiState.Selecting,
                    RestoreUiState.Validating,
                    RestoreUiState.AwaitingConfirmation,
                    RestoreUiState.Restoring,
                ),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF718376), contentColor = Color(0xFFFCFAF6)),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).testTag("backup_restore"),
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
    if (editingRetention) {
        RetentionDialog(
            current = retentionPolicy,
            onDismiss = { editingRetention = false },
            onSave = { candidate ->
                editingRetention = false
                val oldDays = retentionPolicy.days
                if (candidate.days != null && (oldDays == null || candidate.days < oldDays)) {
                    proposedRetention = candidate
                } else {
                    onRetentionPolicyChanged(candidate)
                }
            },
        )
    }
    proposedRetention?.let { candidate ->
        AlertDialog(
            onDismissRequest = { proposedRetention = null },
            title = { Text(stringResource(R.string.retention_warning_title)) },
            text = { Text(stringResource(R.string.retention_warning_message, candidate.days!!)) },
            confirmButton = {
                Button(
                    onClick = {
                        onRetentionPolicyChanged(candidate)
                        proposedRetention = null
                    },
                    modifier = Modifier.testTag("retention_warning_confirm"),
                ) { Text(stringResource(R.string.retention_warning_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { proposedRetention = null },
                    modifier = Modifier.testTag("retention_warning_cancel"),
                ) { Text(stringResource(R.string.retention_cancel)) }
            },
        )
    }
}

private fun selectedBackupFolderPath(context: Context, destination: Uri): String? {
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(destination) }.getOrNull() ?: return null
    val volume = documentId.substringBefore(':', missingDelimiterValue = "")
    val relativePath = documentId.substringAfter(':', missingDelimiterValue = "").trim('/')
    if (relativePath.isNotEmpty() &&
        (volume.equals("primary", ignoreCase = true) || volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")))) {
        val path = relativePath.replace("/", " / ")
        return if (volume.equals("primary", ignoreCase = true)) path else "$volume / $path"
    }
    return runCatching {
        val document = DocumentsContract.buildDocumentUriUsingTree(destination, documentId)
        context.contentResolver.query(
            document,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
}

@Composable
private fun SettingsSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun RetentionDialog(
    current: RetentionPolicy,
    onDismiss: () -> Unit,
    onSave: (RetentionPolicy) -> Unit,
) {
    var enabled by remember(current) { mutableStateOf(current.days != null) }
    var input by remember(current) { mutableStateOf((current.days ?: RetentionPolicy.DEFAULT_DAYS).toString()) }
    val parsed = input.toIntOrNull()
    val valid = !enabled || parsed in RetentionPolicy.MIN_DAYS..RetentionPolicy.MAX_DAYS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.retention_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.retention_dialog_explanation))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.retention_cleanup_enabled), modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        modifier = Modifier.testTag("retention_enabled"),
                    )
                }
                if (enabled) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text(stringResource(R.string.retention_days_label)) },
                        supportingText = {
                            Text(stringResource(if (valid) R.string.retention_range else R.string.retention_invalid))
                        },
                        isError = !valid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("retention_days"),
                    )
                } else {
                    Text(
                        stringResource(R.string.retention_disabled_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(if (enabled) RetentionPolicy(parsed!!) else RetentionPolicy.Disabled) },
                enabled = valid,
                modifier = Modifier.testTag("retention_save"),
            ) { Text(stringResource(R.string.retention_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("retention_cancel")) {
                Text(stringResource(R.string.retention_cancel))
            }
        },
    )
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
