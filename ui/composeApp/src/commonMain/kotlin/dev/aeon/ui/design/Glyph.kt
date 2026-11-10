package dev.aeon.ui.design

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning

import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Icons. The status and tab symbols used to be text glyphs, and the web build's bundled font does not have
 * them. These are vector icons from Material (core set), so they render the same on every platform.
 * Unknown glyphs fall back to text.
 */
private fun iconFor(glyph: String): ImageVector? = when (glyph) {
    "✓" -> Icons.Filled.Check
    "✕" -> Icons.Filled.Close
    "✋" -> Icons.Filled.Warning
    "⚠" -> Icons.Filled.Warning
    "⚙" -> Icons.Filled.Settings
    "☰" -> Icons.Filled.Menu
    "⌛" -> Icons.Filled.Info
    "◌" -> Icons.Filled.Refresh
    "⚑" -> Icons.Filled.Done
    "■" -> Icons.Filled.Close
    "▶" -> Icons.Filled.PlayArrow
    "○" -> Icons.Filled.ArrowDropDown
    "↑" -> Icons.Filled.KeyboardArrowUp
    "▭" -> Icons.Filled.Home
    "🔒" -> Icons.Filled.Lock
    "•" -> Icons.Filled.Info
    else -> null
}

@Composable
fun Glyph(glyph: String, color: Color, style: TextStyle = TextStyle.Default, modifier: Modifier = Modifier) {
    val v = iconFor(glyph)
    if (v != null) {
        Icon(v, contentDescription = null, tint = color, modifier = modifier.size(16.dp))
    } else {
        Text(glyph, color = color, style = style, modifier = modifier)
    }
}
