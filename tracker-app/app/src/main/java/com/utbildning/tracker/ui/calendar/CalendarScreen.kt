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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
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
        Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) },
                    style = MaterialTheme.typography.titleLarge)
                Text(month.year.toString(), Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { val today = LocalDate.now(); onMonth(YearMonth.from(today)); onDay(today) },
                modifier = Modifier.testTag("calendar_today").background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))) {
                Text(stringResource(R.string.nav_today), fontSize = 12.sp)
            }
        }
        val byDay = sessions.groupBy { it.date }
        Column(Modifier.fillMaxWidth().testTag("calendar_grid").semantics {
            customActions = listOf(
                CustomAccessibilityAction(previousDescription) { onMonth(month.minusMonths(1)); true },
                CustomAccessibilityAction(nextDescription) { onMonth(month.plusMonths(1)); true })
        }.pointerInput(month) {
            var drag = 0f
            detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragCancel = { drag = 0f },
                onDragEnd = {
                    if (kotlin.math.abs(drag) > 48.dp.toPx()) onMonth(if (drag < 0) month.plusMonths(1) else month.minusMonths(1))
                }) { change, amount -> change.consume(); drag += amount }
        }) {
        Row { DayOfWeek.entries.forEach { Text(it.getDisplayName(TextStyle.SHORT, locale), Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) } }
        val firstOffset = month.atDay(1).dayOfWeek.value - 1
        val cells = ((firstOffset + month.lengthOfMonth() + 6) / 7) * 7
        (0 until cells).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) { week.forEach { index ->
                val number = index - firstOffset + 1
                if (number !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(52.dp))
                else {
                    val date = month.atDay(number)
                    val rows = byDay[date.toEpochDay()].orEmpty()
                    val done = stringResource(R.string.session_done)
                    val skipped = stringResource(R.string.session_skipped)
                    val planned = stringResource(R.string.session_planned)
                    val description = date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(locale)) + rows.joinToString(prefix = if (rows.isEmpty()) "" else "; ", separator = "; ") { it.courseName + ": " + when(it.result) { SessionResult.DONE -> done; SessionResult.SKIPPED -> skipped; SessionResult.PENDING -> planned } }
                    Box(Modifier.weight(1f).height(52.dp).padding(2.dp).background(if (date == selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).clickable { onDay(date) }.semantics(mergeDescendants = false) { contentDescription = description }.testTag("calendar_day_${date.toEpochDay()}").padding(horizontal = 4.dp, vertical = 2.dp), contentAlignment = Alignment.TopCenter) {
                        Text(number.toString(), modifier = Modifier.zIndex(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        if (rows.isNotEmpty()) {
                            Box(Modifier.fillMaxWidth().height(36.dp).offset(y = 11.dp).semantics { testTag = "calendar_ink_${date.toEpochDay()}" }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                                val color = MaterialTheme.colorScheme.onSurface
                                Canvas(Modifier.size(44.dp, 36.dp)) {
                                    val sx = size.width / 48f
                                    val sy = size.height / 32f
                                    val blob = Path().apply {
                                        if (number % 2 == 0) {
                                            moveTo(7*sx,12*sy)
                                            cubicTo(2*sx,8*sy,9*sx,2*sy,17*sx,4*sy)
                                            cubicTo(22*sx,0f,30*sx,3*sy,33*sx,5*sy)
                                            cubicTo(43*sx,3*sy,47*sx,10*sy,43*sx,15*sy)
                                            cubicTo(48*sx,22*sy,39*sx,27*sy,32*sx,25*sy)
                                            cubicTo(24*sx,31*sy,18*sx,26*sy,13*sx,27*sy)
                                            cubicTo(4*sx,26*sy,1*sx,20*sy,7*sx,12*sy)
                                        } else {
                                            moveTo(5*sx,13*sy)
                                            cubicTo(1*sx,5*sy,13*sx,2*sy,19*sx,4*sy)
                                            cubicTo(27*sx,0f,36*sx,3*sy,38*sx,7*sy)
                                            cubicTo(48*sx,9*sy,47*sx,18*sy,40*sx,21*sy)
                                            cubicTo(39*sx,29*sy,29*sx,27*sy,24*sx,26*sy)
                                            cubicTo(14*sx,31*sy,4*sx,26*sy,6*sx,21*sy)
                                            cubicTo(0f,19*sy,1*sx,16*sy,5*sx,13*sy)
                                        }
                                        close()
                                    }
                                    clipPath(blob) {
                                        if (rows.size > 4) {
                                            val alpha = if (rows.any { it.result == SessionResult.DONE }) .94f else .23f
                                            val colors = rows.map { it.colorId }.distinct()
                                            drawPath(blob, courseColor(colors.first()).copy(alpha = alpha * .3f))
                                            for (dotRow in 0..5) for (dotColumn in 0..7) {
                                                val dotColor = colors[(dotRow * 3 + dotColumn) % colors.size]
                                                drawCircle(courseColor(dotColor).copy(alpha = alpha), 2.4f*sx,
                                                    Offset((3f + dotColumn*6f + if (dotRow % 2 == 0) 0f else 3f)*sx, (2f + dotRow*6f)*sy))
                                            }
                                        } else rotate(-19f) {
                                            rows.forEachIndexed { i, session ->
                                                val weights = rows.indices.map { if (rows.size in 3..4 && it != 0 && it != rows.lastIndex) .7f else 1f }
                                                val width = 60f * weights[i] / weights.sum()
                                                val left = -6f + 60f * weights.take(i).sum() / weights.sum()
                                                drawRect(courseColor(session.colorId).copy(alpha = if (session.result == SessionResult.DONE) .94f else .23f),
                                                    Offset(left*sx, -15*sy), Size((width+.3f)*sx, 65*sy))
                                            }
                                        }
                                    }
                                    rows.forEachIndexed { i, session ->
                                        if (rows.size <= 4 && session.result == SessionResult.SKIPPED) {
                                            val x = (8f + (i+.5f)*32f/rows.size)*sx
                                            val y = 15*sy
                                            val mark = Path().apply {
                                                moveTo(x-2.5f*sx,y-2.5f*sy); lineTo(x+2.5f*sx,y+2.5f*sy)
                                                moveTo(x+2.5f*sx,y-2.5f*sy); lineTo(x-2.5f*sx,y+2.5f*sy)
                                            }
                                            drawPath(mark, color, style = Stroke(width = 1.dp.toPx()))
                                        }
                                    }
                                }

                            }
                        }
                    }
                }
            } }
        }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(selected.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG).withLocale(locale)), style = MaterialTheme.typography.titleMedium)
        val visible = byDay[selected.toEpochDay()].orEmpty().sortedBy { it.startMinute }
        if (visible.isEmpty()) Text(stringResource(R.string.sessions_empty), Modifier.padding(vertical = 16.dp))
        Spacer(Modifier.height(8.dp))
        visible.forEach { session ->
            Row(Modifier.fillMaxWidth().testTag("calendar_session_${session.id}").padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(4.dp).height(22.dp).background(courseColor(session.colorId), RoundedCornerShape(4.dp)))
                Text(LocalTime.of(session.startMinute / 60, session.startMinute % 60)
                    .format(DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(session.courseName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (session.result != SessionResult.PENDING) {
                    Text(stringResource(if (session.result == SessionResult.DONE) R.string.session_done else R.string.session_skipped),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Preview(locale = "ru", showBackground = true)
@Composable
private fun CalendarPreview() { TrackerTheme { Surface { CalendarContent(YearMonth.of(2026,9), LocalDate.of(2026,9,26), emptyList(), Locale.forLanguageTag("ru"), {}, {}, {}, {}) } } }
