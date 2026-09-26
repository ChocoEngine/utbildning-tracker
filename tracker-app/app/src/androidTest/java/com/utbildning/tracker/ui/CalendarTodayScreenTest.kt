package com.utbildning.tracker.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.local.SessionEntity
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.ui.calendar.CalendarContent
import com.utbildning.tracker.ui.today.TodayContent
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.time.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarTodayScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun calendarNavigatesLeapMonthAndRoutesResultsForSelectedDay() {
        val leapDay = LocalDate.of(2028, 2, 29)
        val month = mutableStateOf(YearMonth.from(leapDay))
        val selected = mutableStateOf(leapDay.minusDays(1))
        val sessions = listOf(lesson("first", leapDay, 600), lesson("second", leapDay, 720, result = SessionResult.DONE))
        var done: String? = null
        var skipped: String? = null
        compose.setContent { TrackerTheme { CalendarContent(month.value, selected.value, sessions, Locale.ENGLISH,
            onMonth = { month.value = it; selected.value = it.atDay(1) }, onDay = { selected.value = it },
            onDone = { done = it }, onSkip = { skipped = it }) } }
        compose.onNodeWithTag("session_done_first").assertDoesNotExist()
        click("calendar_day_${leapDay.toEpochDay()}")
        click("session_done_first")
        click("session_skip_second")
        compose.runOnIdle { assertEquals("first", done); assertEquals("second", skipped) }
        click("calendar_next")
        compose.onNodeWithTag("calendar_day_${LocalDate.of(2028, 3, 31).toEpochDay()}").assertExists()
        compose.onNodeWithTag("calendar_day_${leapDay.toEpochDay()}").assertDoesNotExist()
        compose.onNodeWithTag("session_done_first").assertDoesNotExist()
        click("calendar_prev")
        click("calendar_day_${leapDay.toEpochDay()}")
        compose.onNodeWithTag("session_done_first").assertExists()
    }

    @Test fun calendarShowsOneInkAndPerSessionBarsWithSkippedMarks() {
        val date = LocalDate.of(2028, 2, 29)
        val sessions = listOf(
            lesson("pending", date, 600, result = SessionResult.PENDING).copy(colorIdSnapshot = 0),
            lesson("done", date, 630, result = SessionResult.DONE).copy(colorIdSnapshot = 1),
            lesson("skip_a", date, 660, result = SessionResult.SKIPPED).copy(colorIdSnapshot = 2),
            lesson("skip_b", date, 690, result = SessionResult.SKIPPED).copy(colorIdSnapshot = 3),
        )
        compose.setContent { TrackerTheme { CalendarContent(YearMonth.from(date), date, sessions, Locale.ENGLISH, {}, {}, {}, {}) } }
        val description = compose.onNodeWithTag("calendar_day_${date.toEpochDay()}").fetchSemanticsNode().config.toString()
        assertTrue(description.contains("Planned")); assertTrue(description.contains("Done")); assertTrue(description.contains("Skipped"))
    }

    @Test fun todayOmitsPastShowsNearestFutureAndQuestionsFollowEndOrNinetyMinutes() {
        val day = LocalDate.of(2026, 9, 26)
        val now = mutableStateOf(Instant.parse("2026-09-26T11:29:00Z"))
        val sessions = listOf(
            lesson("past", day.minusDays(1), 600),
            lesson("default", day, 600),
            lesson("explicit", day, 540, end = 720),
            lesson("done", day, 480, result = SessionResult.DONE),
            lesson("near", day.plusDays(1), 600),
            lesson("far", day.plusDays(2), 600),
        )
        var done: String? = null
        compose.setContent { TrackerTheme { TodayContent(sessions, now.value, ZoneOffset.UTC, Locale.ENGLISH,
            onDone = { done = it }, onSkip = {}) } }
        compose.onNodeWithTag("session_done_past").assertDoesNotExist()
        compose.onNodeWithTag("session_done_far").assertDoesNotExist()
        compose.onNodeWithTag("session_done_near").assertExists()
        compose.onNodeWithTag("question_default").assertDoesNotExist()
        compose.onNodeWithTag("question_explicit").assertDoesNotExist()
        click("session_done_default")
        compose.runOnIdle { assertEquals("default", done); now.value = Instant.parse("2026-09-26T11:30:00Z") }
        compose.onNodeWithTag("question_default").assertExists()
        compose.onNodeWithTag("question_explicit").assertDoesNotExist()
        compose.runOnIdle { now.value = Instant.parse("2026-09-26T12:00:00Z") }
        compose.onNodeWithTag("question_explicit").assertExists()
        compose.onNodeWithTag("question_done").assertDoesNotExist()
    }

    @Test fun localMidnightRemovesYesterdayFromTodayWithoutDisplayingOldQuestion() {
        val day = LocalDate.of(2026, 9, 26)
        val now = mutableStateOf(Instant.parse("2026-09-26T20:59:00Z"))
        val sessions = listOf(lesson("yesterday", day, 600), lesson("newday", day.plusDays(1), 600))
        compose.setContent { TrackerTheme { TodayContent(sessions, now.value, ZoneId.of("Europe/Moscow"), Locale.ENGLISH, {}, {}) } }
        compose.onNodeWithTag("question_yesterday").assertExists()
        compose.runOnIdle { now.value = Instant.parse("2026-09-26T21:00:00Z") }
        compose.onNodeWithTag("session_done_yesterday").assertDoesNotExist()
        compose.onNodeWithTag("question_yesterday").assertDoesNotExist()
        compose.onNodeWithTag("session_done_newday").assertExists()
        compose.onNodeWithTag("question_newday").assertDoesNotExist()
    }

    private fun lesson(id: String, day: LocalDate, start: Int, end: Int? = null, result: SessionResult = SessionResult.PENDING) =
        SessionEntity(id, "course_$id", day.toEpochDay(), start, id, 0, 1, 1, endMinute = end, result = result)
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
}
