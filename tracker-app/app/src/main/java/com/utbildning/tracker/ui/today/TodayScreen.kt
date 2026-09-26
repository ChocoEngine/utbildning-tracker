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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.utbildning.tracker.R
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.SessionTime
import com.utbildning.tracker.ui.AppHeader
import com.utbildning.tracker.ui.session.SessionRow
import com.utbildning.tracker.ui.theme.TrackerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
        AppHeader(title = "", onSettings = onSettings)
        if (error) Text(stringResource(R.string.session_error), color = MaterialTheme.colorScheme.error)
        TodayContent(sessions, now, ZoneId.systemDefault(), locale, onSession, onSkip = { id ->
            scope.launch { try { repository.setSessionResult(id, SessionResult.SKIPPED); error = false } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { error = true } }
        }, onPending = { id -> scope.launch { try { repository.setSessionResult(id, SessionResult.PENDING); error = false } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { error = true } } })
    }
}

@Composable
internal fun TodayContent(sessions: List<SessionEntity>, now: Instant, zone: ZoneId, locale: Locale, onDone: (String) -> Unit, onSkip: (String) -> Unit, onPending: (String) -> Unit = {}) {
    val today = now.atZone(zone).toLocalDate()
    val current = sessions.filter { it.date == today.toEpochDay() }.sortedBy { it.startMinute }
    val upcoming = sessions.filter { it.date > today.toEpochDay() }.sortedWith(compareBy<SessionEntity> { it.date }.thenBy { it.startMinute })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(today.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)), style = MaterialTheme.typography.titleMedium)
        if (current.isEmpty()) Text(stringResource(R.string.sessions_empty))
        current.forEach { session ->
            val question = session.result == SessionResult.PENDING && !now.isBefore(SessionTime.question(today, session.startMinute, session.endMinute, session.endDayOffset, zone))
            SessionRow(session, locale, onDone, onSkip, question, onPending)
        }
        upcoming.firstOrNull()?.let { first ->
            HorizontalDivider()
            Text(stringResource(R.string.today_next), style = MaterialTheme.typography.titleMedium)
            Text(LocalDate.ofEpochDay(first.date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)))
            upcoming.filter { it.date == first.date }.forEach { SessionRow(it, locale, onDone, onSkip, onPending = onPending) }
        }
    }
}

@Preview(locale = "ru", showBackground = true)
@Composable
private fun TodayPreview() { TrackerTheme { Surface { TodayContent(emptyList(), Instant.parse("2026-09-26T12:00:00Z"), ZoneId.of("Europe/Moscow"), Locale.forLanguageTag("ru"), {}, {}) } } }
