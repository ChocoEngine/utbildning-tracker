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

    @Test fun calendarNavigatesLeapMonthAndKeepsSelectedDayReadOnly() {
        val leapDay = LocalDate.of(2028, 2, 29)
        val month = mutableStateOf(YearMonth.from(leapDay))
        val selected = mutableStateOf(leapDay.minusDays(1))
        val sessions = listOf(lesson("first", leapDay, 600), lesson("second", leapDay, 720, result = SessionResult.DONE))
        compose.setContent { TrackerTheme { CalendarContent(month.value, selected.value, sessions, Locale.ENGLISH,
            onMonth = { month.value = it; selected.value = it.atDay(1) }, onDay = { selected.value = it }) } }
        compose.onNodeWithTag("session_done_first").assertDoesNotExist()
        click("calendar_day_${leapDay.toEpochDay()}")
        compose.onNodeWithTag("calendar_session_first").assertHasNoClickAction()
        compose.onNodeWithTag("session_skip_second").assertDoesNotExist()
        compose.onNodeWithTag("calendar_grid").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("calendar_day_${LocalDate.of(2028, 3, 31).toEpochDay()}").assertExists()
        compose.onNodeWithTag("calendar_day_${leapDay.toEpochDay()}").assertDoesNotExist()
        compose.onNodeWithTag("session_done_first").assertDoesNotExist()
        compose.onNodeWithTag("calendar_grid").performTouchInput { swipeRight() }
        click("calendar_day_${leapDay.toEpochDay()}")
        compose.onNodeWithTag("calendar_session_first").assertExists()
        click("calendar_today")
        compose.runOnIdle { assertEquals(YearMonth.now(), month.value); assertEquals(LocalDate.now(), selected.value) }
    }

    @Test fun calendarDaySemanticsDescribePendingDoneAndSkippedSessions() {
        val date = LocalDate.of(2028, 2, 29)
        val sessions = listOf(
            lesson("pending", date, 600, result = SessionResult.PENDING).copy(colorId = 0),
            lesson("done", date, 630, result = SessionResult.DONE).copy(colorId = 1),
            lesson("skip_a", date, 660, result = SessionResult.SKIPPED).copy(colorId = 2),
            lesson("skip_b", date, 690, result = SessionResult.SKIPPED).copy(colorId = 3),
        )
        compose.setContent { TrackerTheme { CalendarContent(YearMonth.from(date), date, sessions, Locale.ENGLISH, {}, {}, today = date) } }
        val description = compose.onNodeWithTag("calendar_day_${date.toEpochDay()}").fetchSemanticsNode().config.toString()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        listOf(com.utbildning.tracker.R.string.session_planned, com.utbildning.tracker.R.string.session_done,
            com.utbildning.tracker.R.string.session_skipped).forEach { assertTrue(description.contains(context.getString(it))) }
    }

    @Test fun calendarProjectsYesterdayPendingAsSkippedWithoutMutatingInput() {
        val today = LocalDate.of(2028, 3, 1)
        val yesterday = today.minusDays(1)
        val pending = lesson("pending_yesterday", yesterday, 600)
        compose.setContent { TrackerTheme { CalendarContent(YearMonth.from(yesterday), yesterday, listOf(pending), Locale.ENGLISH, {}, {}, today = today) } }

        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val description = compose.onNodeWithTag("calendar_day_${yesterday.toEpochDay()}").fetchSemanticsNode().config.toString()
        assertTrue(description.contains(context.getString(com.utbildning.tracker.R.string.session_skipped)))
        compose.onNodeWithText(context.getString(com.utbildning.tracker.R.string.session_skipped)).assertExists()
        assertEquals(SessionResult.PENDING, pending.result)
    }

    @Test fun todayShowsYesterdayButOmitsOlderAndFutureSessions() {
        val day = LocalDate.of(2026, 9, 26)
        val now = mutableStateOf(Instant.parse("2026-09-26T11:29:00Z"))
        val sessions = listOf(
            lesson("old", day.minusDays(2), 600),
            lesson("yesterday", day.minusDays(1), 600, result = SessionResult.SKIPPED),
            lesson("completed_yesterday", day.minusDays(1), 660).copy(courseCompleted = true),
            lesson("default", day, 600),
            lesson("explicit", day, 540, end = 720),
            lesson("done", day, 480, result = SessionResult.DONE),
            lesson("near", day.plusDays(1), 600),
            lesson("far", day.plusDays(2), 600),
        )
        var done: String? = null
        compose.setContent { TrackerTheme { TodayContent(sessions, now.value, ZoneOffset.UTC, Locale.ENGLISH,
            onDone = { done = it }, onSkip = {}) } }
        compose.onNodeWithTag("session_done_old").assertDoesNotExist()
        compose.onNodeWithTag("session_done_far").assertDoesNotExist()
        compose.onNodeWithTag("session_done_near").assertDoesNotExist()
        compose.onNodeWithTag("session_done_completed_yesterday").assertDoesNotExist()
        compose.onNodeWithTag("today_yesterday").assertExists()
        click("session_done_yesterday")
        compose.runOnIdle { assertEquals("yesterday", done) }
        click("session_done_default")
        compose.runOnIdle { assertEquals("default", done); now.value = Instant.parse("2026-09-26T11:30:00Z") }
        compose.runOnIdle { now.value = Instant.parse("2026-09-26T12:00:00Z") }
    }

    @Test fun localMidnightMovesSessionIntoEditableYesterdaySectionWithoutOldQuestion() {
        val day = LocalDate.of(2026, 9, 26)
        val now = mutableStateOf(Instant.parse("2026-09-26T20:59:00Z"))
        val sessions = listOf(lesson("yesterday", day, 600), lesson("newday", day.plusDays(1), 600))
        compose.setContent { TrackerTheme { TodayContent(sessions, now.value, ZoneId.of("Europe/Moscow"), Locale.ENGLISH, {}, {}) } }
        compose.onNodeWithTag("question_yesterday").assertDoesNotExist()
        compose.runOnIdle { now.value = Instant.parse("2026-09-26T21:00:00Z") }
        compose.onNodeWithTag("today_yesterday").assertExists()
        compose.onNodeWithTag("session_done_yesterday").assertExists()
        compose.onNodeWithTag("question_yesterday").assertDoesNotExist()
        compose.onNodeWithTag("session_done_newday").assertExists()
        compose.onNodeWithTag("question_newday").assertDoesNotExist()
    }

    @Test fun todayAndYesterdayListsScrollIndependently() {
        val day = LocalDate.of(2026, 9, 26)
        val today = (0..7).map {
            lesson("today_$it", day, 8 * 60 + it * 10).let { session ->
                if (it == 7) session.copy(courseName = "Algorithms and data structures practice in C") else session
            }
        }
        val yesterday = (0..7).map { lesson("yesterday_$it", day.minusDays(1), 8 * 60 + it * 10, result = SessionResult.SKIPPED) }
        compose.setContent {
            TrackerTheme {
                TodayContent(today + yesterday, Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC, Locale.ENGLISH, {}, {})
            }
        }

        compose.onNodeWithTag("today_sessions").performScrollToIndex(today.lastIndex)
        compose.onNodeWithTag("session_done_today_7").assertIsDisplayed()
        val timeBounds = compose.onNodeWithTag("session_time_today_7").fetchSemanticsNode().boundsInRoot
        val blotBounds = compose.onNodeWithTag("session_blot_today_7").fetchSemanticsNode().boundsInRoot
        val titleBounds = compose.onNodeWithTag("session_title_today_7").fetchSemanticsNode().boundsInRoot
        val listBounds = compose.onNodeWithTag("today_sessions").fetchSemanticsNode().boundsInRoot
        assertTrue(blotBounds.left < titleBounds.left)
        assertTrue(titleBounds.right < timeBounds.left)
        assertTrue(titleBounds.height > timeBounds.height)
        assertEquals(listBounds.right, timeBounds.right, 1f)
        assertEquals((timeBounds.top + timeBounds.bottom) / 2, (blotBounds.top + blotBounds.bottom) / 2, 1f)
        val yesterdayHeaderTop = compose.onNodeWithTag("today_yesterday").fetchSemanticsNode().boundsInRoot.top
        val dayDividerWidth = compose.onNodeWithTag("day_sections_divider").fetchSemanticsNode().boundsInRoot.width
        val sessionDivider = compose.onNodeWithTag("session_divider_today_7").fetchSemanticsNode().boundsInRoot
        val sessionDividerWidth = sessionDivider.width
        val buttonLeft = compose.onNodeWithTag("session_done_today_7").fetchSemanticsNode().boundsInRoot.left
        assertTrue(sessionDividerWidth < dayDividerWidth)
        assertEquals(buttonLeft, sessionDivider.left)

        compose.onNodeWithTag("yesterday_sessions").performScrollToIndex(yesterday.lastIndex)
        compose.onNodeWithTag("session_done_yesterday_7").assertIsDisplayed()
        compose.onNodeWithTag("session_done_today_7").assertIsDisplayed()
        assertEquals(yesterdayHeaderTop, compose.onNodeWithTag("today_yesterday").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun todayAndYesterdayUseMatchingSectionsAndSeparateEmptyMessages() {
        val day = LocalDate.of(2026, 9, 26)
        compose.setContent {
            TrackerTheme {
                TodayContent(emptyList(), Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC, Locale.ENGLISH, {}, {})
            }
        }

        val settings = compose.onNodeWithTag("settings").fetchSemanticsNode().boundsInRoot
        val todayHeader = compose.onNodeWithTag("today_date").fetchSemanticsNode().boundsInRoot
        val yesterdayHeader = compose.onNodeWithTag("today_yesterday").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("today_empty").assertExists()
        compose.onNodeWithTag("yesterday_empty").assertExists()
        compose.onNodeWithTag("day_sections_divider").assertExists()
        compose.onNodeWithTag("today_sessions").assertDoesNotExist()
        compose.onNodeWithTag("yesterday_sessions").assertDoesNotExist()
        assertTrue(todayHeader.top >= settings.bottom)
        assertEquals(todayHeader.left, yesterdayHeader.left)
    }

    private fun lesson(id: String, day: LocalDate, start: Int, end: Int? = null, result: SessionResult = SessionResult.PENDING) =
        SessionEntity(id, "course_$id", day.toEpochDay(), start, id, 0, 1, 1, endMinute = end, result = result)
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
}
