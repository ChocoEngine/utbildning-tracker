package com.utbildning.tracker.ui.courses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.utbildning.tracker.R
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.*
import com.utbildning.tracker.ui.AppHeader
import com.utbildning.tracker.ui.theme.*

@Composable
internal fun CoursesScreen(onSettings: () -> Unit, repository: TrackerRepository, onSchedule: (String) -> Unit = {}) {
    val model: CoursesViewModel = viewModel(factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CoursesViewModel(repository) as T
        }
    })
    BackHandler(model.draft != null) { if (!model.busy) model.back() }
    Column(Modifier.fillMaxSize().testTag("screen_courses")) {
        AppHeader(title = stringResource(R.string.nav_courses), onSettings = onSettings)
        val draft = model.draft
        if (draft == null && model.error != null) Text(errorText(model.error!!), color = MaterialTheme.colorScheme.error)
        if (draft == null) CourseListContent(model.courses, model.categories, model.progress, model.showAll,
            { model.showAll = !model.showAll }, { model.open(it) })
        else CourseEditorContent(draft, model.categories, model.colors, model.busy, model.error,
            model::change, model::save, model::cancel, model::applyTopics, { model.removeCategory(it) }, model::toggle, onSchedule, model::requestLifecycle)
        if (model.completionOffer && model.lifecycleAction == null) AlertDialog(onDismissRequest = model::keepActive,
            text = { Text(stringResource(R.string.course_offer_complete)) },
            confirmButton = { TextButton(onClick = { model.requestLifecycle("complete") }, modifier = Modifier.testTag("course_offer_complete")) { Text(stringResource(R.string.course_complete)) } },
            dismissButton = { TextButton(onClick = model::keepActive, modifier = Modifier.testTag("course_keep_active")) { Text(stringResource(R.string.course_keep_active)) } })
        model.lifecycleAction?.let { action ->
            AlertDialog(onDismissRequest = model::dismissLifecycle,
                text = { Column { Text(stringResource(when(action) { "delete" -> R.string.course_confirm_delete; "pause" -> R.string.course_confirm_pause; else -> R.string.course_confirm_complete })); model.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) } } },
                confirmButton = { TextButton(onClick = model::confirmLifecycle, enabled = !model.busy, modifier = Modifier.testTag("course_action_confirm")) { Text(stringResource(when(action) { "delete" -> R.string.course_delete; "pause" -> R.string.course_pause; else -> R.string.course_complete })) } },
                dismissButton = { TextButton(onClick = model::dismissLifecycle, modifier = Modifier.testTag("course_action_cancel")) { Text(stringResource(R.string.course_cancel)) } })
        }
        model.deleteCategory?.let { category ->
            AlertDialog(onDismissRequest = model::dismissCategoryDelete,
                text = { Column { Text(stringResource(R.string.category_delete_question, category.name)); model.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) } } },
                confirmButton = { TextButton(onClick = { model.removeCategory(category, true) }, modifier = Modifier.testTag("category_delete_confirm")) { Text(stringResource(R.string.course_delete)) } },
                dismissButton = { TextButton(onClick = model::dismissCategoryDelete, modifier = Modifier.testTag("category_delete_cancel")) { Text(stringResource(R.string.course_cancel)) } })
        }
    }
}

@Composable
internal fun CourseListContent(courses: List<CourseEntity>, categories: List<CategoryEntity>,
    progress: Map<String, Pair<Int, Int>>, showAll: Boolean, onFilter: () -> Unit, onOpen: (String?) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (courses.count { !it.isCompleted } < 10) TextButton(onClick = { onOpen(null) }, modifier = Modifier.testTag("course_add")) { Text(stringResource(R.string.course_add)) }
        val shown = courses.filter { showAll || !it.isCompleted }
        if (shown.isEmpty()) Text(stringResource(R.string.courses_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        shown.groupBy { it.categoryId }.forEach { (categoryId, group) ->
            Text(categories.find { it.id == categoryId }?.name ?: stringResource(R.string.course_no_category), style = MaterialTheme.typography.labelLarge)
            group.forEach { course ->
                Surface(onClick = { onOpen(course.id) }, modifier = Modifier.fillMaxWidth().testTag("course_row_${course.id}"), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(14.dp).background(CourseColors[course.colorId], CircleShape))
                        Text(course.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val count = progress[course.id] ?: (0 to 0)
                        Text(if (count.second > 0) "${count.first}/${count.second}" else stringResource(R.string.course_sessions, count.first), style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }
        }
        Text(stringResource(R.string.courses_completed, courses.count { it.isCompleted }), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onFilter, modifier = Modifier.testTag("courses_filter")) { Text(stringResource(if (showAll) R.string.courses_active else R.string.courses_all)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CourseEditorContent(draft: CourseDraft, categories: List<CategoryEntity>, colors: List<Int>, busy: Boolean, error: String?,
    onChange: ((CourseDraft) -> CourseDraft) -> Unit, onSave: () -> Unit, onCancel: () -> Unit, onApply: () -> Unit,
    onDeleteCategory: (CategoryEntity) -> Unit, onToggle: (String) -> Unit, onSchedule: (String) -> Unit = {}, onLifecycle: (String) -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    val effectiveText = draft.editingText ?: draft.text
    val conflictLines = try { TopicListEditor.plan(effectiveText, draft.topics); emptyList<Int>() } catch (failure: TopicListConflictException) { failure.lineNumbers }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("course_cancel")) { Text(stringResource(R.string.course_cancel)) }
            Button(onClick = onSave, enabled = !busy && conflictLines.isEmpty(), modifier = Modifier.testTag("course_save")) { Text(stringResource(R.string.course_save)) }
        }
        OutlinedTextField(draft.name, { value -> onChange { it.copy(name = value) } }, label = { Text(stringResource(R.string.course_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("course_name"))
        ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
            OutlinedTextField(draft.category, { value -> onChange { it.copy(category = value) }; expanded = true }, label = { Text(stringResource(R.string.course_category)) }, singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).testTag("course_category"))
            ExposedDropdownMenu(expanded, { expanded = false }) {
                categories.filter { it.name.contains(draft.category.trim(), true) }.forEach { category ->
                    val deleteDescription = stringResource(R.string.category_delete_accessibility, category.name)
                    DropdownMenuItem(text = { Text(category.name) }, onClick = { onChange { it.copy(category = category.name) }; expanded = false },
                        modifier = Modifier.testTag("category_option_${category.id}"),
                        trailingIcon = { IconButton(onClick = { expanded = false; onDeleteCategory(category) }, modifier = Modifier.testTag("category_delete_${category.id}")) {
                            val ink = MaterialTheme.colorScheme.onSurfaceVariant
                            Canvas(Modifier.size(20.dp).semantics { contentDescription = deleteDescription }) {
                                drawLine(ink, Offset(size.width * .15f, size.height * .25f), Offset(size.width * .85f, size.height * .25f), 2.dp.toPx())
                                drawRect(ink, Offset(size.width * .25f, size.height * .3f), androidx.compose.ui.geometry.Size(size.width * .5f, size.height * .6f), style = Stroke(2.dp.toPx()))
                                drawLine(ink, Offset(size.width * .35f, size.height * .1f), Offset(size.width * .65f, size.height * .1f), 2.dp.toPx())
                            }
                        } })
                }
            }
        }
        Text(stringResource(R.string.course_color), style = MaterialTheme.typography.labelLarge)
        colors.chunked(5).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { color ->
                val description = stringResource(R.string.course_color_number, color + 1)
                Box(Modifier.size(48.dp).border(if (draft.color == color) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(6.dp)
                    .background(CourseColors[color], CircleShape).combinedClickable(onClick = { onChange { it.copy(color = color) } })
                    .semantics { contentDescription = description; selected = draft.color == color; role = Role.RadioButton }.testTag("color_$color"))
            }
        } }
        if (draft.id == null) Column {
            FilterChip(draft.mode == CourseMode.SCHEDULED, { onChange { it.copy(mode = CourseMode.SCHEDULED) } }, label = { Text(stringResource(R.string.course_scheduled)) }, modifier = Modifier.testTag("mode_scheduled"))
            FilterChip(draft.mode == CourseMode.UNSCHEDULED, { onChange { it.copy(mode = CourseMode.UNSCHEDULED) } }, label = { Text(stringResource(R.string.course_unscheduled)) }, modifier = Modifier.testTag("mode_unscheduled"))
        } else Text(stringResource(if (draft.mode == CourseMode.SCHEDULED) R.string.course_scheduled else R.string.course_unscheduled))
        if (draft.id != null && draft.mode == CourseMode.SCHEDULED && !draft.completed && !draft.paused) {
            TextButton(onClick = { onSchedule(draft.id) }, modifier = Modifier.testTag("course_schedule")) { Text(stringResource(R.string.course_configure)) }
        }
        Text(stringResource(R.string.course_topics), style = MaterialTheme.typography.titleMedium)
        val completed = draft.topics.filter { !it.isArchived && it.isCompleted }
        val total = completed.size + TopicListEditor.parse(draft.text).size
        if (total > 0) {
            Text("${completed.size}/$total", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { completed.size.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        }
        completed.forEach { topic -> TopicRow(topic.id, topic.title, "✓", !draft.completed && draft.editingText == null && !draft.topicsChanged, onToggle) }
        if (draft.editingText != null) {
            OutlinedTextField(draft.editingText, { value -> onChange { it.copy(editingText = value) } }, label = { Text(stringResource(R.string.course_pending_topics)) }, minLines = 6, modifier = Modifier.fillMaxWidth().testTag("topics_input"))
            Row {
                TextButton(onClick = onApply, enabled = conflictLines.isEmpty(), modifier = Modifier.testTag("topics_apply")) { Text(stringResource(R.string.topics_apply)) }
                TextButton(onClick = { onChange { it.copy(editingText = null) } }, modifier = Modifier.testTag("topics_cancel")) { Text(stringResource(R.string.course_cancel)) }
            }
        } else {
            val planned = try { TopicListEditor.plan(draft.text, draft.topics).topics } catch (_: TopicListConflictException) { emptyList() }
            planned.forEachIndexed { index, topic -> TopicRow(topic.existingId ?: "draft_$index", topic.title, "${index + 1}",
                draft.id != null && !draft.completed && !draft.topicsChanged && topic.existingId != null, onToggle) }
            if (!draft.completed) TextButton(onClick = { onChange { it.copy(editingText = it.text) } }, modifier = Modifier.testTag("topics_edit")) { Text(stringResource(R.string.topics_edit)) }
        }
        if (conflictLines.isNotEmpty()) Text(stringResource(R.string.topics_conflict, conflictLines.joinToString()), color = MaterialTheme.colorScheme.error)
        if (error != null) Text(errorText(error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("course_error"))
        if (draft.id != null && draft.editingText == null) {
            if (!draft.completed) {
                TextButton(onClick = { onLifecycle("complete") }, modifier = Modifier.testTag("course_complete")) { Text(stringResource(R.string.course_complete)) }
                if (!draft.paused) TextButton(onClick = { onLifecycle("pause") }, modifier = Modifier.testTag("course_pause")) { Text(stringResource(R.string.course_pause)) }
            }
            TextButton(onClick = { onLifecycle("delete") }, modifier = Modifier.testTag("course_delete")) { Text(stringResource(R.string.course_delete)) }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TopicRow(id: String, title: String, marker: String, enabled: Boolean, onToggle: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = if (enabled) ({ onToggle(id) }) else null).padding(vertical = 10.dp).testTag("topic_$id"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(marker); Text(title)
    }
}

@Composable
private fun errorText(error: String): String = when {
    error.startsWith("lines:") -> stringResource(R.string.topics_conflict, error.removePrefix("lines:"))
    error == "EMPTY_NAME" -> stringResource(R.string.course_name_required)
    error == "TOPICS_REQUIRED" -> stringResource(R.string.course_topics_required)
    error == "COURSE_LIMIT" -> stringResource(R.string.course_limit)
    error == "COLOR_UNAVAILABLE" -> stringResource(R.string.course_color_unavailable)
    else -> stringResource(R.string.course_save_error)
}

@Preview(locale = "ru", showBackground = true)
@Composable
private fun CoursesScreenPreview() { TrackerTheme { Surface { CourseListContent(emptyList(), emptyList(), emptyMap(), false, {}, {}) } } }

@Preview(locale = "ru", showBackground = true)
@Composable
private fun CourseEditorPreview() { TrackerTheme { Surface { CourseEditorContent(CourseDraft(name = "Лекции по C", text = "Введение\nУказатели"), emptyList(), (0..9).toList(), false, null, {}, {}, {}, {}, {}, {}) } } }
