package com.utbildning.tracker.domain

import java.time.Instant
import java.time.ZoneId

fun millisUntilNextLocalDay(now: Long, zone: ZoneId): Long {
    val instant = Instant.ofEpochMilli(now)
    val next = instant.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
    return (next.toEpochMilli() - now).coerceAtLeast(1L)
}
