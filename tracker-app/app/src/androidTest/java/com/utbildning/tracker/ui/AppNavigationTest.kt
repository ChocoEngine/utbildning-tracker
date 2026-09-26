package com.utbildning.tracker.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsReturnsToEachOriginTab() {
        for (tab in listOf("today", "calendar", "courses")) {
            compose.onNodeWithTag("nav_$tab").performClick()
            compose.onNodeWithTag("screen_$tab").assertIsDisplayed()
            compose.onNodeWithTag("nav_$tab").assertIsSelected()
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("screen_settings").assertIsDisplayed()
            compose.onNodeWithTag("back").performClick()
            compose.onNodeWithTag("screen_$tab").assertIsDisplayed()
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("screen_settings").assertIsDisplayed()
            pressBack()
            compose.onNodeWithTag("screen_$tab").assertIsDisplayed()
        }
    }

    @Test
    fun repeatedTabSelectionDoesNotTrapBackAndRecreationKeepsDestination() {
        compose.onNodeWithTag("nav_courses").performClick()
        repeat(3) { compose.onNodeWithTag("nav_courses").performClick() }
        compose.onNodeWithTag("settings").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("screen_settings").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("screen_courses").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("screen_today").assertIsDisplayed()
    }

    private fun pressBack() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
