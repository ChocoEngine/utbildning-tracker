package com.utbildning.tracker.ui

import java.util.UUID

/** pm clear does not remove MediaStore files from previous instrumentation runs. */
internal fun uniqueTestScreenshotName(name: String): String =
    "${name.removeSuffix(".png")}_${UUID.randomUUID()}.png"
