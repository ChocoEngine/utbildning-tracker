package com.utbildning.tracker.domain

/** Storage-independent snapshot used by the text editor. */
data class EditableTopic(
    val id: String,
    val title: String,
    val position: Int,
    val isCompleted: Boolean = false,
    val isArchived: Boolean = false,
)

data class TopicLine(val lineNumber: Int, val title: String)

data class PlannedTopic(val existingId: String?, val title: String, val position: Int)

data class TopicListPlan(val topics: List<PlannedTopic>, val archivedIds: List<String>)

/** Carries source line numbers; the UI supplies localized error text. */
class TopicListConflictException(val lineNumbers: List<Int>) :
    IllegalArgumentException("TOPIC_LIST_CONFLICT")

object TopicListEditor {
    fun parse(text: String): List<TopicLine> = text.lineSequence()
        .mapIndexedNotNull { index, line ->
            line.trim().takeIf { it.isNotEmpty() }?.let { TopicLine(index + 1, it) }
        }.toList()

    fun editableText(topics: List<EditableTopic>): String = editable(topics)
        .joinToString("\n") { it.title.trim() }

    /** Planning is pure: cancel by discarding the text/plan, without a compensating write. */
    fun plan(text: String, topics: List<EditableTopic>): TopicListPlan {
        val lines = parse(text)
        val completed = topics.filter { !it.isArchived && it.isCompleted }
        val conflicts = lines.filter { line ->
            completed.any { it.title.trim().equals(line.title, ignoreCase = true) }
        }.map { it.lineNumber }
        if (conflicts.isNotEmpty()) throw TopicListConflictException(conflicts)

        val existing = editable(topics)
        val byTitle = existing.groupBy { it.title.trim() }
            .mapValues { (_, matches) -> ArrayDeque(matches) }
        val retainedIds = mutableSetOf<String>()
        val occupiedPositions = completed.map { it.position }.toSet()
        var position = 0
        val planned = lines.map { line ->
            while (position in occupiedPositions) position++
            val matched = byTitle[line.title]?.removeFirstOrNull()
            matched?.let { retainedIds.add(it.id) }
            PlannedTopic(matched?.id, line.title, position++)
        }
        return TopicListPlan(planned, existing.filterNot { it.id in retainedIds }.map { it.id })
    }

    private fun editable(topics: List<EditableTopic>): List<EditableTopic> = topics
        .filter { !it.isArchived && !it.isCompleted }
        .sortedWith(compareBy<EditableTopic> { it.position }.thenBy { it.id })
}
