package com.utbildning.tracker

/** Requires a prepared emulator clock or other explicit external conditions. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SystemScenario

/** Mutates the emulator's demo data; never part of regression verification. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class EmulatorFixture

/** Produces images for human review without asserting product behavior. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ScreenshotCapture
