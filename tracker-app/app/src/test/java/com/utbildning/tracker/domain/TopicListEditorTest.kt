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

    @Test fun normalizesEachTitleByUnicodeCodePointsAfterTrim() {
        val ninetyNine = "Я".repeat(99)
        val hundred = "😀".repeat(100)
        val mixed = "C".repeat(98) + "😀Ж"
        val lines = TopicListEditor.parse("  $ninetyNine  \n$hundred\n${hundred}X\n  ${mixed}tail  ")

        assertEquals(listOf(99, 100, 100, 100), lines.map { it.title.codePointCount(0, it.title.length) })
        assertEquals(ninetyNine, lines[0].title)
        assertEquals(hundred, lines[1].title)
        assertEquals(hundred, lines[2].title)
        assertEquals(mixed, lines[3].title)
        assertFalse(lines.any { it.title.firstOrNull()?.isLowSurrogate() == true })
        assertFalse(lines.any { it.title.lastOrNull()?.isHighSurrogate() == true })
    }

    @Test fun veryLongLinesAreLimitedIndependentlyWithoutCollapsingDuplicates() {
        val longEmoji = "🧠".repeat(10_000)
        val result = TopicListEditor.parse("${longEmoji}A\nshort\n${longEmoji}B")

        assertEquals(listOf(100, 5, 100), result.map { it.title.codePointCount(0, it.title.length) })
        assertEquals(result[0].title, result[2].title)
        assertEquals(listOf(1, 2, 3), result.map { it.lineNumber })
    }

    @Test fun trimmingHappensBeforeLimitAndBlankLinesKeepFollowingSourceNumbers() {
        val title = "A".repeat(99) + "😀"
        val parsed = TopicListEditor.parse("\n  $title overflow  \n \nsecond")

        assertEquals(listOf(2, 4), parsed.map { it.lineNumber })
        assertEquals(title, parsed[0].title)
        assertEquals(100, parsed[0].title.codePointCount(0, parsed[0].title.length))
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

    @Test fun completedConflictIsDetectedAfterTruncationWithOriginalLineNumber() {
        val completedTitle = "Т".repeat(99) + "😀"
        val existing = listOf(EditableTopic("done", completedTitle, 0, isCompleted = true))
        try {
            TopicListEditor.plan("\n\n  ${completedTitle}ignored\n", existing)
            fail("Expected completed topic conflict")
        } catch (exception: TopicListConflictException) {
            assertEquals(listOf(3), exception.lineNumbers)
        }
    }

    @Test fun reorderAndDuplicatesUseStableFifoIdsWithoutCollapsingLines() {
        val existing = listOf(EditableTopic("b", "Same", 2), EditableTopic("x", "Other", 1),
            EditableTopic("a", "Same", 0))
        val result = TopicListEditor.plan("Other\n Same \nSame\nSame", existing)
        assertEquals(listOf("x", "a", "b", null), result.topics.map { it.existingId })
        assertEquals(listOf(0, 1, 2, 3), result.topics.map { it.position })
        assertEquals(listOf("Other", "Same", "Same", "Same"), result.topics.map { it.title })
        assertTrue(result.deletedIds.isEmpty())
    }

    @Test fun fifoTiesUseIdAndReportRemovedDuplicate() {
        val result = TopicListEditor.plan("Same", listOf(
            EditableTopic("b", "Same", 0), EditableTopic("a", "Same", 0)))
        assertEquals("a", result.topics.single().existingId)
        assertEquals(listOf("b"), result.deletedIds)
    }

    @Test fun changedCaseCreatesNewTopicWhileExactTitleKeepsItsId() {
        val result = TopicListEditor.plan("pointers\nOld", listOf(
            EditableTopic("active", "Pointers", 0),
            EditableTopic("archived", "Old", 1, isCompleted = false)))
        assertEquals(listOf(null, "archived"), result.topics.map { it.existingId })
        assertEquals(listOf("active"), result.deletedIds)
    }

    @Test fun completedPositionsAreReservedAndCompletedRowsExcludedFromText() {
        val topics = listOf(EditableTopic("done", "Completed", 1, isCompleted = true),
            EditableTopic("b", "Second", 3), EditableTopic("a", "First", 0),
            EditableTopic("old", "Archived", 2, isCompleted = true))
        assertEquals("First\nSecond", TopicListEditor.editableText(topics))
        assertEquals(listOf(0, 3, 4), TopicListEditor.plan("Second\nFirst\nNew", topics).topics.map { it.position })
    }

    @Test fun discardingPlanLeavesOriginalSnapshotUnchanged() {
        val original = listOf(EditableTopic("a", "First", 0), EditableTopic("b", "Done", 1, isCompleted = true))
        val before = original.toList()
        TopicListEditor.plan("Replacement", original)
        assertEquals(before, original)
        val reapplied = TopicListEditor.plan(TopicListEditor.editableText(original), original)
        assertEquals(listOf("a"), reapplied.topics.map { it.existingId })
        assertTrue(reapplied.deletedIds.isEmpty())
    }
}
