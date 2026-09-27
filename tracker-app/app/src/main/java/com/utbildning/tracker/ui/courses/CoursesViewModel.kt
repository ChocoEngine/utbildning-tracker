package com.utbildning.tracker.ui.courses

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.RepositoryException
import com.utbildning.tracker.data.RepositoryError
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CourseDraft(
    val id: String? = null, val name: String = "", val category: String = "",
    val color: Int? = 0, val hasSchedule: Boolean = false,
    val completed: Boolean = false, val topics: List<EditableTopic> = emptyList(),
    val text: String = "", val editingText: String? = null, val topicsChanged: Boolean = false,
    val paused: Boolean = false,
)

internal class CoursesViewModel(private val repository: TrackerRepository) : ViewModel() {
    var courses by mutableStateOf<List<CourseEntity>>(emptyList()); private set
    var categories by mutableStateOf<List<CategoryEntity>>(emptyList()); private set
    var progress by mutableStateOf<Map<String, Pair<Int, Int>>>(emptyMap()); private set
    var draft by mutableStateOf<CourseDraft?>(null); private set
    var colors by mutableStateOf<List<Int>>(emptyList()); private set
    var showAll by mutableStateOf(false)
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var deleteCategory by mutableStateOf<CategoryEntity?>(null); private set
    var lifecycleAction by mutableStateOf<String?>(null); private set
    var creating by mutableStateOf(false); private set
    private val writes = Mutex()
    private var pendingWrites = 0
    private var sessions: List<SessionEntity> = emptyList()
    private var topicProgress = emptyMap<String, Pair<Int, Int>>()
    private var detailJob: Job? = null
    private val progressJobs = mutableMapOf<String, Job>()
    init {
        viewModelScope.launch { repository.observeCategories().collect { categories = it } }
        viewModelScope.launch { repository.observeSessions().collect { sessions = it; refreshProgress() } }
        viewModelScope.launch {
            repository.observeCourses().collect { list ->
                courses = list
                progressJobs.keys.filter { id -> list.none { it.id == id } }.forEach { progressJobs.remove(it)?.cancel() }
                list.forEach { course ->
                    if (course.id !in progressJobs) progressJobs[course.id] = viewModelScope.launch {
                        repository.observeCourseDetails(course.id).collect { details ->
                            details?.let { topicProgress = topicProgress + (course.id to (it.topics.count { topic -> topic.isCompleted } to it.topics.size)); refreshProgress() }
                        }
                    }
                }
            }
        }
    }
    fun change(transform: (CourseDraft) -> CourseDraft) { draft = draft?.let(transform); error = null }
    fun open(id: String? = null) = work {
        detailJob?.cancel()
        colors = repository.availableColors(id)
        if (id == null) { draft = CourseDraft(color = colors.firstOrNull() ?: 0); creating = true }
        else {
            creating = false
            val details = repository.getCourseDetails(id) ?: return@work
            val topics = repository.getTopicEditorTopics(id)
            draft = CourseDraft(id, details.course.name, details.category?.name.orEmpty(), details.course.colorId,
                details.hasSchedule, details.course.isCompleted, topics, TopicListEditor.editableText(topics), paused = details.course.isPaused)
            detailJob = viewModelScope.launch {
                repository.observeCourseDetails(id).collect { current ->
                    current ?: return@collect
                    val latest = current.topics.map { topic -> EditableTopic(topic.id, topic.title, topic.position, topic.isCompleted) }
                    draft?.takeIf { it.id == id }?.let { old ->
                        draft = old.copy(hasSchedule = current.hasSchedule, topics = latest, completed = current.course.isCompleted, paused = current.course.isPaused, text = if (old.topicsChanged) old.text else TopicListEditor.editableText(latest))
                    }
                }
            }
        }
    }
    fun cancel() { creating = false; detailJob?.cancel(); draft = null; error = null; lifecycleAction = null }
    fun back() { if (draft?.editingText != null) change { it.copy(editingText = null) } else leave() }
    fun continueCreation() = work {
        val current = draft ?: return@work
        val saved = repository.saveCourseForm(name = current.name, colorId = current.color,
            categoryName = current.category, topicText = current.text)
        creating = false
        attach(saved.id)
    }
    private suspend fun attach(id: String) {
        val details = repository.getCourseDetails(id) ?: return
        val topics = repository.getTopicEditorTopics(id)
        draft = CourseDraft(id, details.course.name, details.category?.name.orEmpty(), details.course.colorId,
            details.hasSchedule, details.course.isCompleted, topics, TopicListEditor.editableText(topics),
            paused = details.course.isPaused)
        colors = repository.availableColors(id)
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            repository.observeCourseDetails(id).collect { current ->
                current ?: return@collect
                val latest = current.topics.map { topic -> EditableTopic(topic.id, topic.title, topic.position, topic.isCompleted) }
                draft?.takeIf { it.id == id }?.let { old ->
                    draft = old.copy(hasSchedule = current.hasSchedule, topics = latest, completed = current.course.isCompleted, paused = current.course.isPaused,
                        text = if (old.topicsChanged) old.text else TopicListEditor.editableText(latest))
                }
            }
        }
    }
    fun commitName() = commitField("name")
    fun commitCategory() = commitField("category")
    fun selectCategory(value: String) { change { it.copy(category = value) }; commitCategory() }
    fun selectColor(value: Int) { change { it.copy(color = value) }; commitField("color") }
    private fun commitField(field: String) {
        val snapshot = draft ?: return
        if (snapshot.completed) return
        val id = snapshot.id ?: return
        work {
            val current = repository.getCourseDetails(id) ?: return@work
            val name = if (field == "name") snapshot.name else current.course.name
            val category = if (field == "category") snapshot.category else current.category?.name.orEmpty()
            val color = if (field == "color") snapshot.color else current.course.colorId
            if (name.trim() != current.course.name || category.trim() != current.category?.name.orEmpty() || color != current.course.colorId)
                repository.updateCourse(id, name, color, category)
        }
    }
    fun leave(after: () -> Unit = {}) {
        val snapshot = draft
        work {
            if (snapshot?.id != null) flush(snapshot)
            cancel()
            after()
        }
    }
    fun openSchedule(after: (String) -> Unit) {
        val snapshot = draft ?: return
        work { if (snapshot.id != null) { flush(snapshot); after(snapshot.id) } }
    }
    private suspend fun flush(snapshot: CourseDraft) {
        if (snapshot.completed) return
        val id = snapshot.id ?: return
        val current = repository.getCourseDetails(id) ?: return
        if (snapshot.name.trim() != current.course.name || snapshot.category.trim() != current.category?.name.orEmpty() || snapshot.color != current.course.colorId)
            repository.updateCourse(id, snapshot.name, snapshot.color, snapshot.category)
    }
    fun requestLifecycle(action: String) { lifecycleAction = action }
    fun dismissLifecycle() { lifecycleAction = null }
    fun confirmLifecycle() = work {
        val id = draft?.id ?: return@work
        when (lifecycleAction) {
            "disable_schedule" -> {
                draft?.let { flush(it) }
                repository.disableSchedule(id)
                attach(id)
                lifecycleAction = null
                return@work
            }
            "delete" -> repository.deleteCourse(id)
            "pause" -> repository.pauseCourse(id)
            "complete" -> repository.completeCourse(id)
        }
        cancel()
    }
    private fun refreshProgress() {
        progress = topicProgress.mapValues { (id, count) ->
            if (count.second == 0) sessions.count { it.courseId == id && it.result == SessionResult.DONE } to 0 else count
        }
    }
    fun applyTopics() {
        val current = draft ?: return
        val text = current.editingText ?: return
        work {
            TopicListEditor.plan(text, current.topics)
            if (current.id == null) {
                val saved = repository.saveCourseForm(name = current.name, colorId = current.color,
                    categoryName = current.category, topicText = text)
                attach(saved.id)
            } else {
                repository.saveTopicList(current.id, text)
                change { it.copy(text = text, editingText = null, topicsChanged = false) }
            }
        }
    }
    fun toggle(id: String) = work {
        val current = draft ?: return@work
        repository.toggleTopicCompletion(current.id ?: return@work, id)
    }
    fun removeCategory(category: CategoryEntity, confirmed: Boolean = false) = work {
        try { repository.deleteCategory(category.id, confirmed) }
        catch (failure: RepositoryException) {
            if (failure.error == RepositoryError.CATEGORY_IN_USE) { deleteCategory = category; return@work }
            throw failure
        }
        change { if (it.category.trim().equals(category.name.trim(), true)) it.copy(category = "") else it }
        deleteCategory = null
    }
    fun dismissCategoryDelete() { deleteCategory = null }
    private fun work(block: suspend () -> Unit) {
        pendingWrites++; busy = true
        viewModelScope.launch {
            try {
                writes.withLock {
                    error = null
                    try { block() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (conflict: TopicListConflictException) { error = "lines:${conflict.lineNumbers.joinToString()}" }
                    catch (failure: RepositoryException) { error = failure.error.name }
                    catch (_: Exception) { error = "STORAGE" }
                }
            } finally { pendingWrites--; busy = pendingWrites > 0 }
        }
    }
}
