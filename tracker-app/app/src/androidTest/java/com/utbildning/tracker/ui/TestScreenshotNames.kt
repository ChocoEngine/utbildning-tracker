package com.utbildning.tracker.ui

import java.util.UUID
import androidx.compose.ui.unit.dp

/** pm clear does not remove MediaStore files from previous instrumentation runs. */
internal fun uniqueTestScreenshotName(name: String): String =
    "${name.removeSuffix(".png")}_${UUID.randomUUID()}.png"

internal fun reviewHeight() =
    (androidx.test.platform.app.InstrumentationRegistry.getArguments()
        .getString("reviewHeightDp")?.toInt() ?: 620).dp
