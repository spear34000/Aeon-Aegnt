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

private val Light = lightColorScheme(
    primary = Color(0xFF6750D9), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEFECFD), onPrimaryContainer = Color(0xFF4733B8),
    secondary = Color(0xFF5C5C68), onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F7F8), onBackground = Color(0xFF17171C),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF17171C),
    surfaceVariant = Color(0xFFF1F1F3), onSurfaceVariant = Color(0xFF5C5C68),
    surfaceContainer = Color(0xFFF1F1F3), surfaceContainerHigh = Color(0xFFE7E7EB),
    outline = Color(0xFFCACAD2), outlineVariant = Color(0xFFE3E3E8),
    error = Color(0xFFC42B2B), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE8E7), onErrorContainer = Color(0xFFC42B2B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9F8FFF), onPrimary = Color(0xFF140F33),
    primaryContainer = Color(0xFF221D3F), onPrimaryContainer = Color(0xFFCBC2FF),
    secondary = Color(0xFFA1A1AD), onSecondary = Color(0xFF0E0E10),
    background = Color(0xFF0E0E10), onBackground = Color(0xFFECECF1),
    surface = Color(0xFF161618), onSurface = Color(0xFFECECF1),
    surfaceVariant = Color(0xFF1C1C1F), onSurfaceVariant = Color(0xFFA1A1AD),
    surfaceContainer = Color(0xFF1C1C1F), surfaceContainerHigh = Color(0xFF26262B),
    outline = Color(0xFF3A3A42), outlineVariant = Color(0xFF2A2A30),
    error = Color(0xFFFF7B73), onError = Color(0xFF2B0B09),
    errorContainer = Color(0xFF341716), onErrorContainer = Color(0xFFFF7B73),
)

private val LightStatus = AeonStatus(
    ok = Color(0xFF1A7F4B), okSoft = Color(0xFFE5F4EB), warn = Color(0xFF95580A), warnSoft = Color(0xFFFCF0DB),
    danger = Color(0xFFC42B2B), dangerSoft = Color(0xFFFCE8E7), fg3 = Color(0xFF8A8A96),
    accentSoft = Color(0xFFEFECFD), onAccentSoft = Color(0xFF4733B8),
)

private val DarkStatus = AeonStatus(
    ok = Color(0xFF4ACB8A), okSoft = Color(0xFF10281C), warn = Color(0xFFF2B65A), warnSoft = Color(0xFF2B2111),
    danger = Color(0xFFFF7B73), dangerSoft = Color(0xFF341716), fg3 = Color(0xFF6F6F7A),
    accentSoft = Color(0xFF221D3F), onAccentSoft = Color(0xFFCBC2FF),
)

@Composable
fun AeonTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme: ColorScheme = if (dark) Dark else Light
    CompositionLocalProvider(LocalAeonStatus provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, typography = aeonTypography(), content = content)
    }
}

/** Shape scale from the design: 10 for controls, 12 for cards, 16 for bubbles. */
object AeonShape {
    const val CONTROL = 10
    const val CARD = 12
    const val BUBBLE = 16
}
