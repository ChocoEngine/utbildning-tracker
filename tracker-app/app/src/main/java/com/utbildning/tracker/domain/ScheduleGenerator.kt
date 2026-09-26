package com.utbildning.tracker.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class WeeklyRule(
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int? = null,
    val endDayOffset: Int = 0,
) {
    init {
        require(dayOfWeek in 1..7 && startMinute in 0..1439)
        require(endMinute == null || endMinute in 0..1439)
        require(endDayOffset in 0..1)
        require(if (endMinute == null) endDayOffset == 0
            else endMinute + endDayOffset * 1440 > startMinute)
        require(endMinute == null || endMinute + endDayOffset * 1440 - startMinute <= 1440)
    }
}

data class ScheduledOccurrence(
    val date: LocalDate,
    val startMinute: Int,
    val endMinute: Int?,
    val endDayOffset: Int,
) {
    fun startAt(zone: ZoneId): Instant = SessionTime.start(date, startMinute, zone)
    fun questionAt(zone: ZoneId): Instant = SessionTime.question(date, startMinute, endMinute, endDayOffset, zone)
}

object SessionTime {
    fun start(date: LocalDate, minute: Int, zone: ZoneId): Instant =
        date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant()

    fun question(date: LocalDate, startMinute: Int, endMinute: Int?, endDayOffset: Int, zone: ZoneId): Instant {
        val start = start(date, startMinute, zone)
        if (endMinute == null) return start.plusSeconds(90 * 60)
        val end = start(date.plusDays(endDayOffset.toLong()), endMinute, zone)
        return if (end > start) end else start.plusSeconds((endMinute + 1440L * endDayOffset - startMinute) * 60)
    }
}

object ScheduleGenerator {
    fun generate(
        startsOn: LocalDate,
        endsOn: LocalDate?,
        rules: List<WeeklyRule>,
        from: LocalDate,
        through: LocalDate,
    ): List<ScheduledOccurrence> {
        require(endsOn == null || endsOn >= startsOn)
        require(rules.map { it.dayOfWeek }.distinct().size == rules.size)
        val first = maxOf(startsOn, from)
        val last = minOf(endsOn ?: through, through)
        if (first > last) return emptyList()
        val result = mutableListOf<ScheduledOccurrence>()
        for (rule in rules) {
            val delta = (DayOfWeek.of(rule.dayOfWeek).value - first.dayOfWeek.value + 7) % 7
            var day = first.plusDays(delta.toLong())
            while (day <= last) {
                result.add(ScheduledOccurrence(day, rule.startMinute, rule.endMinute, rule.endDayOffset))
                day = day.plusWeeks(1)
            }
        }
        return result.sortedWith(compareBy<ScheduledOccurrence> { it.date }.thenBy { it.startMinute })
    }
}
