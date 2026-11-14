package dev.aeon.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Outline icons drawn for the mockup: 24dp grid, 1.8 stroke, round caps. Tint them with `Icon(tint = ...)`. */
object AeonIcons {
    private fun stroke(name: String, d: String, width: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    val Menu by lazy { stroke("menu", "M4 7h16M4 12h16M4 17h10") }
    val Chat by lazy { stroke("chat", "M4 5h16v11H9l-5 4z") }
    val Feed by lazy { stroke("feed", "M5 19.2v.01M5 11a8 8 0 0 1 8 8M5 4.5A14.5 14.5 0 0 1 19.5 19") }
    val Explore by lazy { stroke("explore", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM15.5 8.5l-2 5-5 2 2-5z") }
    val Tasks by lazy { stroke("tasks", "M4 7l2 2 3-3M12 7.5h8M4 16l2 2 3-3M12 16.5h8") }
    val Agent by lazy { stroke("agent", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4.5 20c1-4 4-6 7.5-6s6.5 2 7.5 6") }
    val Send by lazy { stroke("send", "M12 19V5M6 11l6-6 6 6", 2.2f) }
    val Plus by lazy { stroke("plus", "M12 5v14M5 12h14", 2f) }
    val Check by lazy { stroke("check", "M5 12.5l4.5 4.5L19 7.5", 2.2f) }
    val Chevron by lazy { stroke("chevron", "M9 6l6 6-6 6") }
    val Back by lazy { stroke("back", "M15 6l-6 6 6 6") }
    val Search by lazy { stroke("search", "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM16.5 16.5L20 20") }
    val Close by lazy { stroke("close", "M6 6l12 12M18 6L6 18") }
    val File by lazy { stroke("file", "M7 3h7l4 4v14H7zM14 3v4h4") }
    val Globe by lazy { stroke("globe", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z") }
    val Terminal by lazy { stroke("terminal", "M4 5h16v14H4zM8 10l3 2.5L8 15M13 15h3") }
    val Key by lazy { stroke("key", "M7.5 15.5a4 4 0 1 1 0-8 4 4 0 0 1 0 8zM11.5 11.5H21M17.5 11.5v3M21 11.5v2") }
    val Phone by lazy { stroke("phone", "M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM11 18h2") }
    val Cpu by lazy { stroke("cpu", "M7 7h10v10H7zM10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4") }
    val Chart by lazy { stroke("chart", "M5 20V10M12 20V4M19 20v-7") }
    val Info by lazy { stroke("info", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v6M12 7.5v.01") }
    val Shield by lazy { stroke("shield", "M12 3l7 3v5.5c0 4.3-2.9 7.6-7 9.5-4.1-1.9-7-5.2-7-9.5V6z") }
    val Plug by lazy { stroke("plug", "M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4") }
    val Heart by lazy { stroke("heart", "M12 20s-7-4.4-7-10a4 4 0 017-2.6A4 4 0 0119 10c0 5.6-7 10-7 10z") }
    val ArrowUp by lazy { stroke("up", "M12 19V5M6 11l6-6 6 6", 2f) }
    val ArrowDown by lazy { stroke("down", "M12 5v14M6 13l6 6 6-6", 2f) }
    val Sliders by lazy { stroke("sliders", "M4 7h10M18 7h2M4 17h2M10 17h10M14 4v6M6 14v6") }
    val Repeat by lazy { stroke("repeat", "M17 2l4 4-4 4M3 11V9a3 3 0 013-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 01-3 3H3") }
    val Memory by lazy { stroke("memory", "M7 4h10v16l-5-4-5 4zM10 9h4") }
    val Book by lazy { stroke("book", "M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3zM5 17a3 3 0 0 1 3-3h11") }
    val Moon by lazy { stroke("moon", "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z") }
    val Link by lazy { stroke("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1") }
    val Pause by lazy { stroke("pause", "M9 6v12M15 6v12", 2.2f) }
    val Clock by lazy { stroke("clock", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2") }
    val Dash by lazy { stroke("dash", "M6 12h12", 2.2f) }
    val Box by lazy { stroke("box", "M12 3l8 4.5v9L12 21l-8-4.5v-9zM4 7.5l8 4.5 8-4.5M12 12v9") }
    val Code by lazy { stroke("code", "M8 8l-4 4 4 4M16 8l4 4-4 4M13.5 5l-3 14") }
    val Refresh by lazy { stroke("refresh", "M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5") }
    val Warn by lazy { stroke("warn", "M12 4l9 16H3zM12 10v4M12 17v.01") }
    val More by lazy { stroke("more", "M6 12h.01M12 12h.01M18 12h.01", 2.4f) }

    /** The Aeon mark: an orbit around a core. */
    val Mark: ImageVector by lazy {
        val line = SolidColor(Color.Black)
        ImageVector.Builder("aeon", 24.dp, 24.dp, 48f, 48f)
            .addPath(PathParser().parsePathString("M5 24a19 19 0 1 0 38 0a19 19 0 1 0 -38 0z").toNodes(), fill = null, stroke = line, strokeLineWidth = 3.2f)
            .addGroup(rotate = -30f, pivotX = 24f, pivotY = 24f)
            .addPath(PathParser().parsePathString("M5 24a19 7.5 0 1 0 38 0a19 7.5 0 1 0 -38 0z").toNodes(), fill = null, stroke = line, strokeLineWidth = 3.2f)
            .clearGroup()
            .addPath(PathParser().parsePathString("M19.5 24a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0z").toNodes(), fill = line)
            .build()
    }
}
