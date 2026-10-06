package com.mkread.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Every role is set explicitly: unset roles fall back to Material's purple baseline, which
// clashed with the green brand on chips, sheets and progress tracks.
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF275D4B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8EBD5),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF8C4F24),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E8DE),
    onSecondaryContainer = Color(0xFF10201A),
    tertiary = Color(0xFF8C4F24),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC6),
    onTertiaryContainer = Color(0xFF311300),
    background = Color(0xFFF8F6F0),
    onBackground = Color(0xFF1A1C1B),
    surface = Color(0xFFF8F6F0),
    onSurface = Color(0xFF1A1C1B),
    surfaceVariant = Color(0xFFDDE5DF),
    onSurfaceVariant = Color(0xFF414944),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F1EB),
    surfaceContainer = Color(0xFFECEBE5),
    surfaceContainerHigh = Color(0xFFE6E5DF),
    surfaceContainerHighest = Color(0xFFE1E0DA),
    outline = Color(0xFF717973),
    outlineVariant = Color(0xFFC0C9C2),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF91D5BB),
    onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF0B5039),
    onPrimaryContainer = Color(0xFFB8EBD5),
    secondary = Color(0xFFFFB782),
    onSecondary = Color(0xFF4F2500),
    secondaryContainer = Color(0xFF2E4A3F),
    onSecondaryContainer = Color(0xFFD0E8DC),
    tertiary = Color(0xFFFFB782),
    onTertiary = Color(0xFF4F2500),
    tertiaryContainer = Color(0xFF6E390E),
    onTertiaryContainer = Color(0xFFFFDCC6),
    background = Color(0xFF111412),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF111412),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF3A433E),
    onSurfaceVariant = Color(0xFFC0C9C2),
    surfaceContainerLowest = Color(0xFF0C0F0D),
    surfaceContainerLow = Color(0xFF191C1A),
    surfaceContainer = Color(0xFF1D201E),
    surfaceContainerHigh = Color(0xFF272B28),
    surfaceContainerHighest = Color(0xFF323633),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF414944),
)

@Composable
fun MkreadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
