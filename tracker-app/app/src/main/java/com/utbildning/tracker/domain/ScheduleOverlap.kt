package com.utbildning.tracker.domain

import java.time.LocalDate

data class OccupiedSchedule(
    val courseId: String,
    val courseName: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate?,
    val rules: List<WeeklyRule>,
)

data class ScheduleOverlap(
    val courseId: String,
    val courseName: String,
    val rule: WeeklyRule,
    /** Other session's start day relative to the edited session's start day. */
    val dayOffset: Int,
)

/** Advisory occupancy in local wall-clock minutes; does not change session/reminder times. */
object ScheduleOverlaps {
    fun endMinute(rule: WeeklyRule): Int = rule.endMinute?.let {
        it + if (it < rule.startMinute) 1440 else 0
    } ?: minOf(rule.startMinute + 60, 1440)

    fun find(
        rule: WeeklyRule,
        from: LocalDate,
        through: LocalDate?,
        schedules: List<OccupiedSchedule>,
    ): List<ScheduleOverlap> = buildList {
        val end = endMinute(rule)
        if (end == rule.startMinute || (through != null && through < from)) return@buildList
        for (schedule in schedules) for (other in schedule.rules) {
            for (weekOffset in listOf(-7, 0, 7)) {
                val offset = other.dayOfWeek - rule.dayOfWeek + weekOffset
                if (offset !in -1..1) continue
                val otherStart = offset * 1440 + other.startMinute
                val otherEnd = offset * 1440 + endMinute(other)
                if (maxOf(rule.startMinute, otherStart) >= minOf(end, otherEnd)) continue

                // Dates refer to starts, so overnight rules shift the other schedule's bounds.
                val first = maxOf(from, schedule.startsOn.minusDays(offset.toLong()))
                val last = listOfNotNull(through, schedule.endsOn?.minusDays(offset.toLong())).minOrNull()
                val matchingDay = first.plusDays(((rule.dayOfWeek - first.dayOfWeek.value + 7) % 7).toLong())
                if (last == null || matchingDay <= last) {
                    add(ScheduleOverlap(schedule.courseId, schedule.courseName, other, offset))
                }
            }
        }
    }.sortedWith(compareBy({ it.dayOffset * 1440 + it.rule.startMinute }, { it.courseName }, { it.courseId }))
}
