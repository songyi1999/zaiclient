package com.zai.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF60A5FA)
private val Amber = Color(0xFFFBBF24)

private val DarkScheme = darkColorScheme(
    primary = Blue,
    onPrimary = Color(0xFF0B1E3A),
    secondary = Amber,
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111A2C),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Color(0xFFEF4444),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    secondary = Color(0xFFB45309),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun ZaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
