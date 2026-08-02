package com.mkread.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF275D4B),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF8C4F24),
    background = Color(0xFFF8F9F6),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1B),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF91D5BB),
    onPrimary = Color(0xFF003829),
    secondary = Color(0xFFFFB782),
    background = Color(0xFF111412),
    surface = Color(0xFF191C1A),
    onSurface = Color(0xFFE1E3DF),
)

@Composable
fun MkreadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
