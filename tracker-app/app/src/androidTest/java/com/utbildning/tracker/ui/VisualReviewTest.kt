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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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

/** Review artifacts, not golden-image assertions. Uses local state and no app database/services. */
@RunWith(AndroidJUnit4::class)
class VisualReviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureRussianAt320dp() = captureScenes("ru")
    @Test fun captureEnglishAt320dp() = captureScenes("en")

    private fun captureScenes(language: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val locale = Locale.forLanguageTag(language)
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localized = context.createConfigurationContext(configuration)
        val title = if (language == "ru") "Лекции и практические занятия по программированию на C" else "Lectures and practical exercises in programming with C"
        val category = if (language == "ru") "Программирование" else "Programming"
        val topics = (1..40).map { TopicEntity("topic_$it", "course_0", it - 1, if (language == "ru") "Тема $it: указатели, массивы и управление памятью" else "Topic $it: pointers, arrays and memory management") }
        val completedIds = topics.take(3).map { it.id }.toSet()
        val editableTopics = topics.map { EditableTopic(it.id, it.title, it.position, it.id in completedIds) }
        val text = topics.drop(3).joinToString("\n") { it.title }
        val courses = (0..9).map { CourseEntity("course_$it", "$title ${it + 1}", it, 1, 1, categoryId = "category") }
        val date = LocalDate.of(2026, 9, 26)
        val sessions = courses.mapIndexed { index, course -> SessionEntity("session_$index", course.id, date.toEpochDay(), 8 * 60 + index * 30,
            course.name, index, 1, 1, result = when (index) { 0, 3, 6 -> SessionResult.DONE; 1, 4, 7, 9 -> SessionResult.SKIPPED; else -> SessionResult.PENDING }) } +
            listOf(1, 4, 7).mapIndexed { index, color -> SessionEntity("calendar_extra_$index", "course_${index + 7}", date.toEpochDay(), 13 * 60 + index * 30,
                "${title} ${index + 8}", color, 1, 1, result = SessionResult.SKIPPED) }
        val calendarSessions = sessions + SessionEntity("yesterday_pending", courses.first().id, date.minusDays(1).toEpochDay(), 23 * 60 + 30,
            courses.first().name, courses.first().colorId, 1, 1, endMinute = 60)
        val scene = mutableIntStateOf(0)
        val names = listOf("course_list", "course_new", "course_edit", "topic_editor", "calendar", "today", "session", "schedule", "guide")
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                TrackerTheme {
                    Surface(Modifier.requiredWidth(320.dp).height(620.dp).testTag("review_canvas")) {
                        key(scene.intValue) {
                            when (scene.intValue) {
                                0 -> CourseListContent(courses, listOf(CategoryEntity("category", category)), courses.associate { it.id to (3 to 40) }, false, {}, {})
                                1 -> CourseEditorContent(CourseDraft(name = title, category = category), emptyList(), (0..9).toList(), false, null, {}, {}, {}, {}, {}, {})
                                2 -> CourseEditorContent(CourseDraft(id = "course_0", name = title, category = category, topics = editableTopics, text = text), emptyList(), listOf(0), false, null, {}, {}, {}, {}, {}, {})
                                3 -> CourseEditorContent(CourseDraft(id = "course_0", name = title, category = category, topics = editableTopics, text = text, editingText = text), emptyList(), listOf(0), false, null, {}, {}, {}, {}, {}, {})
                                4 -> CalendarContent(YearMonth.from(date), date.minusDays(1), calendarSessions, locale, {}, {}, today = date)
                                5 -> TodayContent(sessions, Instant.parse("2026-09-26T10:00:00Z"), ZoneId.of("Europe/Moscow"), locale, {}, {})
                                6 -> SessionContent(title, topics, completedIds, false, false, {}, {}, {})
                                7 -> ScheduleContent(initial = listOf(WeeklyRule(2, 19 * 60, 20 * 60 + 30), WeeklyRule(6, 11 * 60)), initialEnd = date.plusMonths(3).toEpochDay())
                                8 -> GuideScreen({})
                            }
                        }
                    }
                }
            }
        }
        names.forEachIndexed { index, name ->
            compose.runOnIdle { scene.intValue = index }
            capture(context, "${language}_${name}_320dp.png")
            if (name == "topic_editor") {
                compose.onNodeWithTag("topics_input").performTouchInput { swipeUp() }
                capture(context, "${language}_topic_editor_scrolled_320dp.png")
            }
            if (name == "schedule") {
                compose.onNodeWithTag("schedule_day_7").performScrollTo()
                capture(context, "${language}_schedule_end_320dp.png")
            }
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
        val bitmap = compose.onNodeWithTag("review_canvas").captureToImage().asAndroidBitmap()
        save(context, bitmap, name)
    }

    private fun save(context: Context, bitmap: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, uniqueTestScreenshotName(name))
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
