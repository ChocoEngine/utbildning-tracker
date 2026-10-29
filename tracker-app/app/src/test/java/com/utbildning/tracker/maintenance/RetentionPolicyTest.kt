package com.utbildning.tracker.maintenance

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RetentionPolicyTest {
    @Test fun defaultsAndAllowedRangeMatchCalendarHistoryContract() {
        assertEquals(365, RetentionPolicy.Default.days)
        for (days in listOf(2, 3650)) assertEquals(days, RetentionPolicy(days).days)
        for (days in listOf(-1, 0, 1, 3651)) {
            assertThrows(IllegalArgumentException::class.java) { RetentionPolicy(days) }
        }
    }

    @Test
    fun retainedDatesMatchElapsedLocalDaysAcrossMonthAndYearBoundaries() {
        val seed = 20261024
        val random = Random(seed)
        repeat(250) { case ->
            val today = LocalDate.of(2022, 1, 1).plusDays(random.nextInt(1800).toLong())
            val days = random.nextInt(RetentionPolicy.MIN_DAYS, RetentionPolicy.MAX_DAYS + 1)
            val session = today.plusDays(random.nextInt(-3700, 31).toLong())
            val policy = RetentionPolicy(days)
            val age = ChronoUnit.DAYS.between(session, today)
            assertEquals("seed=$seed case=$case today=$today session=$session days=$days age=$age",
                age > days, policy.shouldDelete(session, today))
            assertEquals("seed=$seed case=$case today=$today days=$days boundary", false,
                policy.shouldDelete(today.minusDays(days.toLong()), today))
        }
    }

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
        val policy = RetentionPolicy(2)

        assertEquals(LocalDate.of(2026, 10, 5), policy.cutoffDate(clock, ZoneId.of("UTC")))
        assertEquals(LocalDate.of(2026, 10, 6), policy.cutoffDate(clock, ZoneId.of("Europe/Moscow")))
        assertEquals(LocalDate.of(2026, 10, 5), policy.cutoffDate(clock, ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun disabledPolicyHasNoBoundaryAndDeletesNothing() {
        val today = LocalDate.of(2026, 10, 7)
        assertNull(RetentionPolicy.Disabled.cutoffDate(today))
        assertFalse(RetentionPolicy.Disabled.shouldDelete(LocalDate.of(2000, 1, 1), today))
    }
}
