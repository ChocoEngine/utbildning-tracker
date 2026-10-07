package com.utbildning.tracker.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.MainActivity
import com.utbildning.tracker.R
import com.utbildning.tracker.maintenance.RetentionPolicy
import com.utbildning.tracker.maintenance.RetentionPreferences
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetentionSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val localeManager = context.getSystemService(LocaleManager::class.java)
    private lateinit var originalLocales: LocaleList
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun startWithDefaults() {
        originalLocales = localeManager.applicationLocales
        localeManager.applicationLocales = LocaleList.forLanguageTags("en")
        context.getSharedPreferences(RetentionPreferences.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun restoreState() {
        scenario?.close()
        context.getSharedPreferences(RetentionPreferences.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        localeManager.applicationLocales = originalLocales
    }

    @Test
    fun validatesWarnsAndPersistsRetentionAcrossRelaunch() {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("retention_current").performScrollTo().assertTextContains("365", substring = true)

        openEditor()
        compose.onNodeWithTag("retention_days").performTextReplacement("0")
        compose.onNodeWithTag("retention_save").assertIsNotEnabled()
        compose.onNodeWithText("Enter a whole number from 1 to 3650").assertIsDisplayed()

        compose.onNodeWithTag("retention_days").performTextReplacement("30")
        compose.onNodeWithTag("retention_save").performClick()
        compose.onNodeWithText("Keep fewer calendar sessions?").assertIsDisplayed()
        compose.onNodeWithTag("retention_warning_confirm").performClick()
        compose.onNodeWithTag("retention_current").assertTextContains("30", substring = true)
        assertEquals(RetentionPolicy(30), RetentionPreferences(context).policy())

        openEditor()
        compose.onNodeWithTag("retention_days").performTextReplacement("60")
        compose.onNodeWithTag("retention_save").performClick()
        compose.onNodeWithTag("retention_current").assertTextContains("60", substring = true)

        openEditor()
        compose.onNodeWithTag("retention_enabled").performClick()
        compose.onNodeWithTag("retention_save").performClick()
        compose.onNodeWithTag("retention_current").assertTextContains("Cleanup off", substring = true)
        assertEquals(RetentionPolicy.Disabled, RetentionPreferences(context).policy())

        openEditor()
        compose.onNodeWithTag("retention_enabled").performClick()
        compose.onNodeWithTag("retention_days").performTextReplacement("100")
        compose.onNodeWithTag("retention_save").performClick()
        compose.onNodeWithText("Keep fewer calendar sessions?").assertIsDisplayed()
        compose.onNodeWithTag("retention_warning_confirm").performClick()

        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("retention_current").performScrollTo().assertTextContains("100", substring = true)
        assertEquals(RetentionPolicy(100), RetentionPreferences(context).policy())
    }

    @Test
    fun retentionContractIsLocalizedInRussianAndEnglish() {
        val expected = listOf(
            "en" to listOf("Calendar history", "365", "1–3650", "cleanup off"),
            "ru" to listOf("История календаря", "365", "от 1 до 3650", "очистку"),
        )
        for ((language, fragments) in expected) {
            val localized = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(language))
                },
            )
            val text = listOf(
                localized.getString(R.string.retention_title),
                localized.getString(R.string.retention_dialog_explanation),
            ).joinToString(" ")
            fragments.forEach { fragment ->
                assertTrue("$language must contain $fragment: $text", text.contains(fragment, ignoreCase = true))
            }
        }
    }

    private fun openEditor() {
        compose.onNodeWithTag("retention_configure").performScrollTo().performClick()
        compose.onNodeWithTag("retention_enabled").assertIsDisplayed()
    }
}
