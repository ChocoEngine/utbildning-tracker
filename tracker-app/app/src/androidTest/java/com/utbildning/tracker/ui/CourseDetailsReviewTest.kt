package com.utbildning.tracker.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.EditableTopic
import com.utbildning.tracker.ui.courses.CourseDraft
import com.utbildning.tracker.ui.courses.CourseEditorContent
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.time.LocalDate
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseDetailsReviewTest {
    @get:Rule val compose = createComposeRule()
    @Test fun russianEditorStatesKeepActionsAndTopicChecksAligned() = review("ru")
    @Test fun englishEditorStatesKeepActionsAndTopicChecksAligned() = review("en")
    private fun review(language: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val localized = context.createConfigurationContext(configuration)
        val scene = mutableIntStateOf(0)
        val topics = (1..40).map { EditableTopic("topic_$it", "${it}: C — pointers, arrays and memory", it - 1, it < 3) }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration, LocalResources provides localized.resources) {
                TrackerTheme { Surface(Modifier.requiredWidth(320.dp).height(540.dp).testTag("review_canvas")) {
                    key(scene.intValue) { CourseEditorContent(CourseDraft(id = "course", name = "Лекции и практические занятия по программированию C", category = "Программирование", color = 0,
                        hasSchedule = scene.intValue == 0, paused = scene.intValue == 1, completed = scene.intValue == 2,
                        rules = if (scene.intValue == 0) listOf(ScheduleRuleEntity("course", 2, 1140), ScheduleRuleEntity("course", 6, 660)) else emptyList(),
                        endsOn = if (scene.intValue == 0) LocalDate.of(2026, 12, 31).toEpochDay() else null,
                        topics = topics, text = topics.drop(2).joinToString("\n") { it.title }), emptyList(), (0..9).toList(), false, null, {}, {}, {}, {}, {}, {}) }
                } }
            }
        }
        for (state in 0..3) {
            compose.runOnIdle { scene.intValue = state }
            compose.onNodeWithTag("course_delete").assertIsDisplayed()
            if (state == 1 || state == 2) compose.onNodeWithTag("course_schedule_row").assertDoesNotExist()
            else compose.onNodeWithTag("course_schedule_row").assertIsDisplayed()
            if (state == 1) compose.onNodeWithTag("course_resume").assertIsDisplayed()
            val titleBounds = compose.onNodeWithTag("topic_title_topic_1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val checkBounds = compose.onNodeWithTag("topic_done_topic_1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val secondCheck = compose.onNodeWithTag("topic_done_topic_2", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertEquals(titleBounds.center.y, checkBounds.center.y, 1f)
            org.junit.Assert.assertEquals(checkBounds.center.x, secondCheck.center.x, 1f)
            val before = compose.onNodeWithTag("course_actions").fetchSemanticsNode().boundsInRoot
            capture(context, "${language}_details_${state}_collapsed.png")
            if (state != 2) {
                compose.onNodeWithTag("color_1").assertDoesNotExist()
                compose.onNodeWithTag("course_color_toggle").performClick()
                compose.onNodeWithTag("color_1").assertIsDisplayed()
                // The palette is a separate dialog; the underlying layout does not reflow.
                capture(context, "${language}_details_${state}_expanded.png")
                compose.onNodeWithTag("color_1").performClick()
                compose.onNodeWithTag("color_1").assertDoesNotExist()
            }
            compose.onNodeWithTag("course_topics_scroll").performTouchInput { swipeUp() }
            org.junit.Assert.assertEquals(before, compose.onNodeWithTag("course_actions").fetchSemanticsNode().boundsInRoot)
        }
    }
    private fun capture(context: Context, name: String) {
        compose.awaitReviewDraw()
        val bitmap = compose.onNodeWithTag(if (name.contains("expanded")) "course_palette_dialog" else "review_canvas").captureToImage().asAndroidBitmap()
        saveTestScreenshot(context, bitmap, name)
    }

}
