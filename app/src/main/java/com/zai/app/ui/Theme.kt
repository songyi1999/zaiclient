package com.zai.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.zai.app.auth.Session

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

// 浅色：白卡片 + 灰底 + 深翠绿强调
private val LightScheme = lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color.White,
    secondary = Color(0xFFB45309),
    background = Color(0xFFF2F3F5),
    onBackground = Color(0xFF1A1B1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1B1E),
    surfaceVariant = Color(0xFFE8EAEC),
    onSurfaceVariant = Color(0xFF5C6168),
    outline = Color(0xFFC6CAD0),
    error = Color(0xFFDC2626),
)

/**
 * 外观跟随「我的 → 外观」设置，默认浅色，即时切换即时生效。
 */
@Composable
fun ZaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (Session.themeMode == "dark") DarkScheme else LightScheme,
        content = content,
    )
}

/** 内容浮层底板色：深色纯黑 / 浅色浅灰，衬托顶部圆角面板 */
val PanelBackdropDark = Color.Black
val PanelBackdropLight = Color(0xFFE4E6E9)
