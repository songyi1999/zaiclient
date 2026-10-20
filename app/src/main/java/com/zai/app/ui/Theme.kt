package com.zai.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Emerald = Color(0xFF34D399)
private val Amber = Color(0xFFFBBF24)

// WorkBuddy 风格：近黑背景 + 深灰卡片 + 翠绿强调
private val DarkScheme = darkColorScheme(
    primary = Emerald,
    onPrimary = Color(0xFF00351E),
    secondary = Amber,
    background = Color(0xFF121214),
    onBackground = Color(0xFFE6E6E9),
    surface = Color(0xFF1B1B1E),
    onSurface = Color(0xFFE6E6E9),
    surfaceVariant = Color(0xFF26262A),
    onSurfaceVariant = Color(0xFF9A9AA3),
    outline = Color(0xFF4A4A52),
    error = Color(0xFFEF4444),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color.White,
    secondary = Color(0xFFB45309),
    background = Color(0xFFF4F5F6),
    onBackground = Color(0xFF191A1C),
    surface = Color.White,
    onSurface = Color(0xFF191A1C),
    surfaceVariant = Color(0xFFE9EAEC),
    onSurfaceVariant = Color(0xFF5F6368),
    outline = Color(0xFFC4C7CC),
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
