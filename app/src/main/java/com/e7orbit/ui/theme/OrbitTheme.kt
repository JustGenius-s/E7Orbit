package com.e7orbit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OrbitBackground = Color(0xFFF4F0E8)
val OrbitSurface = Color(0xFFFFFDF9)
val OrbitSurfaceRaised = Color(0xFFEEE7DD)
val OrbitPrimary = Color(0xFF6750C8)
val OrbitSecondary = Color(0xFFC56A3D)
val OrbitSuccess = Color(0xFF2F7D5A)
val OrbitWarning = Color(0xFFA76414)
val OrbitError = Color(0xFFB43D52)
val OrbitOnSurfaceMuted = Color(0xFF706A62)

private val OrbitColors = lightColorScheme(
    primary = OrbitPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DFFF),
    onPrimaryContainer = Color(0xFF26145F),
    secondary = OrbitSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBC9),
    onSecondaryContainer = Color(0xFF4A1A06),
    background = OrbitBackground,
    onBackground = Color(0xFF28231E),
    surface = OrbitSurface,
    onSurface = Color(0xFF28231E),
    surfaceVariant = OrbitSurfaceRaised,
    onSurfaceVariant = OrbitOnSurfaceMuted,
    error = OrbitError,
    onError = Color.White,
    outline = Color(0xFF81786E),
    outlineVariant = Color(0xFFDDD4C8),
)

@Composable
fun E7OrbitTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = OrbitColors,
        content = content,
    )
}
