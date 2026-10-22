package com.utbildning.tracker.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalDayTest {
    @Test fun nextBoundaryMatchesMinuteByMinuteSearchInSeveralZones() {
        val seed = 20261023
        val random = Random(seed)
        val zones = listOf("UTC", "Europe/Moscow", "Europe/Berlin", "America/New_York", "Asia/Kathmandu").map(ZoneId::of)
        repeat(100) { case ->
            val zone = zones[random.nextInt(zones.size)]
            val now = Instant.parse("2023-01-01T00:00:00Z").plusSeconds(random.nextInt(365 * 5 * 24 * 60).toLong() * 60)
            val localDay = now.atZone(zone).toLocalDate()
            var minutes = 1L
            while (now.plusSeconds(minutes * 60).atZone(zone).toLocalDate() == localDay) minutes++
            val expected = minutes * 60_000
            assertEquals("seed=$seed case=$case now=$now zone=$zone localDay=$localDay", expected,
                millisUntilNextLocalDay(now.toEpochMilli(), zone))
        }
    }

    @Test fun nextRunIsLocalMidnightRatherThanOneMinuteLater() {
        assertEquals(30 * 60 * 1000L, millisUntilNextLocalDay(Instant.parse("2026-09-26T20:30:00Z").toEpochMilli(), ZoneId.of("Europe/Moscow")))
    }
    @Test fun dayBoundaryRespectsShortAndLongDstDays() {
        val zone = ZoneId.of("Europe/Berlin")
        assertEquals(23 * 60 * 60 * 1000L, millisUntilNextLocalDay(Instant.parse("2026-03-28T23:00:00Z").toEpochMilli(), zone))
        assertEquals(25 * 60 * 60 * 1000L, millisUntilNextLocalDay(Instant.parse("2026-10-24T22:00:00Z").toEpochMilli(), zone))
    }
}
