package com.utbildning.tracker.domain

import org.junit.Assert.*
import org.junit.Test

class TopicListEditorTest {
    @Test fun parsesLineEndingsTrimsAndKeepsOriginalLineNumbers() {
        for (separator in listOf("\n", "\r\n", "\r")) {
            assertEquals(listOf(TopicLine(2, "Массивы"), TopicLine(4, "Pointers")),
                TopicListEditor.parse(listOf(" ", " Массивы ", "\t", "Pointers", "").joinToString(separator)))
        }
        assertTrue(TopicListEditor.parse(" \r\n\t\n").isEmpty())
    }

    @Test fun completedConflictsReportEveryOriginalLineUsingUnicodeCaseAndTrim() {
        val existing = listOf(EditableTopic("done", " Массивы ", 0, isCompleted = true))
        try {
            TopicListEditor.plan("\nМАССИВЫ\n\n массивы \nМассив", existing)
            fail("Expected completed topic conflicts")
        } catch (exception: TopicListConflictException) {
            assertEquals(listOf(2, 4), exception.lineNumbers)
        }
    }

    @Test fun reorderAndDuplicatesUseStableFifoIdsWithoutCollapsingLines() {
        val existing = listOf(EditableTopic("b", "Same", 2), EditableTopic("x", "Other", 1),
            EditableTopic("a", "Same", 0))
        val result = TopicListEditor.plan("Other\n Same \nSame\nSame", existing)
        assertEquals(listOf("x", "a", "b", null), result.topics.map { it.existingId })
        assertEquals(listOf(0, 1, 2, 3), result.topics.map { it.position })
        assertEquals(listOf("Other", "Same", "Same", "Same"), result.topics.map { it.title })
        assertTrue(result.archivedIds.isEmpty())
    }

    @Test fun fifoTiesUseIdAndRemovedDuplicateIsArchived() {
        val result = TopicListEditor.plan("Same", listOf(
            EditableTopic("b", "Same", 0), EditableTopic("a", "Same", 0)))
        assertEquals("a", result.topics.single().existingId)
        assertEquals(listOf("b"), result.archivedIds)
    }

    @Test fun changedCaseIsNewTopicAndArchivedRowsNeverReappear() {
        val result = TopicListEditor.plan("pointers\nOld", listOf(
            EditableTopic("active", "Pointers", 0),
            EditableTopic("archived", "Old", 1, isCompleted = true, isArchived = true)))
        assertEquals(listOf(null, null), result.topics.map { it.existingId })
        assertEquals(listOf("active"), result.archivedIds)
    }

    @Test fun completedPositionsAreReservedAndCompletedRowsExcludedFromText() {
        val topics = listOf(EditableTopic("done", "Completed", 1, isCompleted = true),
            EditableTopic("b", "Second", 3), EditableTopic("a", "First", 0),
            EditableTopic("old", "Archived", 2, isArchived = true))
        assertEquals("First\nSecond", TopicListEditor.editableText(topics))
        assertEquals(listOf(0, 2, 3), TopicListEditor.plan("Second\nFirst\nNew", topics).topics.map { it.position })
    }

    @Test fun discardingPlanLeavesOriginalSnapshotUnchanged() {
        val original = listOf(EditableTopic("a", "First", 0), EditableTopic("b", "Done", 1, isCompleted = true))
        val before = original.toList()
        TopicListEditor.plan("Replacement", original)
        assertEquals(before, original)
        val reapplied = TopicListEditor.plan(TopicListEditor.editableText(original), original)
        assertEquals(listOf("a"), reapplied.topics.map { it.existingId })
        assertTrue(reapplied.archivedIds.isEmpty())
    }
}
