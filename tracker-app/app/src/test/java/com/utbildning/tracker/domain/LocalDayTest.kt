package com.utbildning.tracker.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalDayTest {
    @Test fun nextRunIsLocalMidnightRatherThanOneMinuteLater() {
        assertEquals(30 * 60 * 1000L, millisUntilNextLocalDay(Instant.parse("2026-09-26T20:30:00Z").toEpochMilli(), ZoneId.of("Europe/Moscow")))
    }
    @Test fun dayBoundaryRespectsShortAndLongDstDays() {
        val zone = ZoneId.of("Europe/Berlin")
        assertEquals(23 * 60 * 60 * 1000L, millisUntilNextLocalDay(Instant.parse("2026-03-28T23:00:00Z").toEpochMilli(), zone))
        assertEquals(25 * 60 * 60 * 1000L, millisUntilNextLocalDay(Instant.parse("2026-10-24T22:00:00Z").toEpochMilli(), zone))
    }
}
