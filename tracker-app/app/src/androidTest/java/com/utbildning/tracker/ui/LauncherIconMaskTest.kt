package com.utbildning.tracker.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconMaskTest {
    @Test
    fun adaptiveIconFitsRoundedSquareMask() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue("Launcher icon must be adaptive", icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull("Android 13 themed icon layer is missing", icon.monochrome)

        val size = 512
        val mask = roundedSquareMask(size)
        val background = render(size, mask, icon, includeForeground = false)
        val rendered = render(size, mask, icon, includeForeground = true)

        assertTrue("Rounded-square corner must remain transparent", rendered.getPixel(0, 0) ushr 24 == 0)
        assertTrue("Rounded-square center must be opaque", rendered.getPixel(size / 2, size / 2) ushr 24 == 0xff)

        var changed = 0
        var minX = size
        var minY = size
        var maxX = -1
        var maxY = -1
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (rendered.getPixel(x, y) != background.getPixel(x, y)) {
                    changed++
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        assertTrue("Foreground artwork was not rendered", changed > size * size / 50)
        val safeInset = size * 15 / 100
        assertTrue(
            "Foreground exceeds the adaptive-icon safe area: $minX,$minY..$maxX,$maxY",
            minX >= safeInset && minY >= safeInset && maxX < size - safeInset && maxY < size - safeInset,
        )

        val output = File(checkNotNull(context.getExternalFilesDir(null)), "launcher-rounded-square.png")
        output.outputStream().use { stream ->
            check(rendered.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    private fun render(
        size: Int,
        mask: Path,
        icon: AdaptiveIconDrawable,
        includeForeground: Boolean,
    ): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.clipPath(mask)
        icon.background.bounds = Rect(0, 0, size, size)
        icon.background.draw(canvas)
        if (includeForeground) {
            icon.foreground.bounds = Rect(0, 0, size, size)
            icon.foreground.draw(canvas)
        }
        canvas.restore()
    }

    private fun roundedSquareMask(size: Int): Path {
        // Android 13 framework's rounded-square adaptive-icon mask (100 x 100 viewport).
        val scale = size / 100f
        fun value(coordinate: Float) = coordinate * scale
        return Path().apply {
            moveTo(value(50f), value(0f))
            lineTo(value(92f), value(0f))
            cubicTo(value(96.42f), value(0f), value(100f), value(4.58f), value(100f), value(8f))
            lineTo(value(100f), value(92f))
            cubicTo(value(100f), value(96.42f), value(96.42f), value(100f), value(92f), value(100f))
            lineTo(value(8f), value(100f))
            cubicTo(value(4.58f), value(100f), value(0f), value(96.42f), value(0f), value(92f))
            lineTo(value(0f), value(8f))
            cubicTo(value(0f), value(4.42f), value(4.42f), value(0f), value(8f), value(0f))
            close()
        }
    }
}
