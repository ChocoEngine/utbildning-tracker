package com.utbildning.tracker.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.data.local.CourseEntity
import com.utbildning.tracker.data.local.ScheduleRuleEntity
import com.utbildning.tracker.ui.courses.CourseListContent
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseTopicPaceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun russianNarrowListShowsPaceAfterCountAndHidesCompletedPace() = check("ru", "5 из 24 тем · 3 темы за занятие", "21 из 24 тем · 1 тема за занятие", "24 из 24 тем")
    @Test fun englishNarrowListShowsPaceAfterCountAndHidesCompletedPace() = check("en", "5 of 24 topics · 3 topics per session", "21 of 24 topics · 1 topic per session", "24 of 24 topics")

    private fun check(language: String, ordinary: String, one: String, completed: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val localized = context.createConfigurationContext(config)
        val courses = (0..2).map { CourseEntity("pace-$it", "C ${it + 1}", it, 1, 1) }
        val rules = courses.associate { it.id to listOf(ScheduleRuleEntity(it.id, 2, 1140)) }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
                TrackerTheme { Surface(Modifier.width(320.dp)) {
                    CourseListContent(courses, emptyList(), mapOf("pace-0" to (5 to 24), "pace-1" to (21 to 24), "pace-2" to (24 to 24)),
                        false, {}, {}, rules, mapOf("pace-0" to 3, "pace-1" to 1, "pace-2" to 1))
                } }
            }
        }
        compose.onNodeWithText(ordinary).assertIsDisplayed()
        compose.onNodeWithText(one).assertIsDisplayed()
        compose.onNodeWithText(completed).assertIsDisplayed()
        java.io.File(context.getExternalFilesDir(null), "topic-pace-$language.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        for ((text, percent) in listOf(ordinary to "21%", one to "88%", completed to "100%")) {
            val label = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val value = compose.onNodeWithText(percent, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Text must stay before percentage", label.right <= value.left)
        }
        val quantity = localized.resources
        assertTrue(quantity.getQuantityString(com.utbildning.tracker.R.plurals.course_topic_pace, 11, 11).contains("11"))
    }
}
