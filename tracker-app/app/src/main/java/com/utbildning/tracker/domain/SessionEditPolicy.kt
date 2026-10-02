package com.utbildning.tracker.domain

import java.time.LocalDate

/** User-entered results are limited to the local start date and the following day. */
internal object SessionEditPolicy {
    fun canEdit(sessionDate: Long, courseCompleted: Boolean, today: LocalDate): Boolean =
        !courseCompleted && (sessionDate == today.toEpochDay() || sessionDate == today.minusDays(1).toEpochDay())
}
