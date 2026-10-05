package ru.finassist.pf.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colour tokens of «Понятные финансы» (tokens.json v7). Names match the design system one-to-one. */
@Immutable
data class PfColors(
    val bg: Color,
    val surface: Color,
    val nav: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val knob: Color,
    val positive: Color,
    val positiveSoft: Color,
    val infoSoft: Color,
    val infoText: Color,
    val warningSoft: Color,
    val warningText: Color,
    val onWarning: Color,
    val scrim: Color,
    val isDark: Boolean,
) {
    val focusRing: Color get() = accent
}

val PfLightColors = PfColors(
    bg = Color(0xFFF5F6F8),
    surface = Color(0xFFFFFFFF),
    nav = Color(0xFFFFFFFF),
    border = Color(0xFFE6E8EE),
    borderStrong = Color(0xFF767D93),
    text = Color(0xFF14213D),
    textMuted = Color(0xFF5F6782),
    textFaint = Color(0xFF687087),
    accent = Color(0xFF3B5BDB),
    accentSoft = Color(0xFFEBEFFC),
    onAccent = Color(0xFFFFFFFF),
    knob = Color(0xFFFFFFFF),
    positive = Color(0xFF237A4B),
    positiveSoft = Color(0xFFE3F2EA),
    infoSoft = Color(0xFFEEF2FF),
    infoText = Color(0xFF2C3E8C),
    warningSoft = Color(0xFFFFF4E5),
    warningText = Color(0xFF8A4B0F),
    onWarning = Color(0xFFFFFFFF),
    scrim = Color(0x7314213D),
    isDark = false,
)

val PfDarkColors = PfColors(
    bg = Color(0xFF0E111B),
    surface = Color(0xFF181C2A),
    nav = Color(0xFF141826),
    border = Color(0xFF262B3D),
    borderStrong = Color(0xFF666D86),
    text = Color(0xFFE8EAF2),
    textMuted = Color(0xFFA0A6BA),
    textFaint = Color(0xFF80869C),
    accent = Color(0xFF7C93F0),
    accentSoft = Color(0xFF222A4A),
    onAccent = Color(0xFF0E111B),
    knob = Color(0xFF0E111B),
    positive = Color(0xFF5CC990),
    positiveSoft = Color(0xFF15321F),
    infoSoft = Color(0xFF1C2340),
    infoText = Color(0xFFBCC8F7),
    warningSoft = Color(0xFF33260F),
    warningText = Color(0xFFF2C27B),
    onWarning = Color(0xFF0E111B),
    scrim = Color(0x99000000),
    isDark = true,
)

val LocalPfColors = staticCompositionLocalOf { PfLightColors }
