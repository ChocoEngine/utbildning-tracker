package com.utbildning.tracker.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import com.utbildning.tracker.R
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.data.local.TopicEntity
import com.utbildning.tracker.ui.TopicStatusDivider
import com.utbildning.tracker.ui.TopicStatusRow
import com.utbildning.tracker.ui.theme.TrackerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SessionScreen(repository: TrackerRepository, id: String, onBack: () -> Unit) {
    val details by remember(repository, id) { repository.observeSessionDetails(id) }.collectAsStateWithLifecycle(initialValue = null)
    var selected by rememberSaveable(id) { mutableStateOf<ArrayList<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var completionCourseId by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(details) { if (selected == null && details != null) selected = ArrayList(details!!.selectedTopicIds) }
    BackHandler { if (!busy && completionCourseId == null) onBack() }
    val current = details
    if (current == null) {
        IconButton(onClick = onBack, modifier = Modifier.padding(16.dp).testTag("session_back")) {
            Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back))
        }
    } else SessionContent(current.session.courseName, current.selectableTopics, selected.orEmpty().toSet(), busy, error,
        onToggle = { topicId -> selected = ArrayList(selected.orEmpty().let { if (topicId in it) it - topicId else it + topicId }) },
        onCancel = onBack, onSave = {
            scope.launch {
                busy = true; error = false
                try {
                    val offerCompletion = repository.setSessionResult(id, SessionResult.DONE, selected.orEmpty().toSet())
                    com.utbildning.tracker.notifications.ReminderScheduler.reconcile(context, repository)
                    if (offerCompletion) completionCourseId = current.session.courseId else onBack()
                }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }, onSkip = {
            scope.launch {
                busy = true; error = false
                try {
                    repository.setSessionResult(id, SessionResult.SKIPPED)
                    com.utbildning.tracker.notifications.ReminderScheduler.reconcile(context, repository)
                    onBack()
                }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }, editable = current.canEdit)
    completionCourseId?.let { courseId ->
        fun finish(complete: Boolean) {
            scope.launch {
                busy = true
                try {
                    if (complete) repository.completeCourse(courseId)
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
    onToggle: (String) -> Unit, onCancel: () -> Unit, onSave: () -> Unit, onSkip: () -> Unit = {}, editable: Boolean = true) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("session_back")) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back))
            }
            Text(name, style = MaterialTheme.typography.headlineSmall, fontSize = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("session_title"))
        }
        if (editable) Text(stringResource(R.string.session_choose), style = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-.2).sp), modifier = Modifier.padding(horizontal = 8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp).testTag("session_topics")) {
            itemsIndexed(topics, key = { _, topic -> topic.id }) { index, topic ->
                val isSelected = topic.id in selected
                TopicStatusRow(
                    topic.id,
                    topic.title,
                    "${topic.position + 1}.",
                    isSelected,
                    Modifier.fillMaxWidth()
                        .toggleable(value = isSelected, enabled = !busy && editable, role = Role.Checkbox, onValueChange = { onToggle(topic.id) })
                        .semantics { contentDescription = topic.title }.testTag("session_topic_${topic.id}"),
                )
                if (index < topics.lastIndex) TopicStatusDivider()
            }
        }
        if (error) Text(stringResource(R.string.session_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp))
        if (editable) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSkip, enabled = !busy, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).testTag("session_skip")) { Text(stringResource(R.string.session_skipped)) }
            Button(onClick = onSave, enabled = !busy, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).testTag("session_save")) { Text(stringResource(R.string.session_done)) }
        }
    }
}

@Preview(locale = "ru", showBackground = true)
@Composable
private fun SessionPreview() { TrackerTheme { Surface { SessionContent("Лекции по C", listOf(TopicEntity("a", "c", 0, "Указатели")), setOf("a"), false, false, {}, {}, {}) } } }
