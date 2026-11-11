package dev.aeon.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** Status colours from the design. Never used alone: every status also has an icon and a word. */
data class AeonStatus(
    val ok: Color, val okSoft: Color, val warn: Color, val warnSoft: Color,
    val danger: Color, val dangerSoft: Color, val fg3: Color, val accentSoft: Color, val onAccentSoft: Color,
)

val LocalAeonStatus = compositionLocalOf<AeonStatus> { error("AeonTheme missing") }

/**
 * Neutral surfaces with one accent (mockup "Aeon 모바일", light and dark).
 * background = page, surface = cards and sheets, surfaceVariant = soft fills (icon buttons, chips, bubbles).
 * Amber means "waiting for you", red means "cannot be undone", green means "checked".
 */
private val Light = lightColorScheme(
    primary = Color(0xFF2F5BEA), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6ECFF), onPrimaryContainer = Color(0xFF1C3FB8),
    secondary = Color(0xFF5A5A66), onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F4F6), onBackground = Color(0xFF0B0B0F),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0B0B0F),
    surfaceVariant = Color(0xFFE9E9ED), onSurfaceVariant = Color(0xFF5A5A66),
    surfaceContainer = Color(0xFFE9E9ED), surfaceContainerHigh = Color(0xFFE1E1E7),
    outline = Color(0xFFC9C9D2), outlineVariant = Color(0xFFE1E1E7),
    error = Color(0xFFC5221F), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE8E7), onErrorContainer = Color(0xFF9E1B18),
    scrim = Color(0xFF0B0B0F),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF86A2FF), onPrimary = Color(0xFF081033),
    primaryContainer = Color(0xFF1A2450), onPrimaryContainer = Color(0xFFC9D5FF),
    secondary = Color(0xFFA6A6B2), onSecondary = Color(0xFF0B0B0D),
    background = Color(0xFF0B0B0D), onBackground = Color(0xFFF4F4F6),
    surface = Color(0xFF17171A), onSurface = Color(0xFFF4F4F6),
    surfaceVariant = Color(0xFF242428), onSurfaceVariant = Color(0xFFA6A6B2),
    surfaceContainer = Color(0xFF242428), surfaceContainerHigh = Color(0xFF2C2C31),
    outline = Color(0xFF3A3A42), outlineVariant = Color(0xFF2C2C31),
    error = Color(0xFFFF6B66), onError = Color(0xFF2B0B09),
    errorContainer = Color(0xFF3A1412), onErrorContainer = Color(0xFFFFB8B4),
    scrim = Color(0xFF000000),
)

private val LightStatus = AeonStatus(
    ok = Color(0xFF0B7A53), okSoft = Color(0xFFDDF5EA), warn = Color(0xFF8A5200), warnSoft = Color(0xFFFFF0D2),
    danger = Color(0xFFC5221F), dangerSoft = Color(0xFFFDE8E7), fg3 = Color(0xFF6B6B77),
    accentSoft = Color(0xFFE6ECFF), onAccentSoft = Color(0xFF1C3FB8),
)

private val DarkStatus = AeonStatus(
    ok = Color(0xFF4FD6A0), okSoft = Color(0xFF0E2C21), warn = Color(0xFFFFB84D), warnSoft = Color(0xFF33250B),
    danger = Color(0xFFFF6B66), dangerSoft = Color(0xFF3A1412), fg3 = Color(0xFF8E8E9A),
    accentSoft = Color(0xFF1A2450), onAccentSoft = Color(0xFFC9D5FF),
)

/** [dark] = null follows the system setting. */
@Composable
fun AeonTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val isDark = dark ?: isSystemInDarkTheme()
    val scheme: ColorScheme = if (isDark) Dark else Light
    CompositionLocalProvider(LocalAeonStatus provides if (isDark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, typography = aeonTypography(), content = content)
    }
}

/** Shape scale from the mockup: 16 for fields and small rows, 20 for cards and bubbles, 28 for sheets; buttons are pills. */
object AeonShape {
    const val CONTROL = 16
    const val CARD = 20
    const val BUBBLE = 20
    const val SHEET = 28
}
