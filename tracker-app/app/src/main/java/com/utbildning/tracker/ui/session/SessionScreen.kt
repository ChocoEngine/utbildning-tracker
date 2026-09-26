package com.utbildning.tracker.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import android.text.format.DateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.utbildning.tracker.R
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.data.local.TopicEntity
import com.utbildning.tracker.ui.theme.TrackerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SessionScreen(repository: TrackerRepository, id: String, onBack: () -> Unit) {
    val details by remember(repository, id) { repository.observeSessionDetails(id) }.collectAsState(initial = null)
    var selected by rememberSaveable(id) { mutableStateOf<ArrayList<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var completionCourseId by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(details) { if (selected == null && details != null) selected = ArrayList(details!!.selectedTopicIds) }
    BackHandler { if (!busy && completionCourseId == null) onBack() }
    val current = details
    if (current == null) {
        Column(Modifier.padding(20.dp)) { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }
    } else SessionContent(current.session.courseNameSnapshot, current.selectableTopics, selected.orEmpty().toSet(), busy, error,
        onToggle = { topicId -> selected = ArrayList(selected.orEmpty().let { if (topicId in it) it - topicId else it + topicId }) },
        onCancel = onBack, onSave = {
            scope.launch {
                busy = true; error = false
                try {
                    repository.setSessionResult(id, SessionResult.DONE, selected.orEmpty().toSet())
                    if (repository.shouldOfferCompletion(current.session.courseId)) completionCourseId = current.session.courseId else onBack()
                }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }, dateLabel = LocalDate.ofEpochDay(current.session.date).format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMM"), locale)) + " · " +
            LocalTime.of(current.session.startMinute / 60, current.session.startMinute % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)))
    completionCourseId?.let { courseId ->
        fun finish(complete: Boolean) {
            scope.launch {
                busy = true
                try {
                    if (complete) repository.completeCourse(courseId) else repository.dismissCompletionPrompt(courseId)
                    completionCourseId = null
                    onBack()
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }
        AlertDialog(onDismissRequest = { if (!busy) finish(false) }, text = { Column { Text(stringResource(R.string.course_offer_complete)); if (error) Text(stringResource(R.string.session_error), color = MaterialTheme.colorScheme.error) } },
            confirmButton = { TextButton(onClick = { finish(true) }, enabled = !busy, modifier = Modifier.testTag("session_complete_course")) { Text(stringResource(R.string.course_complete)) } },
            dismissButton = { TextButton(onClick = { finish(false) }, enabled = !busy, modifier = Modifier.testTag("session_keep_active")) { Text(stringResource(R.string.course_keep_active)) } })
    }
}

@Composable
internal fun SessionContent(name: String, topics: List<TopicEntity>, selected: Set<String>, busy: Boolean, error: Boolean,
    onToggle: (String) -> Unit, onCancel: () -> Unit, onSave: () -> Unit, dateLabel: String = "") {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onCancel, enabled = !busy, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("session_back")) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text(stringResource(R.string.back), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (dateLabel.isNotEmpty()) Text(dateLabel.uppercase(LocalConfiguration.current.locales[0]), style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 29.sp, letterSpacing = (-.6).sp))
        Text(stringResource(R.string.session_choose), style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-.2).sp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("session_topics")) {
            items(topics, key = { it.id }) { topic ->
                val isSelected = topic.id in selected
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .toggleable(value = isSelected, enabled = !busy, role = Role.Checkbox, onValueChange = { onToggle(topic.id) })
                        .heightIn(min = 48.dp).padding(vertical = 12.dp)
                        .semantics { contentDescription = topic.title }.testTag("session_topic_${topic.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(if (isSelected) "✓" else (topic.position + 1).toString(), Modifier.width(20.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(topic.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (error) Text(stringResource(R.string.session_error), color = MaterialTheme.colorScheme.error)
        Button(onClick = onSave, enabled = !busy, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("session_save")) { Text(stringResource(R.string.course_save)) }
    }
}

@Preview(locale = "ru", showBackground = true)
@Composable
private fun SessionPreview() { TrackerTheme { Surface { SessionContent("Лекции по C", listOf(TopicEntity("a", "c", 0, "Указатели")), setOf("a"), false, false, {}, {}, {}, "26 сентября · 11:00") } } }
