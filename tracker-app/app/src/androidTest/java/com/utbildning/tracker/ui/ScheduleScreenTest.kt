package com.utbildning.tracker.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.domain.WeeklyRule
import com.utbildning.tracker.domain.OccupiedSchedule
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

    @Test fun addingEndSuggestsHourButCancelKeepsItAbsent() {
        var saved: List<WeeklyRule>? = null
        compose.setContent { TrackerTheme { ScheduleContent(initial = listOf(WeeklyRule(1, 1200)),
            onSave = { rules, _ -> saved = rules }) } }
        clickScrolled("schedule_add_end_1")
        assertSuggestedTime(21, 0)
        pickerButton(android.R.id.button2)
        compose.onNodeWithTag("schedule_end_1").assertDoesNotExist()
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle { assertEquals(listOf(WeeklyRule(1, 1200)), saved) }
        clickScrolled("schedule_add_end_1")
        assertSuggestedTime(21, 0)
        pickerButton(android.R.id.button1)
        compose.onNodeWithTag("schedule_remove_end_1").assertIsDisplayed()
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle { assertEquals(listOf(WeeklyRule(1, 1200, 1260)), saved) }
    }

    @Test fun addingEndNearMidnightSuggestsEndOfDay() {
        var saved: List<WeeklyRule>? = null
        compose.setContent { TrackerTheme { ScheduleContent(initial = listOf(WeeklyRule(1, 1410)),
            onSave = { rules, _ -> saved = rules }) } }
        clickScrolled("schedule_add_end_1")
        assertSuggestedTime(0, 0)
        pickerButton(android.R.id.button1)
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle { assertEquals(listOf(WeeklyRule(1, 1410, 0)), saved) }
    }

    private fun assertSuggestedTime(hour: Int, minute: Int) {
        compose.runOnIdle {
            val picker = windowViews().filterIsInstance<android.widget.TimePicker>().single()
            assertEquals(hour, picker.hour)
            assertEquals(minute, picker.minute)
        }
    }

    private fun pickerButton(id: Int) {
        compose.runOnIdle { windowViews().single { it.id == id }.performClick() }
        compose.waitForIdle()
    }

    private fun windowViews(): List<android.view.View> {
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()
        return android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap { descendants(it) }
    }

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

    @Test fun overnightWarningDisappearsAfterRemovingEndAndDoesNotBlockSaving() {
        val monday = LocalDate.of(2026, 9, 28)
        var saved: List<WeeklyRule>? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TrackerTheme { ScheduleContent(
            initial = listOf(WeeklyRule(1, 1410, 60)), today = monday,
            occupied = listOf(OccupiedSchedule("other", "Swedish", monday, null, listOf(WeeklyRule(2, 30, 90)))),
            onSave = { rules, _ -> saved = rules }) } }
        compose.onNodeWithTag("schedule_conflicts_1").assertExists()
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle { assertEquals(listOf(WeeklyRule(1, 1410, 60)), saved) }
        clickScrolled("schedule_remove_end_1")
        compose.onNodeWithTag("schedule_conflicts_1").assertDoesNotExist()
        compose.onNodeWithTag("schedule_end_1").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("schedule_end_1").assertDoesNotExist()
        compose.onNodeWithTag("schedule_save").performClick()
        compose.runOnIdle { assertEquals(listOf(WeeklyRule(1, 1410)), saved) }
    }

    @Test fun defaultHourWarnsAndUncheckingDayRemovesWarning() {
        val monday = LocalDate.of(2026, 9, 28)
        compose.setContent { TrackerTheme { ScheduleContent(
            initial = listOf(WeeklyRule(1, 1200)), today = monday,
            occupied = listOf(OccupiedSchedule("other", "C", monday, null, listOf(WeeklyRule(1, 1230))))) } }
        compose.onNodeWithTag("schedule_conflicts_1").assertExists()
        clickScrolled("schedule_day_1")
        compose.onNodeWithTag("schedule_conflicts_1").assertDoesNotExist()
    }

    @Test fun repositoryChangesRefreshWarningsInOpenScreen() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.utbildning.tracker.data.local.TrackerDatabase::class.java).build()
        try {
            val repo = com.utbildning.tracker.data.TrackerRepository(db)
            val current = repo.createCourse("Practice", 0)
            val other = repo.createCourse("C", 1)
            val day = LocalDate.now().dayOfWeek.value
            repo.saveInitialSchedule(current.id, listOf(WeeklyRule(day, 1200)))
            repo.saveInitialSchedule(other.id, listOf(WeeklyRule(day, 1230)))
            compose.setContent { TrackerTheme { com.utbildning.tracker.ui.schedule.ScheduleScreen(repo, current.id, {}) } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("schedule_conflicts_$day").fetchSemanticsNodes().isNotEmpty() }
            repo.disableSchedule(other.id)
            compose.waitUntil(5000) { compose.onAllNodesWithTag("schedule_conflicts_$day").fetchSemanticsNodes().isEmpty() }
        } finally { db.close() }
    }

    private fun clickScrolled(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
}
