package com.zai.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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

/**
 * 全局固定深色主题（WorkBuddy 风格按深色设计）。
 * 不跟随系统浅色——避免部分机型浅色下输入文字对比度不足的问题。
 */
@Composable
fun ZaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content,
    )
}
