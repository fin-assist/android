package ru.finassist.pf.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Type ramp (tokens.json «Заголовки и суммы», «Текст»). System font (Roboto on Android). */
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
    val captionStrong: TextStyle,
    val overline: TextStyle,
    val hint: TextStyle,
    val micro: TextStyle,
    /** 16 sp input text (fields and composer). */
    val input: TextStyle,
)

private fun style(size: Int, line: Int, weight: Int, letterSpacingEm: Float = 0f, tabular: Boolean = false) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = letterSpacingEm.em,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    fontFeatureSettings = if (tabular) "tnum" else null,
)

val PfDefaultTypography = PfTypography(
    amountInput = style(40, 48, 700, tabular = true),
    amountHero = style(30, 36, 700, -0.02f, tabular = true),
    title1 = style(26, 32, 700, -0.02f),
    amountLg = style(26, 32, 700, -0.02f, tabular = true),
    amountMd = style(20, 24, 700, tabular = true),
    title2 = style(18, 24, 700),
    wordmark = style(15, 20, 700, -0.01f),
    bodyStrong = style(15, 20, 600),
    lead = style(15, 20, 400),
    body = style(14, 20, 400),
    caption = style(13, 20, 400),
    captionStrong = style(13, 20, 600),
    overline = style(12, 16, 600, 0.04f),
    hint = style(12, 16, 400),
    micro = style(11, 16, 600),
    input = style(16, 24, 400),
)

val LocalPfTypography = staticCompositionLocalOf { PfDefaultTypography }
