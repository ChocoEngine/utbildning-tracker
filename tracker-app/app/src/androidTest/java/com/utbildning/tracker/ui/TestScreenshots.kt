package com.utbildning.tracker.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.Choreographer
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** pm clear does not remove MediaStore files from previous instrumentation runs. */
internal fun uniqueTestScreenshotName(name: String): String =
    "${name.removeSuffix(".png")}_${UUID.randomUUID()}.png"

internal fun reviewHeight() =
    (InstrumentationRegistry.getArguments().getString("reviewHeightDp")?.toInt() ?: 620).dp

/** Compose idleness can precede the platform draw of a newly keyed scene. */
internal fun ComposeTestRule.awaitReviewDraw() {
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    val frames = CountDownLatch(1)
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
        Choreographer.getInstance().postFrameCallback {
            Choreographer.getInstance().postFrameCallback { frames.countDown() }
        }
    }
    check(frames.await(3, TimeUnit.SECONDS)) { "Review scene did not receive two display frames" }
    instrumentation.waitForIdleSync()
}

internal fun saveTestScreenshot(
    context: Context,
    bitmap: Bitmap,
    name: String,
    directory: String = "Pictures/TrackerChecks",
) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, uniqueTestScreenshotName(name))
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, directory)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
    try {
        checkNotNull(resolver.openOutputStream(uri)).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null) == 1)
    } catch (failure: Exception) {
        resolver.delete(uri, null, null)
        throw failure
    }
}
