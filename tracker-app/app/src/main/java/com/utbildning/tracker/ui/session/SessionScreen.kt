package com.utbildning.tracker.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    LaunchedEffect(details) { if (selected == null && details != null) selected = ArrayList(details!!.selectedTopicIds) }
    BackHandler { if (!busy && completionCourseId == null) onBack() }
    val current = details
    if (current == null) {
        Column(Modifier.padding(20.dp)) { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } }
    } else SessionContent(current.session.courseNameSnapshot, current.selectableTopics, selected.orEmpty().toSet(), busy, error,
        onToggle = { topicId -> selected = ArrayList(selected.orEmpty().let { if (topicId in it) it - topicId else it + topicId }) },
        onNone = { selected = arrayListOf() }, onCancel = onBack, onSave = {
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
        })
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
    onToggle: (String) -> Unit, onNone: () -> Unit, onCancel: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.session_choose), style = MaterialTheme.typography.headlineMedium)
        Text(name, style = MaterialTheme.typography.titleMedium)
        topics.forEach { topic ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(topic.id in selected, { onToggle(topic.id) }, enabled = !busy, modifier = Modifier.semantics { contentDescription = topic.title }.testTag("session_topic_${topic.id}"))
                Text(topic.title, Modifier.padding(top = 12.dp))
            }
        }
        TextButton(onClick = onNone, enabled = !busy, modifier = Modifier.testTag("session_none")) { Text(stringResource(R.string.session_nothing)) }
        if (error) Text(stringResource(R.string.session_error), color = MaterialTheme.colorScheme.error)
        Row {
            Button(onClick = onSave, enabled = !busy, modifier = Modifier.testTag("session_save")) { Text(stringResource(R.string.course_save)) }
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("session_cancel")) { Text(stringResource(R.string.course_cancel)) }
        }
    }
}

@Preview(locale = "ru", showBackground = true)
@Composable
private fun SessionPreview() { TrackerTheme { Surface { SessionContent("Лекции по C", listOf(TopicEntity("a", "c", 0, "Указатели")), setOf("a"), false, false, {}, {}, {}, {}) } } }
