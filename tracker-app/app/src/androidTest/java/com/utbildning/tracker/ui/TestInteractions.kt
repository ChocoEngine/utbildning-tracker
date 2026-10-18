package com.utbildning.tracker.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry

/** The category list is intentionally hidden while the add field's IME is visible. */
internal fun ComposeTestRule.selectCategoryOption(tag: String) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.serviceInfo = automation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    if (automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("input keyevent KEYCODE_BACK"),
        ).use { it.readBytes() }
    }
    waitUntil(5_000) {
        runCatching { onNodeWithTag(tag).performScrollTo().assertIsDisplayed() }.isSuccess
    }
    onNodeWithTag(tag).performClick()
    waitForIdle()
}
