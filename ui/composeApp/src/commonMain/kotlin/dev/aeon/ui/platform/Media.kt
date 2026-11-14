package dev.aeon.ui.platform

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes a JPEG, PNG or WebP into a picture, small enough for a thumbnail. Null when the bytes cannot be read. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/** Opens an https address in the system browser. Anything else is ignored. */
expect fun openUrl(url: String)
