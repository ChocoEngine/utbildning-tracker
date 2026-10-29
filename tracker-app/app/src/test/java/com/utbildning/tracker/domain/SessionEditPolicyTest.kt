package com.utbildning.tracker.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionEditPolicyTest {
    @Test fun onlyTodayAndYesterdayAreEditableAcrossMonthYearAndLeapDay() {
        for (today in listOf("2026-01-01", "2024-03-01", "2026-11-01").map(LocalDate::parse)) {
            for (offset in -3L..2L) {
                assertEquals("today=$today offset=$offset", offset in -1L..0L,
                    SessionEditPolicy.canEdit(today.plusDays(offset).toEpochDay(), false, today))
            }
        }
    }

    @Test fun completedCourseIsReadOnlyEvenOnOtherwiseEditableDates() {
        val today = LocalDate.of(2026, 10, 29)
        for (offset in -2L..1L) {
            assertEquals(false, SessionEditPolicy.canEdit(today.plusDays(offset).toEpochDay(), true, today))
        }
    }
}
