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
import com.utbildning.tracker.notifications.ReminderScheduler
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.utbildning.tracker.ui.AppHeader
import com.utbildning.tracker.ui.theme.TrackerTheme

@Composable
internal fun SettingsScreen(onBack: () -> Unit, onGuide: () -> Unit = {}) {
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
    // An override can change without changing the effective language or recreating the Activity.
    DisposableEffect(localeManager, lifecycleOwner, configuration) {
        selectedLanguage = localeManager.applicationLocales.toLanguageTags()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                selectedLanguage = localeManager.applicationLocales.toLanguageTags()
                remindersAllowed = ReminderScheduler.allowed(context)
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
    )
}

@Composable
private fun SettingsContent(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onBack: () -> Unit,
    remindersAllowed: Boolean = false,
    onNotifications: () -> Unit = {},
    onExact: () -> Unit = {},
    onGuide: () -> Unit = {},
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
        }
    }
}

@Preview(name = "Settings · English", locale = "en", showBackground = true)
@Preview(name = "Настройки · Русский", locale = "ru", showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    TrackerTheme {
        Surface {
            SettingsContent(selectedLanguage = "", onLanguageSelected = {}, onBack = {})
        }
    }
}
