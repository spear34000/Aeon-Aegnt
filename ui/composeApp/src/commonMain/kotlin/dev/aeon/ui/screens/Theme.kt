package dev.aeon.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light: ColorScheme = lightColorScheme(
    primary = Color(0xFF3A4FC4),
    onPrimary = Color.White,
    secondary = Color(0xFF5B6478),
    background = Color(0xFFF7F8FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9ECF4),
    onSurface = Color(0xFF1B1E27),
    error = Color(0xFFB3261E),
)

private val Dark: ColorScheme = darkColorScheme(
    primary = Color(0xFFAFBBFF),
    onPrimary = Color(0xFF111A6B),
    secondary = Color(0xFFB4BCCF),
    background = Color(0xFF12141A),
    surface = Color(0xFF1B1E27),
    surfaceVariant = Color(0xFF2A2F3B),
    onSurface = Color(0xFFE4E6EE),
    error = Color(0xFFF2B8B5),
)

@Composable
fun AeonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Status colours are never the only signal: every status also has a text label. */
object StatusStyle {
    fun label(status: String): String = when (status) {
        "PENDING" -> "Queued"
        "RUNNING" -> "Running"
        "RETRY_WAIT" -> "Retrying soon"
        "WAITING_FOR_APPROVAL" -> "Needs your approval"
        "WAITING_FOR_QUOTA" -> "Waiting for model quota"
        "WAITING_FOR_DEVICE" -> "Waiting for a device"
        "COMPLETED" -> "Completed"
        "FAILED" -> "Failed"
        "CANCELLED" -> "Cancelled"
        else -> status
    }

    fun color(status: String): Color = when (status) {
        "COMPLETED" -> Color(0xFF2E7D32)
        "FAILED" -> Color(0xFFC62828)
        "WAITING_FOR_APPROVAL" -> Color(0xFFB26A00)
        "RUNNING" -> Color(0xFF3A4FC4)
        "CANCELLED" -> Color(0xFF6B7280)
        else -> Color(0xFF5B6478)
    }

    fun verificationLabel(status: String?): String = when (status) {
        "verified" -> "Verified against real files"
        "failed" -> "Verification FAILED"
        "unverified" -> "Not verified (no objective check)"
        "user_confirmed" -> "Confirmed by you"
        null -> "Not checked yet"
        else -> status
    }
}
