package com.utbildning.tracker.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.utbildning.tracker.domain.OccupiedSchedule
import com.utbildning.tracker.domain.WeeklyRule
import com.utbildning.tracker.ui.schedule.ScheduleContent
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.io.File
import java.time.LocalDate
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class ScheduleOverlapReviewTest {
    @get:Rule val compose = createComposeRule()
    @Test fun russian() = review("ru")
    @Test fun english() = review("en")

    private fun review(language: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val localized = context.createConfigurationContext(config)
        val monday = LocalDate.of(2026, 9, 28)
        val scene = mutableIntStateOf(0)
        val drafts = listOf(WeeklyRule(1, 1200, 1260), WeeklyRule(1, 1200, 1320),
            WeeklyRule(1, 1200), WeeklyRule(1, 1410), WeeklyRule(1, 1410, 60), WeeklyRule(1, 1230, 1260), WeeklyRule(4, 660, 330))
        val other = listOf(WeeklyRule(1, 1170, 1230), WeeklyRule(1, 1170, 1230),
            WeeklyRule(1, 1230), WeeklyRule(1, 1395, 1425), WeeklyRule(2, 30, 90), WeeklyRule(1, 1170, 1230), WeeklyRule(4, 1140))
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
                TrackerTheme { Surface(Modifier.requiredSize(320.dp, 540.dp).testTag("overlap_review")) {
                    key(scene.intValue) {
                        val schedules = mutableListOf(OccupiedSchedule("c", if (language == "ru") "Основы C" else "C basics", monday, null, listOf(other[scene.intValue])))
                        if (scene.intValue == 1) schedules += OccupiedSchedule("s", if (language == "ru") "Шведский" else "Swedish", monday, null, listOf(WeeklyRule(1, 1245, 1275)))
                        if (scene.intValue == 6) {
                            schedules.clear()
                            schedules += OccupiedSchedule("conversation", "Разговорная практика", monday, null, listOf(WeeklyRule(4, 1140)))
                            schedules += OccupiedSchedule("swedish", "Шведский · A2", monday, null, listOf(WeeklyRule(4, 1230)))
                        }
                        ScheduleContent(initial = listOf(drafts[scene.intValue]), occupied = schedules, today = monday)
                    }
                } }
            }
        }
        val directory = File(context.getExternalFilesDir(null), "schedule-overlaps").apply { mkdirs() }
        for (index in drafts.indices) {
            compose.runOnIdle { scene.intValue = index }
            compose.onNodeWithTag("schedule_save").assertIsDisplayed().assertIsEnabled()
            val day = drafts[index].dayOfWeek
            if (index == 5) compose.onNodeWithTag("schedule_conflicts_$day").assertDoesNotExist()
            else compose.onNodeWithTag("schedule_conflicts_$day").performScrollTo().assertIsDisplayed()
            for (tag in listOf("schedule_start_$day") + if (drafts[index].endMinute == null) emptyList() else listOf("schedule_end_$day")) {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                org.junit.Assert.assertEquals("One label with its time: $tag", 1, layouts.size)
                val layout = layouts.single()
                org.junit.Assert.assertTrue("Label and time use at most two lines: $tag", layout.lineCount in 1..2)
                val timeStart = layout.layoutInput.text.text.indexOf(' ') + 1
                org.junit.Assert.assertEquals("Time stays together: $tag",
                    layout.getLineForOffset(timeStart), layout.getLineForOffset(layout.layoutInput.text.length - 1))
                for (offset in layout.layoutInput.text.indices) {
                    val bounds = layout.getBoundingBox(offset)
                    org.junit.Assert.assertTrue("Text fits horizontally: $tag $bounds in ${layout.size}",
                        bounds.left >= -1f && bounds.right <= layout.size.width + 1f)
                    org.junit.Assert.assertTrue("Text fits vertically: $tag", bounds.top >= -1f && bounds.bottom <= layout.size.height + 1f)
                }
            }
            if (drafts[index].endMinute != null) {
                val startBounds = compose.onNodeWithTag("schedule_start_$day").fetchSemanticsNode().boundsInRoot
                val endBounds = compose.onNodeWithTag("schedule_end_$day").fetchSemanticsNode().boundsInRoot
                org.junit.Assert.assertEquals("Time controls share a row on 320 dp", startBounds.center.y, endBounds.center.y, 1f)
                compose.onNodeWithTag("schedule_remove_end_$day").assertIsDisplayed()
            }
            compose.waitForIdle()
            val frames = java.util.concurrent.CountDownLatch(1)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                android.view.Choreographer.getInstance().postFrameCallback {
                    android.view.Choreographer.getInstance().postFrameCallback { frames.countDown() }
                }
            }
            check(frames.await(3, java.util.concurrent.TimeUnit.SECONDS))
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val bitmap = compose.onNodeWithTag("overlap_review").captureToImage().asAndroidBitmap()
            File(directory, "${language}_$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
