package dev.aeon.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aeon.ui.api.Qr

/**
 * A QR code on a white square with a quiet zone, black on white in both themes (scanners expect that).
 * Draws nothing if the text is too long for the encoder.
 */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier, description: String = "QR 코드") {
    val code = remember(text) { runCatching { Qr.encode(text) }.getOrNull() } ?: return
    val quiet = 3
    Canvas(modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).semantics { contentDescription = description }) {
        drawRect(Color.White)
        val n = code.size + quiet * 2
        val cell = size.width / n
        for (y in 0 until code.size) for (x in 0 until code.size) {
            if (code.modules[y][x]) drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
