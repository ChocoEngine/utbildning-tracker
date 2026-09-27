package com.utbildning.tracker.ui.schedule

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.utbildning.tracker.R
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.domain.WeeklyRule
import com.utbildning.tracker.ui.AppHeader
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ScheduleScreen(repository: TrackerRepository, courseId: String, onBack: () -> Unit) {
    var loaded by remember { mutableStateOf(false) }
    var initial by remember { mutableStateOf(emptyList<WeeklyRule>()) }
    var endsOn by remember { mutableStateOf<Long?>(null) }
    var existing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var readOnly by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(courseId) {
        val schedule = repository.getSchedule(courseId)
        existing = schedule != null
        readOnly = repository.getCourse(courseId)?.isCompleted == true
        endsOn = schedule?.endsOn
        initial = repository.getScheduleRules(courseId).map { WeeklyRule(it.dayOfWeek, it.startMinute, it.endMinute) }
        loaded = true
    }
    if (loaded) ScheduleContent(initial, endsOn, readOnly, saving, error, onBack) { rules, end ->
        saving = true
        scope.launch {
            try {
                if (rules.isEmpty() && end == null) repository.disableSchedule(courseId)
                else if (existing) repository.updateSchedule(courseId, rules, end)
                else repository.saveInitialSchedule(courseId, rules, end)
                com.utbildning.tracker.notifications.ReminderScheduler.reconcile(context, repository)
                withContext(Dispatchers.Main.immediate) { onBack() }
            }
            catch (e: CancellationException) { throw e }
            catch (failure: Exception) {
                android.util.Log.e("Tracker", "Schedule save failed", failure)
                error = true
            }
            finally { saving = false }
        }
    } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleContent(
    initial: List<WeeklyRule> = emptyList(),
    initialEnd: Long? = null,
    readOnly: Boolean = false,
    saving: Boolean = false,
    error: Boolean = false,
    onBack: () -> Unit = {},
    onSave: (List<WeeklyRule>, Long?) -> Unit = { _, _ -> },
) {
    // Primitive saveable arrays retain the complete draft across activity recreation.
    var days by rememberSaveable { mutableStateOf(BooleanArray(7) { day -> initial.any { it.dayOfWeek == day + 1 } }) }
    var starts by rememberSaveable { mutableStateOf(IntArray(7) { day -> initial.find { it.dayOfWeek == day + 1 }?.startMinute ?: 19 * 60 }) }
    var ends by rememberSaveable { mutableStateOf(IntArray(7) { day -> initial.find { it.dayOfWeek == day + 1 }?.endMinute ?: -1 }) }
    var endDate by rememberSaveable { mutableStateOf(initialEnd) }
    var dateDialog by rememberSaveable { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    fun pickTime(value: Int, update: (Int) -> Unit) {
        TimePickerDialog(context, { _, h, m -> update(h * 60 + m) }, value / 60, value % 60,
            android.text.format.DateFormat.is24HourFormat(context)).show()
    }
    Column(Modifier.fillMaxSize().testTag("screen_schedule")) {
        AppHeader(stringResource(R.string.schedule_title), onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (readOnly) Text(stringResource(R.string.schedule_existing), style = MaterialTheme.typography.titleMedium)
            for (index in 0..6) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(days[index], onCheckedChange = { checked -> days = days.copyOf().also { it[index] = checked } },
                            enabled = !readOnly && !saving, modifier = Modifier.testTag("schedule_day_${index + 1}"))
                        Text(DayOfWeek.of(index + 1).getDisplayName(TextStyle.FULL, locale), style = MaterialTheme.typography.titleMedium)
                    }
                    if (days[index]) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = !readOnly && !saving, onClick = { pickTime(starts[index]) { value -> starts = starts.copyOf().also { it[index] = value } } }, modifier = Modifier.testTag("schedule_start_${index + 1}")) {
                                Text(stringResource(R.string.schedule_start) + " " + LocalTime.of(starts[index] / 60, starts[index] % 60).format(timeFormat))
                            }
                            if (ends[index] < 0) TextButton(enabled = !readOnly && !saving, onClick = { ends = ends.copyOf().also { it[index] = (starts[index] + 90) % 1440 } }) {
                                Text(stringResource(R.string.schedule_add_end))
                            } else {
                                TextButton(enabled = !readOnly && !saving, onClick = { pickTime(ends[index]) { value -> ends = ends.copyOf().also { it[index] = value } } }) {
                                    Text(stringResource(R.string.schedule_end) + " " + LocalTime.of(ends[index] / 60, ends[index] % 60).format(timeFormat))
                                }
                            }
                        }
                        if (ends[index] >= 0) Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ends[index] < starts[index]) Text(stringResource(R.string.schedule_next_day), style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !readOnly && !saving, onClick = { ends = ends.copyOf().also { it[index] = -1 } }) { Text(stringResource(R.string.schedule_remove_end)) }
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.schedule_until), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { dateDialog = true }, enabled = !readOnly && !saving, modifier = Modifier.testTag("schedule_date")) {
                    Icon(painterResource(R.drawable.ic_calendar), stringResource(R.string.schedule_choose_date))
                }
                endDate?.let { epoch ->
                    Text(LocalDate.ofEpochDay(epoch).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)))
                    TextButton(onClick = { endDate = null }, enabled = !readOnly && !saving, modifier = Modifier.testTag("schedule_clear_date")) { Text(stringResource(R.string.schedule_clear_date)) }
                }
            }
            if (invalid || error) Text(stringResource(if (error) R.string.schedule_failed else R.string.schedule_invalid), color = MaterialTheme.colorScheme.error)
        }
        if (!readOnly) Button(enabled = !saving, modifier = Modifier.fillMaxWidth().padding(20.dp).testTag("schedule_save"), onClick = {
            invalid = (days.none { it } && endDate != null) || (endDate?.let { it < LocalDate.now().toEpochDay() } == true)
            if (!invalid) onSave((0..6).filter { days[it] }.map { i -> WeeklyRule(i + 1, starts[i], ends[i].takeIf { it >= 0 }) }, endDate)
        }) { Text(stringResource(R.string.schedule_save)) }
    }
    if (dateDialog) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = endDate?.let { LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() })
        DatePickerDialog(onDismissRequest = { dateDialog = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { endDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                dateDialog = false
            }, modifier = Modifier.testTag("schedule_date_apply")) { Text(stringResource(R.string.schedule_save)) }
        }, dismissButton = { TextButton(onClick = { dateDialog = false }, modifier = Modifier.testTag("schedule_date_cancel")) { Text(stringResource(R.string.schedule_cancel)) } }) { DatePicker(picker) }
    }
}

@Preview(locale = "ru", showBackground = true)
@Preview(locale = "en", showBackground = true, widthDp = 320)
@Composable
private fun SchedulePreview() { TrackerTheme { ScheduleContent(initial = listOf(WeeklyRule(2, 1140), WeeklyRule(6, 660, 750))) } }
