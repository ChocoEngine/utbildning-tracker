package com.utbildning.tracker.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Counts the complete remaining schedule, independently of the generated calendar horizon. */
object TopicPace {
    fun perSession(remainingTopics: Int, startsOn: LocalDate, endsOn: LocalDate?,
        rules: List<WeeklyRule>, today: LocalDate, unavailableDates: Set<LocalDate> = emptySet()): Int? {
        if (remainingTopics <= 0 || endsOn == null || rules.isEmpty()) return null
        val first = maxOf(startsOn, today)
        if (first > endsOn) return null
        val days = rules.map { it.dayOfWeek }.toSet()
        var sessions = days.sumOf { weekday ->
            val offset = (weekday - first.dayOfWeek.value + 7) % 7
            val span = ChronoUnit.DAYS.between(first, endsOn) - offset
            if (span < 0) 0L else span / 7 + 1
        }
        sessions -= unavailableDates.count { it >= first && it <= endsOn && it.dayOfWeek.value in days }
        if (sessions <= 0) return null
        return ((remainingTopics.toLong() + sessions - 1) / sessions).toInt()
    }
}
