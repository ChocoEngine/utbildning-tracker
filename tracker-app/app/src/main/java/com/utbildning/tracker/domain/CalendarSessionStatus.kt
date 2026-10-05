package com.utbildning.tracker.domain

import com.utbildning.tracker.data.local.SessionResult
import java.time.LocalDate

/** Calendar-only projection: an unmarked past session looks skipped without changing storage. */
internal fun calendarSessionResult(result: SessionResult, sessionDate: Long, today: LocalDate): SessionResult =
    if (result == SessionResult.PENDING && sessionDate < today.toEpochDay()) SessionResult.SKIPPED else result
