package com.utbildning.tracker.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.utbildning.tracker.R
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.ui.AppHeader
import com.utbildning.tracker.ui.session.SessionRow
import com.utbildning.tracker.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun CalendarScreen(onSettings: () -> Unit, repository: TrackerRepository, onSession: (String) -> Unit) {
    val sessions by remember(repository) { repository.observeSessions() }.collectAsState(emptyList())
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var day by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(repository, month) {
        try { repository.synchronize(YearMonth.parse(month).atEndOfMonth().toEpochDay()); error = false }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = true }
    }
    Column(Modifier.fillMaxSize().testTag("screen_calendar")) {
        AppHeader(title = stringResource(R.string.nav_calendar), onSettings = onSettings)
        if (error) Text(stringResource(R.string.session_error), color = MaterialTheme.colorScheme.error)
        CalendarContent(YearMonth.parse(month), LocalDate.ofEpochDay(day), sessions, locale,
            onMonth = { month = it.toString(); day = it.atDay(1).toEpochDay() }, onDay = { day = it.toEpochDay() }, onDone = onSession,
            onSkip = { id -> scope.launch { try { repository.setSessionResult(id, SessionResult.SKIPPED); error = false } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { error = true } } },
            onPending = { id -> scope.launch { try { repository.setSessionResult(id, SessionResult.PENDING); error = false } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { error = true } } })
    }
}

@Composable
internal fun CalendarContent(month: YearMonth, selected: LocalDate, sessions: List<SessionEntity>, locale: Locale,
    onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit, onDone: (String) -> Unit, onSkip: (String) -> Unit, onPending: (String) -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val previousDescription = stringResource(R.string.calendar_previous)
        val nextDescription = stringResource(R.string.calendar_next)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onMonth(month.minusMonths(1)) }, modifier = Modifier.testTag("calendar_prev").semantics { contentDescription = previousDescription }) { Text("‹") }
            Text(month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(top = 12.dp))
            TextButton(onClick = { onMonth(month.plusMonths(1)) }, modifier = Modifier.testTag("calendar_next").semantics { contentDescription = nextDescription }) { Text("›") }
        }
        Row { DayOfWeek.entries.forEach { Text(it.getDisplayName(TextStyle.SHORT, locale), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall) } }
        val firstOffset = month.atDay(1).dayOfWeek.value - 1
        val cells = ((firstOffset + month.lengthOfMonth() + 6) / 7) * 7
        val byDay = sessions.groupBy { it.date }
        (0 until cells).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) { week.forEach { index ->
                val number = index - firstOffset + 1
                if (number !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(76.dp))
                else {
                    val date = month.atDay(number)
                    val rows = byDay[date.toEpochDay()].orEmpty()
                    val done = stringResource(R.string.session_done)
                    val skipped = stringResource(R.string.session_skipped)
                    val planned = stringResource(R.string.session_planned)
                    val description = date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(locale)) + rows.joinToString(prefix = if (rows.isEmpty()) "" else "; ", separator = "; ") { it.courseNameSnapshot + ": " + when(it.result) { SessionResult.DONE -> done; SessionResult.SKIPPED -> skipped; SessionResult.PENDING -> planned } }
                    Column(Modifier.weight(1f).height(76.dp).padding(2.dp).background(if (date == selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).clickable { onDay(date) }.semantics(mergeDescendants = false) { contentDescription = description }.testTag("calendar_day_${date.toEpochDay()}").padding(4.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(number.toString(), style = MaterialTheme.typography.bodyMedium)
                        if (rows.isNotEmpty()) {
                            Box(Modifier.fillMaxWidth().height(34.dp).semantics { testTag = "calendar_ink_${date.toEpochDay()}" }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                                val color = MaterialTheme.colorScheme.onSurface
                                Canvas(Modifier.fillMaxSize()) {
                                    val blob = Path().apply {
                                        moveTo(size.width * .08f, size.height * .48f)
                                        cubicTo(size.width * .04f, size.height * .18f, size.width * .30f, size.height * .08f, size.width * .48f, size.height * .18f)
                                        cubicTo(size.width * .72f, size.height * .02f, size.width * .98f, size.height * .22f, size.width * .92f, size.height * .52f)
                                        cubicTo(size.width * .99f, size.height * .82f, size.width * .68f, size.height * .98f, size.width * .48f, size.height * .84f)
                                        cubicTo(size.width * .24f, size.height * .98f, size.width * .04f, size.height * .78f, size.width * .08f, size.height * .48f)
                                        close()
                                    }
                                    drawPath(blob, color.copy(alpha = .16f))
                                }
                                Column(Modifier.fillMaxWidth(.78f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    rows.forEach { session ->
                                        val skippedSession = session.result == SessionResult.SKIPPED
                                        Box(Modifier.fillMaxWidth().height(4.dp).background(CourseColors[session.colorIdSnapshot].copy(alpha = if (session.result == SessionResult.DONE) 1f else .3f), RoundedCornerShape(4.dp)).testTag("calendar_bar_${session.id}"), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                                            if (skippedSession) Canvas(Modifier.size(7.dp)) {
                                                val mark = Path().apply { moveTo(0f, 0f); lineTo(size.width, size.height); moveTo(size.width, 0f); lineTo(0f, size.height) }
                                                drawPath(mark, color.copy(alpha = .85f), style = Stroke(width = 1.2.dp.toPx()))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(selected.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG).withLocale(locale)), style = MaterialTheme.typography.titleMedium)
        val visible = byDay[selected.toEpochDay()].orEmpty().sortedBy { it.startMinute }
        if (visible.isEmpty()) Text(stringResource(R.string.sessions_empty), Modifier.padding(vertical = 16.dp))
        visible.forEach { SessionRow(it, locale, onDone, onSkip, onPending = onPending) }
    }
}

@Preview(locale = "ru", showBackground = true)
@Composable
private fun CalendarPreview() { TrackerTheme { Surface { CalendarContent(YearMonth.of(2026,9), LocalDate.of(2026,9,26), emptyList(), Locale.forLanguageTag("ru"), {}, {}, {}, {}) } } }
