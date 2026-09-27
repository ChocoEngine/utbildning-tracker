package com.utbildning.tracker.ui

import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.Choreographer
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.EditableTopic
import com.utbildning.tracker.domain.WeeklyRule
import com.utbildning.tracker.ui.calendar.CalendarContent
import com.utbildning.tracker.ui.courses.CourseDraft
import com.utbildning.tracker.ui.courses.CourseEditorContent
import com.utbildning.tracker.ui.courses.CourseListContent
import com.utbildning.tracker.ui.guide.GuideScreen
import com.utbildning.tracker.ui.schedule.ScheduleContent
import com.utbildning.tracker.ui.session.SessionContent
import com.utbildning.tracker.ui.today.TodayContent
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseDetailsReviewTest {
    @get:Rule val compose = createComposeRule()
    @Test fun resumePreservesProgressWithoutSchedule() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        try {
            val repo = com.utbildning.tracker.data.TrackerRepository(db)
            val course = repo.saveCourseForm(name = "C", colorId = 0, categoryName = "", topicText = "One\nTwo")
            repo.saveInitialSchedule(course.id, listOf(WeeklyRule(1, 1200)), null)
            repo.toggleTopicCompletion(course.id, repo.getCourseDetails(course.id)!!.topics.first().id)
            repo.pauseCourse(course.id)
            val before = repo.getCourseDetails(course.id)!!
            repo.disableSchedule(course.id)
            val after = repo.getCourseDetails(course.id)!!
            org.junit.Assert.assertFalse(after.course.isPaused)
            org.junit.Assert.assertFalse(after.hasSchedule)
            org.junit.Assert.assertEquals(before.course.colorId, after.course.colorId)
            org.junit.Assert.assertEquals(before.topics, after.topics)
            repo.pauseCourse(course.id)
            org.junit.Assert.assertTrue(repo.getCourseDetails(course.id)!!.course.isPaused)
            org.junit.Assert.assertEquals(before.topics, repo.getCourseDetails(course.id)!!.topics)
        } finally { db.close() }
    }
    @Test fun russian() = review("ru")
    @Test fun english() = review("en")
    private fun review(language: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val localized = context.createConfigurationContext(configuration)
        val scene = mutableIntStateOf(0)
        val topics = (1..40).map { EditableTopic("topic_$it", "${it}: C — pointers, arrays and memory", it - 1, it < 3) }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
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
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // Compose idleness alone can precede the platform draw of a newly keyed scene.
        val frames = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { frames.countDown() }
            }
        }
        check(frames.await(3, TimeUnit.SECONDS)) { "Review scene did not receive two display frames" }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = compose.onNodeWithTag(if (name.contains("expanded")) "course_palette_dialog" else "review_canvas").captureToImage().asAndroidBitmap()
        save(context, bitmap, name)
    }

    private fun save(context: Context, bitmap: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrackerChecks")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        checkNotNull(resolver.openOutputStream(uri)).use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }
}
