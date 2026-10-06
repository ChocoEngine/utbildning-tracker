package com.utbildning.tracker.ui

import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.Choreographer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.data.local.CourseEntity
import com.utbildning.tracker.ui.courses.CourseListContent
import com.utbildning.tracker.ui.theme.TrackerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Narrow-screen review of the existing layout; no database or app-service mutations. */
@RunWith(AndroidJUnit4::class)
class CourseListGeometryReviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun russianLongNamesKeepPercentAlignedAt320dp() = review("ru")
    @Test fun englishLongNamesKeepPercentAlignedAt320dp() = review("en")

    private fun review(language: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val localized = context.createConfigurationContext(config)
        val percentages = listOf(9, 10, 99, 100)
        val spaced = if (language == "ru") "Программирование на C: указатели массивы память и функции "
            else "Programming in C: pointers arrays memory and functions "
        val alternative = if (language == "ru") "Практика на C: структуры данных и управление памятью "
            else "C practice: data structures and memory management exercises "
        val names = listOf(spaced.take(50), "W".repeat(50), spaced.repeat(3), alternative.take(50))
        val longNames = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config, LocalResources provides localized.resources) {
                TrackerTheme {
                    Surface(Modifier.requiredWidth(320.dp).height(reviewHeight()).testTag("geometry_canvas")) {
                        val courses = percentages.mapIndexed { index, percent ->
                            CourseEntity("review-$percent", if (longNames.value) names[index] else "C $percent", index, 1, 1)
                        }
                        CourseListContent(courses, emptyList(), courses.mapIndexed { index, course ->
                            course.id to (percentages[index] to 100)
                        }.toMap(), false, {}, {})
                    }
                }
            }
        }
        val rightEdges = percentages.associateWith { percent ->
            compose.onNodeWithText("$percent%", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.right
        }
        compose.runOnIdle { longNames.value = true }
        percentages.forEachIndexed { index, percent ->
            compose.onNodeWithTag("course_row_review-$percent").performScrollTo()
            val titleNode = compose.onNodeWithText(names[index], useUnmergedTree = true)
            titleNode.assertIsDisplayed()
            val title = titleNode.fetchSemanticsNode()
            val number = compose.onNodeWithText("$percent%", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
            assertTrue("Title overlaps percentage for $language/$percent", title.boundsInRoot.bottom <= number.boundsInRoot.top)
            assertEquals("Percentage moves horizontally for $language/$percent", rightEdges.getValue(percent), number.boundsInRoot.right, 1f)
            val layouts = mutableListOf<TextLayoutResult>()
            val action = title.config[SemanticsActions.GetTextLayoutResult].action
            assertNotNull(action)
            assertTrue(action!!.invoke(layouts))
            assertFalse("Title clips for $language/$percent", layouts.single().hasVisualOverflow)
            assertTrue("Long title does not wrap for $language/$percent", layouts.single().lineCount > 1)
            println("LIST_GEOMETRY language=$language percent=$percent length=${names[index].length} lines=${layouts.single().lineCount} title=${title.boundsInRoot} percentBounds=${number.boundsInRoot}")
        }
        capture(context, "${language}_long_names_bottom.png")
        compose.onNodeWithTag("course_row_review-9").performScrollTo()
        capture(context, "${language}_long_names_top.png")
    }

    private fun capture(context: Context, name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val frames = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { frames.countDown() }
            }
        }
        check(frames.await(3, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = compose.onNodeWithTag("geometry_canvas").captureToImage().asAndroidBitmap()
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, uniqueTestScreenshotName(name))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrackerListGeometry")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }
}
