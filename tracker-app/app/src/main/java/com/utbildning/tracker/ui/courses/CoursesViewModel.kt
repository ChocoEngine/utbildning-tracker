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

data class CourseDraft(
    val id: String? = null, val name: String = "", val category: String = "",
    val color: Int = 0, val mode: CourseMode = CourseMode.SCHEDULED,
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
    var completionOffer by mutableStateOf(false); private set
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
                            details?.let { topicProgress = topicProgress + (course.id to (it.completions.size to it.topics.size)); refreshProgress() }
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
        if (id == null) draft = CourseDraft(color = colors.firstOrNull() ?: 0)
        else {
            val details = repository.getCourseDetails(id) ?: return@work
            val topics = repository.getTopicEditorTopics(id)
            draft = CourseDraft(id, details.course.name, details.category?.name.orEmpty(), details.course.colorId,
                details.course.mode, details.course.isCompleted, topics, TopicListEditor.editableText(topics), paused = details.course.isPaused)
            detailJob = viewModelScope.launch {
                repository.observeCourseDetails(id).collect { current ->
                    current ?: return@collect
                    val latest = current.topics.map { topic -> EditableTopic(topic.id, topic.title, topic.position, current.completions.any { it.topicId == topic.id }) }
                    draft?.takeIf { it.id == id }?.let { old ->
                        draft = old.copy(topics = latest, completed = current.course.isCompleted, paused = current.course.isPaused, text = if (old.topicsChanged) old.text else TopicListEditor.editableText(latest))
                        completionOffer = repository.shouldOfferCompletion(id)
                    }
                }
            }
        }
    }
    fun cancel() { detailJob?.cancel(); draft = null; error = null; completionOffer = false; lifecycleAction = null }
    fun back() { if (draft?.editingText != null) change { it.copy(editingText = null) } else cancel() }
    fun requestLifecycle(action: String) { lifecycleAction = action }
    fun dismissLifecycle() { lifecycleAction = null }
    fun keepActive() = work { draft?.id?.let { repository.dismissCompletionPrompt(it) }; completionOffer = false }
    fun confirmLifecycle() = work {
        val id = draft?.id ?: return@work
        when (lifecycleAction) {
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
        try {
            TopicListEditor.plan(current.editingText.orEmpty(), current.topics)
            draft = current.copy(text = current.editingText.orEmpty(), editingText = null, topicsChanged = true)
            error = null
        } catch (conflict: TopicListConflictException) { error = "lines:${conflict.lineNumbers.joinToString() }" }
    }
    fun save() = work {
        val current = draft ?: return@work
        repository.saveCourseForm(current.id, current.name, current.color, current.mode, current.category,
            if (current.id == null || current.topicsChanged || current.editingText != null) current.editingText ?: current.text else null)
        cancel()
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
        if (busy) return
        viewModelScope.launch {
            busy = true; error = null
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (conflict: TopicListConflictException) { error = "lines:${conflict.lineNumbers.joinToString()}" }
            catch (failure: RepositoryException) { error = failure.error.name }
            catch (_: Exception) { error = "STORAGE" }
            finally { busy = false }
        }
    }
}
