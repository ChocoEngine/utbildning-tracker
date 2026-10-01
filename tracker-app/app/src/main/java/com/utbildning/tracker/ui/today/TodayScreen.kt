package com.utbildning.tracker.ui.today

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.utbildning.tracker.R
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import com.utbildning.tracker.ui.session.SessionRow
import com.utbildning.tracker.ui.theme.TrackerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import android.text.format.DateFormat
import java.util.Locale

@Composable
internal fun TodayScreen(onSettings: () -> Unit, repository: TrackerRepository, onSession: (String) -> Unit) {
    val sessions by remember(repository) { repository.observeSessions() }.collectAsState(emptyList())
    var now by remember { mutableStateOf(Instant.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) now = Instant.now() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(60_000L - System.currentTimeMillis() % 60_000L) } }
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("screen_today")) {
        if (error) Text(stringResource(R.string.session_error), color = MaterialTheme.colorScheme.error)
        TodayContent(sessions, now, ZoneId.systemDefault(), locale, onDone = { id ->
            scope.launch {
                try {
                    if (!repository.markSessionDoneIfNoTopics(id)) onSession(id)
                    error = false
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { error = true }
            }
        }, onSkip = { id ->
            scope.launch { try { repository.setSessionResult(id, SessionResult.SKIPPED); error = false } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { error = true } }
        }, onSettings = onSettings, onPending = { id -> scope.launch { try { repository.setSessionResult(id, SessionResult.PENDING); error = false } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { error = true } } })
    }
}

@Composable
internal fun TodayContent(sessions: List<SessionEntity>, now: Instant, zone: ZoneId, locale: Locale, onDone: (String) -> Unit, onSkip: (String) -> Unit, onPending: (String) -> Unit = {}, onSettings: () -> Unit = {}) {
    val today = now.atZone(zone).toLocalDate()
    val current = sessions.filter { it.date == today.toEpochDay() }.sortedBy { it.startMinute }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(todayLabel(today, locale).uppercase(locale), modifier = Modifier.weight(1f).testTag("today_date"), style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onSettings, modifier = Modifier.testTag("settings")) {
                Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
            }
        }
        if (current.isEmpty()) Text(stringResource(R.string.today_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
        Column {
            current.forEachIndexed { index, session ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SessionRow(session, locale, onDone, onSkip, onPending = onPending, todayStyle = true)
            }
        }
    }
}

private fun todayLabel(date: LocalDate, locale: Locale): String {
    val weekday = date.format(DateTimeFormatter.ofPattern("EEEE", locale)).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    return weekday + " · " + date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMM"), locale))
}

@Preview(locale = "ru", showBackground = true)
@Composable
private fun TodayPreview() { TrackerTheme { Surface { TodayContent(emptyList(), Instant.parse("2026-09-26T12:00:00Z"), ZoneId.of("Europe/Moscow"), Locale.forLanguageTag("ru"), {}, {}) } } }
