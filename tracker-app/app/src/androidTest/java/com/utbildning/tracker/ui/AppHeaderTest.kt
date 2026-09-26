package com.utbildning.tracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.utbildning.tracker.ui.theme.TrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longTitleLeavesSettingsAccessibleInNarrowWindowWithLargeText() {
        val title = "Очень длинное название раздела приложения для самостоятельной учёбы"
        var clicks = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                TrackerTheme {
                    Box(Modifier.width(320.dp)) {
                        AppHeader(title = title, onSettings = { clicks++ })
                    }
                }
            }
        }
        val titleBounds = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val buttonBounds = compose.onNodeWithTag("settings").fetchSemanticsNode().boundsInRoot
        assertTrue("Title overlaps settings", titleBounds.right <= buttonBounds.left)
        compose.onNodeWithTag("settings").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
