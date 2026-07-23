package com.e7orbit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val OrbitLightColorScheme = lightColorScheme(
    primary = OrbitLightPrimary,
    onPrimary = OrbitLightOnPrimary,
    primaryContainer = OrbitLightPrimaryContainer,
    onPrimaryContainer = OrbitLightOnPrimaryContainer,
    secondary = OrbitLightSecondary,
    onSecondary = OrbitLightOnSecondary,
    secondaryContainer = OrbitLightSecondaryContainer,
    onSecondaryContainer = OrbitLightOnSecondaryContainer,
    tertiary = OrbitLightTertiary,
    onTertiary = OrbitLightOnTertiary,
    tertiaryContainer = OrbitLightTertiaryContainer,
    onTertiaryContainer = OrbitLightOnTertiaryContainer,
    background = OrbitLightBackground,
    onBackground = OrbitLightOnBackground,
    surface = OrbitLightSurface,
    onSurface = OrbitLightOnSurface,
    surfaceVariant = OrbitLightSurfaceVariant,
    onSurfaceVariant = OrbitLightOnSurfaceVariant,
    outline = OrbitLightOutline,
    outlineVariant = OrbitLightOutlineVariant,
)

private val OrbitDarkColorScheme = darkColorScheme(
    primary = OrbitDarkPrimary,
    onPrimary = OrbitDarkOnPrimary,
    primaryContainer = OrbitDarkPrimaryContainer,
    onPrimaryContainer = OrbitDarkOnPrimaryContainer,
    secondary = OrbitDarkSecondary,
    onSecondary = OrbitDarkOnSecondary,
    secondaryContainer = OrbitDarkSecondaryContainer,
    onSecondaryContainer = OrbitDarkOnSecondaryContainer,
    tertiary = OrbitDarkTertiary,
    onTertiary = OrbitDarkOnTertiary,
    tertiaryContainer = OrbitDarkTertiaryContainer,
    onTertiaryContainer = OrbitDarkOnTertiaryContainer,
    background = OrbitDarkBackground,
    onBackground = OrbitDarkOnBackground,
    surface = OrbitDarkSurface,
    onSurface = OrbitDarkOnSurface,
    surfaceVariant = OrbitDarkSurfaceVariant,
    onSurfaceVariant = OrbitDarkOnSurfaceVariant,
    outline = OrbitDarkOutline,
    outlineVariant = OrbitDarkOutlineVariant,
)

@Composable
fun E7OrbitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) OrbitDarkColorScheme else OrbitLightColorScheme,
        typography = OrbitTypography,
        shapes = OrbitShapes,
        content = content,
    )
}
