package com.utbildning.tracker.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TopicPaceTest {
    private val today = LocalDate.of(2026, 10, 4)
    private val rules = listOf(WeeklyRule(2, 1140), WeeklyRule(6, 1140))
    private val end = LocalDate.of(2026, 10, 27)

    @Test fun roundsUpAndKeepsMinimumOne() {
        assertEquals(3, TopicPace.perSession(19, today, end, rules, today))
        assertEquals(1, TopicPace.perSession(3, today, end, rules, today))
        assertEquals(2, TopicPace.perSession(14, today, end, rules, today))
    }
    @Test fun hidesWithoutRemainingTopicsOrRemainingSchedule() {
        assertNull(TopicPace.perSession(0, today, end, rules, today))
        assertNull(TopicPace.perSession(19, today, null, rules, today))
        assertNull(TopicPace.perSession(19, today, end, emptyList(), today))
        assertNull(TopicPace.perSession(19, today, end, rules, end.plusDays(1)))
    }
    @Test fun excludesResolvedSessionsAndUsesInclusiveStartAndEnd() {
        val tuesday = today.plusDays(2)
        assertEquals(2, TopicPace.perSession(2, tuesday, tuesday, rules, today))
        assertNull(TopicPace.perSession(2, tuesday, tuesday, rules, today, setOf(tuesday)))
        assertEquals(4, TopicPace.perSession(19, today, end, rules, today,
            setOf(today.minusDays(1), today.plusDays(2), today.plusDays(6))))
    }
    @Test fun countsBeyondCalendarHorizonAndMatchesGenerator() {
        for (days in 0..400) {
            val last = today.plusDays(days.toLong())
            val count = ScheduleGenerator.generate(today, last, rules, today, last).size
            val expected = if (count == 0) null else (1000 + count - 1) / count
            assertEquals(expected, TopicPace.perSession(1000, today, last, rules, today))
        }
    }
}
