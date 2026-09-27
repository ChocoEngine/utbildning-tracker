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
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import java.time.DayOfWeek
import java.time.format.TextStyle
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
                { model.showAll = !model.showAll }, { model.open(it) }, model.scheduleRules)
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
    progress: Map<String, Pair<Int, Int>>, showAll: Boolean, onFilter: () -> Unit, onOpen: (String?) -> Unit,
    scheduleRules: Map<String, List<ScheduleRuleEntity>> = emptyMap()) {
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (courses.count { !it.isCompleted } < 10) BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Same width as one of the three course actions: (screen width - 32 dp - 12 dp) / 3.
            CourseActionButton(onClick = { onOpen(null) }, modifier = Modifier.width(maxWidth / 3).testTag("course_add")) {
                Text(stringResource(R.string.course_add), maxLines = 1)
            }
        }
        val shown = courses.filter { showAll || !it.isCompleted }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("course_list_scroll"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (shown.isEmpty()) Text(stringResource(R.string.courses_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            shown.groupBy { it.categoryId }.forEach { (categoryId, group) ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text((categories.find { it.id == categoryId }?.name ?: stringResource(R.string.course_no_category)).uppercase(locale),
                            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.3.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp).widthIn(max = LocalConfiguration.current.screenWidthDp.dp - 84.dp))
                        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    group.sortedBy { it.isCompleted }.forEachIndexed { index, course ->
                        Surface(onClick = { onOpen(course.id) }, modifier = Modifier.fillMaxWidth().testTag("course_row_${course.id}"), color = MaterialTheme.colorScheme.surface) {
                            Row(Modifier.heightIn(min = 76.dp).padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (course.isCompleted) Text("✓", modifier = Modifier.width(22.dp).padding(top = 3.dp))
                                else CourseBlot(course.colorId, Modifier.padding(top = 3.dp).size(22.dp, 24.dp).alpha(if (course.isPaused) .45f else 1f))
                                Column(Modifier.weight(1f)) {
                                    Text(course.name, style = MaterialTheme.typography.titleMedium, color = if (course.isPaused)
                                        lerp(MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant, .65f) else MaterialTheme.colorScheme.onSurface)
                                    val count = progress[course.id] ?: (0 to 0)
                                    val rules = scheduleRules[course.id].orEmpty()
                                    val status = when {
                                        course.isCompleted -> stringResource(R.string.course_finished_status)
                                        course.isPaused -> stringResource(R.string.course_paused_status)
                                        rules.isEmpty() -> stringResource(R.string.course_unscheduled)
                                        else -> rules.sortedBy { it.dayOfWeek }.groupBy { it.startMinute }.entries.joinToString(" · ") { (minute, days) ->
                                            days.joinToString(", ") { DayOfWeek.of(it.dayOfWeek).getDisplayName(TextStyle.SHORT, locale) } + " · " + String.format(locale, "%02d:%02d", minute / 60, minute % 60)
                                        }
                                    }
                                    val percent = if (count.second > 0) kotlin.math.round(count.first * 100f / count.second).toInt() else 0
                                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = if (count.second > 0 && !course.isCompleted) 8.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(if (course.isCompleted || count.second == 0) status else stringResource(R.string.course_topics_progress, count.first, count.second) +
                                            if (course.isPaused || rules.isEmpty()) " · $status" else "",
                                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (count.second > 0 && !course.isCompleted) Text("$percent%", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (count.second > 0 && !course.isCompleted) CourseProgress(count.first.toFloat() / count.second, course.colorId,
                                        Modifier.fillMaxWidth().height(6.dp).alpha(if (course.isPaused) .45f else 1f).testTag("course_progress_${course.id}"))
                                }
                                val ink = MaterialTheme.colorScheme.onSurfaceVariant
                                Canvas(Modifier.size(14.dp).align(Alignment.CenterVertically)) {
                                    drawLine(ink, Offset(size.width * .35f, size.height * .2f), Offset(size.width * .65f, size.height * .5f), 1.5.dp.toPx())
                                    drawLine(ink, Offset(size.width * .65f, size.height * .5f), Offset(size.width * .35f, size.height * .8f), 1.5.dp.toPx())
                                }
                            }
                        }
                        if (index < group.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        Column(Modifier.padding(bottom = 16.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FlowRow(Modifier.fillMaxWidth().padding(top = 15.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.courses_completed, courses.count { it.isCompleted }), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (courses.any { it.isCompleted }) FilledTonalButton(onClick = onFilter, shape = RoundedCornerShape(13.dp), modifier = Modifier.testTag("courses_filter")) {
                    Text(stringResource(if (showAll) R.string.courses_active else R.string.courses_all), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CourseBlot(colorId: Int?, modifier: Modifier) {
    Canvas(modifier) {
        val blot = Path().apply {
            moveTo(size.width * .45f, 0f)
            cubicTo(size.width, -size.height * .08f, size.width * .95f, size.height * .45f, size.width * .9f, size.height * .7f)
            cubicTo(size.width * .85f, size.height * 1.15f, size.width * .2f, size.height, size.width * .1f, size.height * .8f)
            cubicTo(-size.width * .2f, size.height * .4f, size.width * .05f, size.height * .1f, size.width * .45f, 0f)
            close()
        }
        drawPath(blot, courseColor(colorId))
    }
}

@Composable
private fun CourseProgress(progress: Float, colorId: Int?, modifier: Modifier) {
    val paper = MaterialTheme.colorScheme.surface
    Canvas(modifier.semantics { progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(progress, 0f..1f) }) {
        val brush = Path().apply {
            moveTo(0f, size.height * .3f)
            cubicTo(size.width * .25f, -size.height * .1f, size.width * .7f, size.height * .15f, size.width, 0f)
            lineTo(size.width, size.height * .8f)
            cubicTo(size.width * .7f, size.height, size.width * .25f, size.height * .75f, 0f, size.height)
            close()
        }
        clipPath(brush) {
            drawRect(paper)
            drawRect(courseColor(colorId).copy(alpha = .19f))
            drawRect(courseColor(colorId), size = androidx.compose.ui.geometry.Size(size.width * progress.coerceIn(0f, 1f), size.height))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun CourseEditorContent(draft: CourseDraft, categories: List<CategoryEntity>, colors: List<Int>, busy: Boolean, error: String?,
    onChange: ((CourseDraft) -> CourseDraft) -> Unit, onSave: () -> Unit, onCancel: () -> Unit, onApply: () -> Unit,
    onDeleteCategory: (CategoryEntity) -> Unit, onToggle: (String) -> Unit, onSchedule: (String) -> Unit = {}, onLifecycle: (String) -> Unit = {},
    onCategoryCommit: () -> Unit = {}, onCategorySelected: (String) -> Unit = {},
    onColorSelected: (Int) -> Unit = { value -> onChange { it.copy(color = value) } }) {
    var paletteExpanded by rememberSaveable(draft.id) { mutableStateOf(draft.color == null) }
    var categoryOpen by rememberSaveable(draft.id) { mutableStateOf(false) }
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
        }.testTag("course_editor").padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            else Text(draft.name, style = MaterialTheme.typography.headlineSmall, fontSize = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = if (!draft.completed) ({ editingName = true }) else null).testTag("course_title"))
            if (draft.color != null) IconButton(onClick = { paletteExpanded = !paletteExpanded }, enabled = !draft.completed, modifier = Modifier.testTag("course_color_toggle")) {
                CourseBlot(draft.color, Modifier.size(27.dp, 28.dp))
            }
        }
        if (draft.completed || draft.paused) Row(Modifier.padding(start = 48.dp).testTag("course_status"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val statusColor = androidx.compose.ui.graphics.Color(if (draft.completed) 0xFF718376 else 0xFF746D66)
            if (draft.completed) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp), tint = statusColor)
            else Icon(painterResource(R.drawable.ic_pause_status), null, Modifier.size(20.dp), tint = statusColor)
            Text(stringResource(if (draft.completed) R.string.course_completed_label else R.string.course_paused_status), style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = statusColor)
        }
        if (!draft.completed && paletteExpanded) AlertDialog(
            modifier = Modifier.testTag("course_palette_dialog"),
            onDismissRequest = { paletteExpanded = false },
            title = { Text(stringResource(R.string.course_color_label)) },
            text = { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.forEach { color ->
                val description = stringResource(R.string.course_color_number, color + 1)
                Box(Modifier.size(44.dp).background(if (draft.color == color) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp)).combinedClickable(onClick = { focus.clearFocus(); onColorSelected(color); paletteExpanded = false })
                    .semantics { contentDescription = description; selected = draft.color == color; role = Role.RadioButton }.testTag("color_$color"), contentAlignment = Alignment.Center) {
                    CourseBlot(color, Modifier.size(27.dp, 28.dp))
                    if (draft.color == color) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp))
                }
            }
        } },
            confirmButton = { TextButton(onClick = { paletteExpanded = false }) { Text(stringResource(R.string.course_cancel)) } })
        TextButton(onClick = { categoryOpen = true }, enabled = !draft.completed, modifier = Modifier.testTag("course_category_open")) {
            Text(if (draft.category.isBlank()) stringResource(R.string.course_add_category) else "${stringResource(R.string.course_category)}: ${draft.category} ⌄")
        }
        if (categoryOpen) AlertDialog(onDismissRequest = { focus.clearFocus(); onCategoryCommit(); categoryOpen = false },
            title = { Text(stringResource(R.string.course_category)) },
            text = { CategoryField(draft.category, categories, { value -> onChange { it.copy(category = value) } }, { value -> onCategorySelected(value); categoryOpen = false }, onCategoryCommit, onDeleteCategory) },
            confirmButton = { TextButton(onClick = { focus.clearFocus(); onCategoryCommit(); categoryOpen = false }) { Text(stringResource(R.string.topics_apply)) } })
        if (!draft.completed && !draft.paused) {
            val locale = LocalConfiguration.current.locales[0]
            CourseSectionDivider()
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { draft.id?.let { focus.clearFocus(); onSchedule(it) } }, enabled = !busy && draft.id != null).testTag("course_schedule_row"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft.id != null && !draft.completed) IconButton(
                    onClick = { focus.clearFocus(); onSchedule(draft.id) },
                    enabled = !busy, modifier = Modifier.testTag("course_schedule")) {
                    Icon(painterResource(R.drawable.ic_calendar),
                        stringResource(if (draft.hasSchedule) R.string.course_configure else R.string.course_enable_schedule),
                        Modifier.size(20.dp))
                } else Icon(painterResource(R.drawable.ic_calendar), null, Modifier.size(20.dp))
                if (draft.rules.isEmpty()) Text(stringResource(R.string.course_unscheduled),
                    style = MaterialTheme.typography.bodyMedium)
                else FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    draft.rules.sortedBy { it.dayOfWeek }.groupBy { it.startMinute to it.endMinute }.forEach { (times, rules) ->
                        val days = rules.joinToString(", ") { DayOfWeek.of(it.dayOfWeek).getDisplayName(TextStyle.SHORT, locale) }
                        fun time(minute: Int) = java.time.LocalTime.of(minute / 60, minute % 60).toString()
                        val hours = time(times.first) + (times.second?.let { "–" + time(it) } ?: "")
                        Text("$days  $hours",
                            Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)).padding(8.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (draft.hasSchedule) draft.endsOn?.let { end ->
                Text(stringResource(R.string.course_schedule_until,
                    java.time.LocalDate.ofEpochDay(end).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CourseSectionDivider()
        }
        val completed = draft.topics.filter { it.isCompleted }
        val total = completed.size + TopicListEditor.parse(draft.text).size
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.course_topics), style = MaterialTheme.typography.titleLarge)
                if (!draft.completed && draft.editingText == null) IconButton(
                    onClick = { onChange { it.copy(editingText = it.text) } },
                    modifier = Modifier.testTag("topics_edit")) {
                    Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.topics_edit), Modifier.size(20.dp))
                }
            }
            if (total > 0) Text(stringResource(R.string.course_topics_progress, completed.size, total), style = MaterialTheme.typography.bodySmall)
        }
        if (total > 0) {
            LinearProgressIndicator(progress = { completed.size.toFloat() / total }, color = courseColor(draft.color), trackColor = courseColor(draft.color).copy(alpha = .16f), modifier = Modifier.fillMaxWidth())
        }

        Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp).verticalScroll(rememberScrollState()).testTag("course_topics_scroll")) {
            draft.topics.sortedBy { it.position }.forEachIndexed { index, topic ->
                TopicRow(topic.id, topic.title, "${index + 1}.", !draft.completed, onToggle, topic.isCompleted)
                if (index < draft.topics.lastIndex) HorizontalDivider(
                    modifier = Modifier.padding(start = 32.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            }
        }
        if (draft.id != null && draft.editingText == null) {
            CourseSectionDivider()
            Text(stringResource(R.string.course_actions_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).height(48.dp).testTag("course_actions"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!draft.completed) {
                    CourseActionButton(onClick = { focus.clearFocus(); onLifecycle("complete") },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).testTag("course_complete")) {
                        Text(stringResource(R.string.course_complete_short), maxLines = 1)
                    }
                    if (draft.paused) CourseActionButton(onClick = { focus.clearFocus(); onLifecycle("resume") }, enabled = !busy, modifier = Modifier.weight(1f).testTag("course_resume")) { Text(stringResource(R.string.course_resume), maxLines = 1) }
                    if (!draft.paused) CourseActionButton(
                        onClick = { focus.clearFocus(); onLifecycle("pause") },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).testTag("course_pause")) {
                        Text(stringResource(R.string.course_pause_short), maxLines = 1)
                    }
                }
                CourseActionButton(onClick = { focus.clearFocus(); onLifecycle("delete") },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).testTag("course_delete")) {
                    Text(stringResource(R.string.course_delete), maxLines = 1)
                }
            }
        }
        if (draft.editingText != null && !draft.completed) {
            AlertDialog(onDismissRequest = { onChange { it.copy(editingText = null) } },
                title = { Text(stringResource(R.string.topics_edit)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(draft.editingText, { value -> onChange { it.copy(editingText = value) } },
                            label = { Text(stringResource(R.string.course_pending_topics)) },
                            minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth().testTag("topics_input"))
                        if (conflictLines.isNotEmpty()) Text(stringResource(R.string.topics_conflict, conflictLines.joinToString()),
                            color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(onClick = onApply, enabled = conflictLines.isEmpty(), modifier = Modifier.testTag("topics_apply")) {
                        Text(stringResource(R.string.topics_apply))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onChange { it.copy(editingText = null) } }, modifier = Modifier.testTag("topics_cancel")) {
                        Text(stringResource(R.string.course_cancel))
                    }
                })
        }
        if (!draft.completed && draft.topics.isNotEmpty() && draft.topics.all { it.isCompleted })
            Text(stringResource(R.string.course_all_topics_completed), modifier = Modifier.testTag("course_all_topics_completed"))
        if (error != null) Text(errorText(error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("course_error"))

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryField(value: String, categories: List<CategoryEntity>, onChange: (String) -> Unit,
    onSelect: (String) -> Unit, onCommit: () -> Unit, onDelete: (CategoryEntity) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val categoryLabel = stringResource(R.string.course_category)
    var fieldWidth by remember { mutableStateOf(0.dp) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val matches = categories.filter { it.name.contains(value.trim(), true) }
    val visible = expanded && matches.isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(stringResource(R.string.course_category), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            TextField(value, { onChange(it); expanded = true }, singleLine = true,
                placeholder = { Text(stringResource(R.string.course_no_category)) },
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                trailingIcon = {
                    if (categories.isNotEmpty()) IconButton(onClick = { expanded = !expanded }) {
                        ExposedDropdownMenuDefaults.TrailingIcon(visible)
                    }
                },
                modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .onGloballyPositioned { fieldWidth = with(density) { it.size.width.toDp() } }
                    .pointerInput(categories) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (down.position.x < size.width - 48.dp.toPx()) expanded = true
                        }
                    }
                    .onFocusChanged { if (focused && !it.isFocused) onCommit(); focused = it.isFocused }
                    .semantics { contentDescription = categoryLabel }
                    .testTag("course_category"))
            if (visible) DropdownMenu(true, { expanded = false },
                // Suggestions must not steal the text field's focus and commit partial input.
                properties = PopupProperties(focusable = false),
                shape = RoundedCornerShape(12.dp),
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.width(fieldWidth).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .testTag("category_menu")) {
                matches.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.name, style = MaterialTheme.typography.bodyMedium) },
                        contentPadding = PaddingValues(start = 12.dp, end = 4.dp),
                        onClick = { onSelect(category.name); expanded = false },
                        modifier = Modifier.testTag("category_option_${category.id}"),
                        trailingIcon = {
                            IconButton(onClick = { expanded = false; onDelete(category) },
                                modifier = Modifier.testTag("category_delete_${category.id}")) {
                                Icon(painterResource(R.drawable.ic_delete),
                                    stringResource(R.string.category_delete_accessibility, category.name),
                                    Modifier.size(18.dp))
                            }
                        })
                }
            }
        }
    }
}

@Composable
private fun CourseSectionDivider() {
    HorizontalDivider(Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .3f))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TopicRow(id: String, title: String, marker: String, enabled: Boolean, onToggle: (String) -> Unit, completed: Boolean = false) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = if (enabled) ({ onToggle(id) }) else null).padding(vertical = 10.dp).testTag("topic_$id"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(marker, Modifier.width(24.dp).testTag("topic_number_$id"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(title, Modifier.weight(1f).testTag("topic_title_$id"), style = MaterialTheme.typography.bodyMedium, color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface); Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (completed) Icon(painterResource(R.drawable.ic_check), null,
                Modifier.size(16.dp).testTag("topic_done_$id"))
        }
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
