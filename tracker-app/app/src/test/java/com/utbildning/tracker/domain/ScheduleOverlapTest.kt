package com.utbildning.tracker.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ScheduleOverlapTest {
    private val monday = LocalDate.of(2026, 9, 28)
    private fun occupied(rule: WeeklyRule, start: LocalDate = monday, end: LocalDate? = null) =
        OccupiedSchedule("other", "C", start, end, listOf(rule))
    private fun find(rule: WeeklyRule, other: WeeklyRule) =
        ScheduleOverlaps.find(rule, monday, null, listOf(occupied(other)))

    @Test fun partialAndEqualStartOverlapButTouchingAndZeroDurationDoNot() {
        assertEquals(1, find(WeeklyRule(1, 1200, 1260), WeeklyRule(1, 1170, 1230)).size)
        assertEquals(1, find(WeeklyRule(1, 1200, 1260), WeeklyRule(1, 1200, 1230)).size)
        assertTrue(find(WeeklyRule(1, 1200, 1260), WeeklyRule(1, 1260, 1320)).isEmpty())
        assertTrue(find(WeeklyRule(1, 1200, 1200), WeeklyRule(1, 1170, 1260)).isEmpty())
        assertTrue(find(WeeklyRule(1, 1200, 1260), WeeklyRule(1, 1230, 1230)).isEmpty())
    }
    @Test fun missingEndIsOneHourForBothCoursesAndStopsAtMidnight() {
        assertEquals(1260, ScheduleOverlaps.endMinute(WeeklyRule(1, 1200)))
        assertEquals(1440, ScheduleOverlaps.endMinute(WeeklyRule(1, 1410)))
        assertEquals(1, find(WeeklyRule(1, 1200), WeeklyRule(1, 1230)).size)
        assertTrue(find(WeeklyRule(1, 1200), WeeklyRule(1, 1260)).isEmpty())
        assertTrue(find(WeeklyRule(1, 1410), WeeklyRule(2, 0, 30)).isEmpty())
    }
    @Test fun explicitOvernightChecksAdjacentDaysAndWeekBoundary() {
        assertEquals(1, find(WeeklyRule(1, 1410, 60), WeeklyRule(2, 30, 90)).single().dayOffset)
        assertEquals(-1, find(WeeklyRule(2, 30, 90), WeeklyRule(1, 1410, 60)).single().dayOffset)
        assertEquals(1, find(WeeklyRule(7, 1410, 60), WeeklyRule(1, 30, 90)).single().dayOffset)
        assertEquals(-1, find(WeeklyRule(1, 30, 90), WeeklyRule(7, 1410, 60)).single().dayOffset)
    }
    @Test fun scheduleDateBoundsMustContainAnActualSharedWeekday() {
        val rule = WeeklyRule(1, 1200)
        assertTrue(ScheduleOverlaps.find(rule, monday, monday.plusDays(3), listOf(occupied(rule, monday.plusDays(1)))).isEmpty())
        assertEquals(1, ScheduleOverlaps.find(rule, monday, monday.plusDays(7), listOf(occupied(rule, monday.plusDays(1)))).size)
        assertTrue(ScheduleOverlaps.find(rule, monday, null, listOf(occupied(rule, monday.minusDays(7), monday.minusDays(1)))).isEmpty())
        assertTrue(ScheduleOverlaps.find(rule, monday, monday.minusDays(1), listOf(occupied(rule))).isEmpty())
    }
    @Test fun overnightBoundsReferToStartDatesIncludingPreviousDay() {
        val sunday = monday.minusDays(1)
        val previous = occupied(WeeklyRule(7, 1410, 60), sunday, sunday)
        assertEquals(1, ScheduleOverlaps.find(WeeklyRule(1, 30, 90), monday, monday, listOf(previous)).size)
        val next = occupied(WeeklyRule(2, 30, 90), monday.plusDays(1), monday.plusDays(1))
        assertEquals(1, ScheduleOverlaps.find(WeeklyRule(1, 1410, 60), monday, monday, listOf(next)).size)
    }
    @Test fun findsMultipleCoursesBeyondCalendarGenerationHorizon() {
        val rule = WeeklyRule(1, 1200, 1320)
        val schedules = listOf(occupied(WeeklyRule(1, 1230), monday.plusDays(365)),
            occupied(WeeklyRule(1, 1290)).copy(courseId = "second"))
        assertEquals(2, ScheduleOverlaps.find(rule, monday, null, schedules).size)
    }
    @Test fun emulatorThursdayElevenToFiveThirtyIncludesEveningCoursesButDaytimeDoesNot() {
        val thursday = LocalDate.of(2026, 10, 1)
        val schedules = listOf(
            OccupiedSchedule("landscape", "Пейзажи", thursday, null, (1..7).map { WeeklyRule(it, 540) }),
            OccupiedSchedule("conversation", "Разговорная практика", thursday, null, listOf(WeeklyRule(2, 1140), WeeklyRule(4, 1140))),
            OccupiedSchedule("swedish", "Шведский · A2", thursday, null, (1..7).map { WeeklyRule(it, 1230) }),
        )
        val overnight = ScheduleOverlaps.find(WeeklyRule(4, 660, 330), thursday, null, schedules)
        assertEquals(listOf("conversation", "swedish"), overnight.map { it.courseId })
        assertEquals(listOf(0, 0), overnight.map { it.dayOffset })
        assertEquals(listOf(1200, 1290), overnight.map { ScheduleOverlaps.endMinute(it.rule) })
        assertTrue(ScheduleOverlaps.find(WeeklyRule(4, 660, 1050), thursday, null, schedules).isEmpty())
        assertTrue(ScheduleOverlaps.find(WeeklyRule(4, 660), thursday, null, schedules).isEmpty())
        assertTrue(ScheduleOverlaps.find(WeeklyRule(4, 660, 750), thursday, null, schedules).isEmpty())
    }

    @Test fun boundedWeeklyCalculationMatchesExplicitCalendarIntervals() {
        val random = kotlin.random.Random(20261001)
        repeat(1000) {
            fun rule(): WeeklyRule {
                val start = random.nextInt(48) * 30
                return WeeklyRule(random.nextInt(1, 8), start, if (random.nextBoolean()) null else random.nextInt(48) * 30)
            }
            val draft = rule()
            val other = rule()
            val from = monday.plusDays(random.nextInt(14).toLong())
            val through = from.plusDays(random.nextInt(15).toLong())
            val otherFrom = monday.minusDays(7).plusDays(random.nextInt(28).toLong())
            val otherThrough = otherFrom.plusDays(random.nextInt(15).toLong())
            fun intervals(rule: WeeklyRule, start: LocalDate, end: LocalDate): List<Pair<Long, Long>> {
                val result = mutableListOf<Pair<Long, Long>>()
                var date = start
                while (date <= end) {
                    if (date.dayOfWeek.value == rule.dayOfWeek) {
                        val begin = date.toEpochDay() * 1440 + rule.startMinute
                        val finish = if (rule.endMinute == null) minOf(begin + 60, (date.toEpochDay() + 1) * 1440)
                            else date.toEpochDay() * 1440 + rule.endMinute + if (rule.endMinute < rule.startMinute) 1440 else 0
                        result += begin to finish
                    }
                    date = date.plusDays(1)
                }
                return result
            }
            val expected = intervals(draft, from, through).any { a ->
                intervals(other, otherFrom, otherThrough).any { b -> maxOf(a.first, b.first) < minOf(a.second, b.second) }
            }
            val actual = ScheduleOverlaps.find(draft, from, through, listOf(occupied(other, otherFrom, otherThrough))).isNotEmpty()
            assertEquals("draft=$draft other=$other dates=$from..$through / $otherFrom..$otherThrough", expected, actual)
        }
    }
}
