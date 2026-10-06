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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.ui.today.TodayContent
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Review artifacts, not golden-image assertions. Uses local state and no app database/services. */
@RunWith(AndroidJUnit4::class)
class TodayVisualReviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureRussianAt320dp() = captureScenes("ru")
    @Test fun captureEnglishAt320dp() = captureScenes("en")

    private fun captureScenes(language: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val locale = Locale.forLanguageTag(language)
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localized = context.createConfigurationContext(configuration)
        val date = LocalDate.of(2026, 9, 26)
        val title = if (language == "ru") "Практика алгоритмов и структур данных на C" else "Algorithms and data structures practice in C"
        val sessions = listOf(SessionEntity("today", "course", date.toEpochDay(), 11 * 60, title, 0, 1, 1))
        val previous = listOf(
            sessions.first().copy(id = "yesterday_pending", date = date.minusDays(1).toEpochDay(), startMinute = 8 * 60, result = SessionResult.PENDING),
            sessions.first().copy(id = "yesterday_done", date = date.minusDays(1).toEpochDay(), startMinute = 23 * 60 + 30, result = SessionResult.DONE),
            sessions.first().copy(id = "yesterday_skipped", date = date.minusDays(1).toEpochDay(), startMinute = 10 * 60, result = SessionResult.SKIPPED),
        )
        val future = listOf(sessions.first().copy(id = "future", date = date.plusDays(1).toEpochDay()))
        val scene = mutableIntStateOf(0)
        val names = listOf("empty", "upcoming", "current", "yesterday")
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration, LocalResources provides localized.resources) {
                TrackerTheme {
                    Surface(Modifier.requiredWidth(320.dp).height(reviewHeight()).testTag("review_canvas")) {
                        key(scene.intValue) {
                            TodayContent(when (scene.intValue) {
                                0 -> emptyList()
                                1 -> future
                                2 -> sessions + future
                                else -> sessions + previous + future
                            }, Instant.parse("2026-09-26T12:00:00Z"), ZoneId.of("Europe/Moscow"), locale, {}, {})
                        }
                    }
                }
            }
        }
        names.forEachIndexed { index, name ->
            compose.runOnIdle { scene.intValue = index }
            capture(context, "today_${language}_${name}.png")

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
        // Capture the displayed window: layer capture can omit newly drawn header glyphs.
        android.os.SystemClock.sleep(250)
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
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
