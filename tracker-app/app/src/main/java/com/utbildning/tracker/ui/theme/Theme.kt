package com.utbildning.tracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val TrackerColorScheme = lightColorScheme(
    primary = ButtonInk,
    onPrimary = Paper,
    primaryContainer = Panel,
    onPrimaryContainer = Ink,
    secondary = MutedInk,
    onSecondary = Paper,
    secondaryContainer = Panel,
    onSecondaryContainer = Ink,
    tertiary = Ink,
    onTertiary = Paper,
    tertiaryContainer = Panel,
    onTertiaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Panel,
    onSurfaceVariant = MutedInk,
    surfaceTint = Paper,
    surfaceBright = Paper,
    surfaceDim = Panel,
    surfaceContainerLowest = Paper,
    surfaceContainerLow = Paper,
    surfaceContainer = Panel,
    surfaceContainerHigh = Panel,
    surfaceContainerHighest = Panel,
    outline = MutedInk,
    outlineVariant = Divider,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = Paper,
)

@Composable
fun TrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TrackerColorScheme,
        typography = TrackerTypography,
        content = content,
    )
}
