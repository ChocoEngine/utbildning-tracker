package com.utbildning.tracker.ui.courses

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
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
internal fun CoursesScreen(onSettings: () -> Unit, repository: TrackerRepository, onExitHandler: (((() -> Unit) -> Unit)?) -> Unit = {}, onSchedule: (String) -> Unit = {}) {
    val model: CoursesViewModel = viewModel(factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CoursesViewModel(repository) as T
        }
    })
    val registerExit by rememberUpdatedState(onExitHandler)
    DisposableEffect(model, model.draft != null) {
        registerExit(if (model.draft != null) ({ after -> model.leave(after) }) else null)
        onDispose { registerExit(null) }
    }
    BackHandler(model.draft != null) { if (model.creating) model.cancel() else model.back() }
    Column(Modifier.fillMaxSize().testTag("screen_courses")) {
        val draft = model.draft
        if (draft == null || model.creating) {
            AppHeader(title = stringResource(R.string.nav_courses), onSettings = onSettings)
            if (model.error != null && !model.creating) Text(errorText(model.error!!), color = MaterialTheme.colorScheme.error)
            CourseListContent(model.courses, model.categories, model.progress, model.showAll,
                { model.showAll = !model.showAll }, { model.open(it) })
        } else CourseEditorContent(draft, model.categories, model.colors, model.busy, model.error,
            model::change, model::commitName, { model.leave() }, model::applyTopics, { model.removeCategory(it) }, model::toggle,
            { model.openSchedule(onSchedule) }, model::requestLifecycle,
            model::commitCategory, model::selectCategory, model::selectColor)
        if (model.creating && draft != null) AlertDialog(onDismissRequest = model::cancel,
            title = { Text(stringResource(R.string.course_add)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(draft.name, { value -> model.change { it.copy(name = value) } },
                    label = { Text(stringResource(R.string.course_name)) }, singleLine = true,
                    supportingText = { Text("${draft.name.codePointCount(0, draft.name.length)}/50") },
                    modifier = Modifier.fillMaxWidth().testTag("course_name"))
                CategoryField(draft.category, model.categories, { value -> model.change { it.copy(category = value) } },
                    { value -> model.change { it.copy(category = value) } }, {}, { model.removeCategory(it) })
                model.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("course_error")) }
            } },
            confirmButton = { Button(onClick = model::continueCreation, enabled = !model.busy, modifier = Modifier.testTag("course_continue")) { Text(stringResource(R.string.course_continue)) } },
            dismissButton = { TextButton(onClick = model::cancel, modifier = Modifier.testTag("course_cancel")) { Text(stringResource(R.string.course_cancel)) } })
        model.lifecycleAction?.let { action ->
            AlertDialog(onDismissRequest = model::dismissLifecycle,
                text = { Column { Text(stringResource(when(action) { "delete" -> R.string.course_confirm_delete; "pause" -> R.string.course_confirm_pause; "disable_schedule" -> R.string.course_confirm_disable_schedule; else -> R.string.course_confirm_complete })); model.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) } } },
                confirmButton = { TextButton(onClick = model::confirmLifecycle, enabled = !model.busy, modifier = Modifier.testTag("course_action_confirm")) { Text(stringResource(when(action) { "delete" -> R.string.course_delete; "pause" -> R.string.course_pause; "disable_schedule" -> R.string.course_disable_schedule; else -> R.string.course_complete })) } },
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
        if (courses.count { !it.isCompleted } < 10) Button(onClick = { onOpen(null) }, modifier = Modifier.testTag("course_add")) { Text(stringResource(R.string.course_add)) }
        val shown = courses.filter { showAll || !it.isCompleted }
        if (shown.isEmpty()) Text(stringResource(R.string.courses_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        shown.groupBy { it.categoryId }.forEach { (categoryId, group) ->
            Text(categories.find { it.id == categoryId }?.name ?: stringResource(R.string.course_no_category), style = MaterialTheme.typography.labelLarge)
            group.forEach { course ->
                Surface(onClick = { onOpen(course.id) }, modifier = Modifier.fillMaxWidth().testTag("course_row_${course.id}"), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(14.dp).background(courseColor(course.colorId), CircleShape))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(course.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val count = progress[course.id] ?: (0 to 0)
                                Text(if (count.second > 0) "${count.first}/${count.second}" else stringResource(R.string.course_sessions, count.first), style = MaterialTheme.typography.bodySmall)
                            }
                            val count = progress[course.id] ?: (0 to 0)
                            if (count.second > 0) LinearProgressIndicator(progress = { count.first.toFloat() / count.second },
                                color = courseColor(course.colorId), trackColor = courseColor(course.colorId).copy(alpha = .16f),
                                modifier = Modifier.fillMaxWidth().testTag("course_progress_${course.id}"))
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        Text(stringResource(R.string.courses_completed, courses.count { it.isCompleted }), style = MaterialTheme.typography.bodySmall)
        if (courses.any { it.isCompleted }) TextButton(onClick = onFilter, modifier = Modifier.testTag("courses_filter")) { Text(stringResource(if (showAll) R.string.courses_active else R.string.courses_all)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CourseEditorContent(draft: CourseDraft, categories: List<CategoryEntity>, colors: List<Int>, busy: Boolean, error: String?,
    onChange: ((CourseDraft) -> CourseDraft) -> Unit, onSave: () -> Unit, onCancel: () -> Unit, onApply: () -> Unit,
    onDeleteCategory: (CategoryEntity) -> Unit, onToggle: (String) -> Unit, onSchedule: (String) -> Unit = {}, onLifecycle: (String) -> Unit = {},
    onCategoryCommit: () -> Unit = {}, onCategorySelected: (String) -> Unit = {},
    onColorSelected: (Int) -> Unit = { value -> onChange { it.copy(color = value) } }) {
    var editingName by rememberSaveable(draft.id) { mutableStateOf(false) }
    var nameFocused by remember { mutableStateOf(false) }
    var nameBounds by remember { mutableStateOf<Rect?>(null) }
    var editorOrigin by remember { mutableStateOf(Offset.Zero) }
    val focusRequester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val effectiveText = draft.editingText ?: draft.text
    val conflictLines = try { TopicListEditor.plan(effectiveText, draft.topics); emptyList<Int>() } catch (failure: TopicListConflictException) { failure.lineNumbers }
    LaunchedEffect(editingName) { if (editingName) focusRequester.requestFocus() }
    Column(Modifier.fillMaxSize().onGloballyPositioned { editorOrigin = it.positionInRoot() }
        .pointerInput(editingName) {
            // Observe the initial pass without consuming the touch: buttons and scrolling still work.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (editingName && nameBounds?.contains(down.position + editorOrigin) == false) focus.clearFocus()
            }
        }.testTag("course_editor").verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel, modifier = Modifier.testTag("course_cancel")) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back))
            }
            if (editingName) OutlinedTextField(draft.name, { value -> onChange { it.copy(name = value) } }, singleLine = true,
                label = { Text(stringResource(R.string.course_name)) },
                supportingText = { Text("${draft.name.codePointCount(0, draft.name.length)}/50") },
                modifier = Modifier.weight(1f).onGloballyPositioned { nameBounds = it.boundsInRoot() }.focusRequester(focusRequester).onFocusChanged {
                    if (nameFocused && !it.isFocused) { onSave(); editingName = false }
                    nameFocused = it.isFocused
                }.testTag("course_name"))
            else Text(draft.name, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = if (!draft.completed) ({ editingName = true }) else null).testTag("course_title"))
        }
        if (draft.completed) {
            if (draft.category.isNotEmpty()) Text(draft.category)
        } else CategoryField(draft.category, categories, { value -> onChange { it.copy(category = value) } }, onCategorySelected, onCategoryCommit, onDeleteCategory)
        if (draft.id != null && !draft.completed) {
            OutlinedButton(onClick = { focus.clearFocus(); onSchedule(draft.id) }, modifier = Modifier.fillMaxWidth().testTag("course_schedule")) {
                Text(stringResource(if (!draft.hasSchedule) R.string.course_enable_schedule else R.string.course_configure))
            }
        }
        if (!draft.completed) {
        Text(stringResource(R.string.course_color), style = MaterialTheme.typography.labelLarge)
        colors.chunked(5).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { color ->
                val description = stringResource(R.string.course_color_number, color + 1)
                Box(Modifier.size(48.dp).border(if (draft.color == color) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(6.dp)
                    .background(courseColor(color), CircleShape).combinedClickable(onClick = { focus.clearFocus(); onColorSelected(color) })
                    .semantics { contentDescription = description; selected = draft.color == color; role = Role.RadioButton }.testTag("color_$color"))
            }
        } }
        }
        Text(stringResource(R.string.course_topics), style = MaterialTheme.typography.titleMedium)
        val completed = draft.topics.filter { it.isCompleted }
        val total = completed.size + TopicListEditor.parse(draft.text).size
        if (total > 0) {
            Text("${completed.size}/$total", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { completed.size.toFloat() / total }, color = courseColor(draft.color), trackColor = courseColor(draft.color).copy(alpha = .16f), modifier = Modifier.fillMaxWidth())
        }

        if (draft.editingText != null && !draft.completed) {
            OutlinedTextField(draft.editingText, { value -> onChange { it.copy(editingText = value) } }, label = { Text(stringResource(R.string.course_pending_topics)) }, minLines = 6, modifier = Modifier.fillMaxWidth().testTag("topics_input"))
            Row {
                TextButton(onClick = onApply, enabled = conflictLines.isEmpty(), modifier = Modifier.testTag("topics_apply")) { Text(stringResource(R.string.topics_apply)) }
                TextButton(onClick = { onChange { it.copy(editingText = null) } }, modifier = Modifier.testTag("topics_cancel")) { Text(stringResource(R.string.course_cancel)) }
            }
        } else {
            draft.topics.sortedBy { it.position }.forEachIndexed { index, topic ->
                TopicRow(topic.id, topic.title, "${index + 1}.", !draft.completed, onToggle, topic.isCompleted)
            }
            if (!draft.completed) TextButton(onClick = { onChange { it.copy(editingText = it.text) } }, modifier = Modifier.testTag("topics_edit")) { Text(stringResource(R.string.topics_edit)) }
        }
        if (!draft.completed && draft.topics.isNotEmpty() && draft.topics.all { it.isCompleted })
            Text(stringResource(R.string.course_all_topics_completed), modifier = Modifier.testTag("course_all_topics_completed"))
        if (conflictLines.isNotEmpty()) Text(stringResource(R.string.topics_conflict, conflictLines.joinToString()), color = MaterialTheme.colorScheme.error)
        if (error != null) Text(errorText(error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("course_error"))
        if (draft.id != null && draft.editingText == null) {
            if (!draft.completed && draft.hasSchedule) OutlinedButton(onClick = { focus.clearFocus(); onLifecycle("disable_schedule") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("course_disable_schedule")) { Text(stringResource(R.string.course_disable_schedule)) }
            if (!draft.completed) {
                OutlinedButton(onClick = { focus.clearFocus(); onLifecycle("complete") }, modifier = Modifier.fillMaxWidth().testTag("course_complete")) { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.course_complete)) }
                if (draft.hasSchedule && !draft.paused) OutlinedButton(onClick = { focus.clearFocus(); onLifecycle("pause") }, modifier = Modifier.fillMaxWidth().testTag("course_pause")) { Text(stringResource(R.string.course_pause)) }
            }
            OutlinedButton(onClick = { focus.clearFocus(); onLifecycle("delete") }, modifier = Modifier.fillMaxWidth().testTag("course_delete")) { Text(stringResource(R.string.course_delete)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryField(value: String, categories: List<CategoryEntity>, onChange: (String) -> Unit,
    onSelect: (String) -> Unit, onCommit: () -> Unit, onDelete: (CategoryEntity) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val matches = categories.filter { it.name.contains(value.trim(), true) }
    val visible = expanded && matches.isNotEmpty()
    ExposedDropdownMenuBox(visible, { expanded = !expanded }) {
        OutlinedTextField(value, { onChange(it); expanded = true }, label = { Text(stringResource(R.string.course_category)) }, singleLine = true,
            trailingIcon = { if (categories.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(visible) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .onFocusChanged { if (focused && !it.isFocused) onCommit(); focused = it.isFocused }.testTag("course_category"))
        if (visible) ExposedDropdownMenu(true, { expanded = false }, modifier = Modifier.testTag("category_menu")) {
            matches.forEach { category ->
                val description = stringResource(R.string.category_delete_accessibility, category.name)
                DropdownMenuItem(text = { Text(category.name) }, onClick = { onSelect(category.name); expanded = false }, modifier = Modifier.testTag("category_option_${category.id}"),
                    trailingIcon = { IconButton(onClick = { expanded = false; onDelete(category) }, modifier = Modifier.testTag("category_delete_${category.id}")) {
                        val ink = MaterialTheme.colorScheme.onSurfaceVariant
                        Canvas(Modifier.size(20.dp).semantics { contentDescription = description }) {
                            drawLine(ink, Offset(size.width * .15f, size.height * .25f), Offset(size.width * .85f, size.height * .25f), 2.dp.toPx())
                            drawRect(ink, Offset(size.width * .25f, size.height * .3f), androidx.compose.ui.geometry.Size(size.width * .5f, size.height * .6f), style = Stroke(2.dp.toPx()))
                            drawLine(ink, Offset(size.width * .35f, size.height * .1f), Offset(size.width * .65f, size.height * .1f), 2.dp.toPx())
                        }
                    } })
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TopicRow(id: String, title: String, marker: String, enabled: Boolean, onToggle: (String) -> Unit, completed: Boolean = false) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = if (enabled) ({ onToggle(id) }) else null).padding(vertical = 10.dp).testTag("topic_$id"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(marker, Modifier.testTag("topic_number_$id")); Text(title, Modifier.weight(1f)); if (completed) Text("✓", Modifier.testTag("topic_done_$id"))
    }
}

@Composable
private fun errorText(error: String): String = when {
    error.startsWith("lines:") -> stringResource(R.string.topics_conflict, error.removePrefix("lines:"))
    error == "NAME_TOO_LONG" -> stringResource(R.string.course_name_too_long)
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
