package com.utbildning.tracker.maintenance

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPolicyTest {
    @Test
    fun boundaryDayIsKeptAndOnlyEarlierSessionsAreDeleted() {
        val policy = RetentionPolicy(30)
        val today = LocalDate.of(2026, 10, 7)

        assertEquals(LocalDate.of(2026, 9, 7), policy.cutoffDate(today))
        assertFalse(policy.shouldDelete(LocalDate.of(2026, 9, 7), today))
        assertTrue(policy.shouldDelete(LocalDate.of(2026, 9, 6), today))
        assertFalse(policy.shouldDelete(today, today))
        assertFalse(policy.shouldDelete(today.plusDays(1), today))
    }

    @Test
    fun boundaryUsesCurrentLocalDateInRequestedTimeZone() {
        val clock = Clock.fixed(Instant.parse("2026-10-07T23:30:00Z"), ZoneId.of("UTC"))
        val policy = RetentionPolicy(1)

        assertEquals(LocalDate.of(2026, 10, 6), policy.cutoffDate(clock, ZoneId.of("UTC")))
        assertEquals(LocalDate.of(2026, 10, 7), policy.cutoffDate(clock, ZoneId.of("Europe/Moscow")))
        assertEquals(LocalDate.of(2026, 10, 6), policy.cutoffDate(clock, ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun disabledPolicyHasNoBoundaryAndDeletesNothing() {
        val today = LocalDate.of(2026, 10, 7)
        assertNull(RetentionPolicy.Disabled.cutoffDate(today))
        assertFalse(RetentionPolicy.Disabled.shouldDelete(LocalDate.of(2000, 1, 1), today))
    }
}
