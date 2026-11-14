package dev.aeon.ui.design

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.aeon.ui.generated.resources.Res
import dev.aeon.ui.generated.resources.pretendard_regular
import dev.aeon.ui.generated.resources.pretendard_semibold
import org.jetbrains.compose.resources.Font as ResFont

/** Pretendard, two weights only (design rule: 400 regular, 600 semibold). Korean and Latin share one face. */
@Composable
fun pretendardFamily(): FontFamily = FontFamily(
    ResFont(Res.font.pretendard_regular, FontWeight.Normal),
    ResFont(Res.font.pretendard_semibold, FontWeight.SemiBold),
)

/** Type scale from the design (display 24/32, title 18/26, subtitle 15/22, body 15/24, label 13/18, caption 12/16). */
@Composable
fun aeonTypography(): Typography {
    val f = pretendardFamily()
    fun s(size: Int, line: Int, w: FontWeight) = TextStyle(fontFamily = f, fontSize = size.sp, lineHeight = line.sp, fontWeight = w)
    return Typography(
        headlineMedium = s(28, 34, FontWeight.SemiBold),
        displaySmall = s(24, 32, FontWeight.SemiBold),
        titleLarge = s(18, 26, FontWeight.SemiBold),
        titleMedium = s(15, 22, FontWeight.SemiBold),
        bodyLarge = s(15, 24, FontWeight.Normal),
        bodyMedium = s(14, 22, FontWeight.Normal),
        labelLarge = s(13, 18, FontWeight.SemiBold),
        labelMedium = s(13, 18, FontWeight.Normal),
        bodySmall = s(12, 16, FontWeight.Normal),
        labelSmall = s(12, 16, FontWeight.Normal),
    )
}
