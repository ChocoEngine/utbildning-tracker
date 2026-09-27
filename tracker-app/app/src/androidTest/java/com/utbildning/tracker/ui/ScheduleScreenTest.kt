package com.utbildning.tracker.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.domain.WeeklyRule
import com.utbildning.tracker.ui.schedule.ScheduleContent
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun customTuesdayAndSaturdayTimesArePassedToSaveUnchanged() {
        val rules = listOf(WeeklyRule(2, 19 * 60), WeeklyRule(6, 11 * 60, 12 * 60 + 30))
        val end = LocalDate.now().plusDays(30).toEpochDay()
        var saved: Pair<List<WeeklyRule>, Long?>? = null
        compose.setContent { TrackerTheme { ScheduleContent(initial = rules, initialEnd = end,
            onSave = { selected, date -> saved = selected to date }) } }
        compose.onNodeWithTag("schedule_day_2").assertIsOn()
        compose.onNodeWithTag("schedule_day_6").assertIsOn()
        compose.onNodeWithTag("schedule_day_1").assertIsOff()
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle { assertEquals(rules to end, saved) }
    }

    @Test fun dateDialogCancelPreservesDateApplyKeepsSelectionAndClearRemovesLimit() {
        val end = LocalDate.now().plusDays(30).toEpochDay()
        var saved: Long? = null
        var calls = 0
        compose.setContent { TrackerTheme { ScheduleContent(initial = listOf(WeeklyRule(2, 1140)), initialEnd = end,
            onSave = { _, date -> saved = date; calls++ }) } }
        clickScrolled("schedule_date")
        compose.onNodeWithTag("schedule_date_cancel").performClick()
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle { assertEquals(end, saved); assertEquals(1, calls) }
        clickScrolled("schedule_date")
        compose.onNodeWithTag("schedule_date_apply").performClick()
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle { assertEquals(end, saved); assertEquals(2, calls) }
        clickScrolled("schedule_clear_date")
        compose.onNodeWithTag("schedule_clear_date").assertDoesNotExist()
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle { assertNull(saved); assertEquals(3, calls) }
    }

    @Test fun noWeekdaysAndNoDateSavesUnscheduledAndSelectingDayEnablesSchedule() {
        var saved: List<WeeklyRule>? = null
        compose.setContent { TrackerTheme { ScheduleContent(onSave = { rules, _ -> saved = rules }) } }
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle { assertEquals(emptyList<WeeklyRule>(), saved) }
        clickScrolled("schedule_day_2")
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle { assertEquals(listOf(WeeklyRule(2, 1200)), saved) }
    }

    @Test fun endDateWithoutWeekdaysCanBeSavedAndCleared() {
        val end = LocalDate.now().plusDays(30).toEpochDay()
        var saved: Pair<List<WeeklyRule>, Long?>? = null
        compose.setContent { TrackerTheme { ScheduleContent(initialEnd = end,
            onSave = { rules, date -> saved = rules to date }) } }
        clickScrolled("schedule_date")
        compose.onNodeWithTag("schedule_date_apply").performClick()
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle { assertEquals(emptyList<WeeklyRule>() to end, saved) }
        clickScrolled("schedule_clear_date")
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle { assertEquals(emptyList<WeeklyRule>() to null, saved) }
    }

    @Test fun savedInstanceRestorationRetainsChangedDaysAndClearedDate() {
        val restoration = StateRestorationTester(compose)
        var saved: Pair<List<WeeklyRule>, Long?>? = null
        restoration.setContent { TrackerTheme { ScheduleContent(
            initial = listOf(WeeklyRule(6, 660)), initialEnd = LocalDate.now().plusDays(20).toEpochDay(),
            onSave = { rules, date -> saved = rules to date }) } }
        clickScrolled("schedule_day_2")
        clickScrolled("schedule_day_6")
        clickScrolled("schedule_clear_date")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("schedule_day_2").assertIsOn()
        compose.onNodeWithTag("schedule_day_6").assertIsOff()
        compose.onNodeWithTag("schedule_clear_date").assertDoesNotExist()
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle { assertEquals(listOf(WeeklyRule(2, 1200)) to null, saved) }
    }

    @Test fun completedCourseScheduleIsReadOnly() {
        compose.setContent { TrackerTheme { ScheduleContent(initial = listOf(WeeklyRule(2, 1140)), readOnly = true) } }
        compose.onNodeWithTag("schedule_day_2").assertIsNotEnabled()
        compose.onNodeWithTag("schedule_start_2").assertIsNotEnabled()
        compose.onNodeWithTag("schedule_date").assertIsNotEnabled()
        compose.onNodeWithTag("schedule_save").assertDoesNotExist()
    }

    private fun clickScrolled(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
}
