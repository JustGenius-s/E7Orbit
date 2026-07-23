package com.e7orbit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val OrbitWhiteColorScheme = lightColorScheme(
    primary = WhitePrimary,
    onPrimary = WhiteOnPrimary,
    primaryContainer = WhitePrimaryContainer,
    onPrimaryContainer = WhiteOnPrimaryContainer,
    inversePrimary = WhiteInversePrimary,
    secondary = WhiteSecondary,
    onSecondary = WhiteOnSecondary,
    secondaryContainer = WhiteSecondaryContainer,
    onSecondaryContainer = WhiteOnSecondaryContainer,
    tertiary = WhiteTertiary,
    onTertiary = WhiteOnTertiary,
    tertiaryContainer = WhiteTertiaryContainer,
    onTertiaryContainer = WhiteOnTertiaryContainer,
    background = WhiteBackground,
    onBackground = WhiteOnBackground,
    surface = WhiteSurface,
    onSurface = WhiteOnSurface,
    surfaceVariant = WhiteSurfaceVariant,
    onSurfaceVariant = WhiteOnSurfaceVariant,
    surfaceTint = WhitePrimary,
    inverseSurface = WhiteInverseSurface,
    inverseOnSurface = WhiteInverseOnSurface,
    error = WhiteError,
    onError = WhiteOnError,
    errorContainer = WhiteErrorContainer,
    onErrorContainer = WhiteOnErrorContainer,
    outline = WhiteOutline,
    outlineVariant = WhiteOutlineVariant,
    scrim = WhiteScrim,
    surfaceDim = WhiteSurfaceDim,
    surfaceBright = WhiteSurfaceBright,
    surfaceContainerLowest = WhiteSurfaceContainerLowest,
    surfaceContainerLow = WhiteSurfaceContainerLow,
    surfaceContainer = WhiteSurfaceContainer,
    surfaceContainerHigh = WhiteSurfaceContainerHigh,
    surfaceContainerHighest = WhiteSurfaceContainerHighest,
)

@Composable
fun E7OrbitTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = OrbitWhiteColorScheme,
        typography = OrbitTypography,
        shapes = OrbitShapes,
        content = content,
    )
}
