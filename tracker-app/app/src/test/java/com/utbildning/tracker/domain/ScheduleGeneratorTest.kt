package com.utbildning.tracker.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ScheduleGeneratorTest {
    private fun date(value: String) = LocalDate.parse(value)

    @Test fun differentTimesAndInclusiveBoundariesAcrossYear() {
        val rules = listOf(WeeklyRule(2, 1140), WeeklyRule(6, 660))
        val generated = ScheduleGenerator.generate(date("2024-12-28"), date("2025-01-07"), rules, date("2024-12-01"), date("2025-02-01"))
        assertEquals(listOf("2024-12-28", "2024-12-31", "2025-01-04", "2025-01-07"), generated.map { it.date.toString() })
        assertEquals(listOf(660, 1140, 660, 1140), generated.map { it.startMinute })
        assertEquals(generated, ScheduleGenerator.generate(date("2024-12-28"), date("2025-01-07"), rules.reversed(), date("2024-12-01"), date("2025-02-01")))
    }

    @Test fun leapDayAndDisjointWindow() {
        val rules = listOf(WeeklyRule(4, 600))
        assertEquals(listOf(date("2024-02-29")), ScheduleGenerator.generate(date("2024-02-28"), null, rules, date("2024-02-28"), date("2024-03-01")).map { it.date })
        assertTrue(ScheduleGenerator.generate(date("2025-02-01"), null, rules, date("2025-01-01"), date("2025-01-31")).isEmpty())
    }

    @Test fun timezoneChangesInstantButNotCalendarIdentity() {
        val occurrence = ScheduledOccurrence(date("2026-09-26"), 600, null, 0)
        assertEquals(Instant.parse("2026-09-26T07:00:00Z"), occurrence.startAt(ZoneId.of("Europe/Moscow")))
        assertEquals(Instant.parse("2026-09-26T14:00:00Z"), occurrence.startAt(ZoneId.of("America/New_York")))
        assertEquals(date("2026-09-26"), occurrence.date)
    }

    @Test fun dstGapShiftsAndOverlapUsesEarlierOffset() {
        val zone = ZoneId.of("Europe/Berlin")
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), SessionTime.start(date("2026-03-29"), 150, zone))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), SessionTime.start(date("2026-10-25"), 150, zone))
        assertEquals(Instant.parse("2026-03-29T02:00:00Z"), SessionTime.question(date("2026-03-29"), 150, 180, 0, zone))
    }

    @Test fun overnightAndFallbackQuestionTimes() {
        val utc = ZoneId.of("UTC")
        assertEquals(Instant.parse("2026-01-01T01:00:00Z"), SessionTime.question(date("2025-12-31"), 1380, 60, 1, utc))
        assertEquals(Instant.parse("2026-01-01T00:30:00Z"), SessionTime.question(date("2025-12-31"), 1380, null, 0, utc))
    }

    @Test fun invalidIntervalsAndDuplicateWeekdaysRejected() {
        assertThrows(IllegalArgumentException::class.java) { WeeklyRule(1, 600, 600) }
        assertThrows(IllegalArgumentException::class.java) { WeeklyRule(1, 600, 800, 1) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleGenerator.generate(date("2026-01-01"), null, listOf(WeeklyRule(1, 600), WeeklyRule(1, 700)), date("2026-01-01"), date("2026-01-10")) }
    }
}
