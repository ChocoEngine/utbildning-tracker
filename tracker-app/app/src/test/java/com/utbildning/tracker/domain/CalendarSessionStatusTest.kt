package com.utbildning.tracker.domain

import com.utbildning.tracker.data.local.SessionResult
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarSessionStatusTest {
    private val today = LocalDate.of(2026, 10, 5)

    @Test fun pastPendingIsOnlyProjectedAsSkipped() {
        assertEquals(SessionResult.SKIPPED, calendarSessionResult(SessionResult.PENDING, today.minusDays(1).toEpochDay(), today))
        assertEquals(SessionResult.PENDING, calendarSessionResult(SessionResult.PENDING, today.toEpochDay(), today))
        assertEquals(SessionResult.PENDING, calendarSessionResult(SessionResult.PENDING, today.plusDays(1).toEpochDay(), today))
    }

    @Test fun explicitResultsAreNeverProjectedDifferently() {
        for (result in listOf(SessionResult.DONE, SessionResult.SKIPPED)) {
            assertEquals(result, calendarSessionResult(result, today.minusDays(10).toEpochDay(), today))
        }
    }
}
