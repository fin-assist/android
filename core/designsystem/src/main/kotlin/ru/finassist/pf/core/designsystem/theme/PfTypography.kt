package ru.finassist.pf.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Type scale of the design system. One family — the system font (Roboto on Android); line heights are multiples
 * of 4 and applied exactly (no font padding), so rows measure as in the mockups. Amount styles use tabular
 * figures so digits line up in columns.
 */
@Immutable
data class PfTypography(
    val amountInput: TextStyle,
    val amountHero: TextStyle,
    val title1: TextStyle,
    val amountLg: TextStyle,
    val amountMd: TextStyle,
    val title2: TextStyle,
    val wordmark: TextStyle,
    val bodyStrong: TextStyle,
    val lead: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val overline: TextStyle,
    val hint: TextStyle,
    val micro: TextStyle,
) {
    companion object {
        private const val TABULAR = "tnum"

        private fun style(size: Int, lineHeight: Int, weight: FontWeight, letterSpacingEm: Float = 0f, tabular: Boolean = false) = TextStyle(
            fontFamily = FontFamily.Default,
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
            fontWeight = weight,
            letterSpacing = (letterSpacingEm * size).sp,
            fontFeatureSettings = if (tabular) TABULAR else null,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None),
        )

        val Default = PfTypography(
            amountInput = style(40, 48, FontWeight.Bold, tabular = true),
            amountHero = style(30, 36, FontWeight.Bold, -0.02f, tabular = true),
            title1 = style(26, 32, FontWeight.Bold, -0.02f),
            amountLg = style(26, 32, FontWeight.Bold, -0.02f, tabular = true),
            amountMd = style(20, 24, FontWeight.Bold, tabular = true),
            title2 = style(18, 24, FontWeight.Bold),
            wordmark = style(15, 20, FontWeight.Bold, -0.01f),
            bodyStrong = style(15, 20, FontWeight.SemiBold, tabular = true),
            lead = style(15, 20, FontWeight.Normal),
            body = style(14, 20, FontWeight.Normal),
            caption = style(13, 20, FontWeight.Normal),
            overline = style(12, 16, FontWeight.SemiBold, 0.04f),
            hint = style(12, 16, FontWeight.Normal),
            micro = style(11, 16, FontWeight.SemiBold),
        )
    }
}

val LocalPfTypography = staticCompositionLocalOf { PfTypography.Default }
