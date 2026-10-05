package ru.finassist.pf.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** Theme setting: «Как в системе», «Светлая», «Тёмная». */
enum class ThemeMode { System, Light, Dark }

object PfTheme {
    val colors: PfColors
        @Composable @ReadOnlyComposable get() = LocalPfColors.current
    val type: PfTypography
        @Composable @ReadOnlyComposable get() = LocalPfTypography.current
}

/**
 * Root theme. Material 3 is used only as a substrate (ripple, sheets, text selection); every visible colour
 * comes from [PfColors]. The M3 scheme is mapped so that any stray Material default still looks right.
 */
@Composable
fun PfTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = if (dark) PfDarkColors else PfLightColors
    CompositionLocalProvider(LocalPfColors provides colors, LocalPfTypography provides PfDefaultTypography) {
        MaterialTheme(colorScheme = colors.toMaterial(), content = content)
    }
}

private fun PfColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = accent,
        secondary = accent, onSecondary = onAccent,
        background = bg, onBackground = text,
        surface = surface, onSurface = text, surfaceVariant = surface, onSurfaceVariant = textMuted,
        surfaceContainer = surface, surfaceContainerHigh = surface, surfaceContainerHighest = surface, surfaceContainerLow = surface, surfaceContainerLowest = surface,
        outline = borderStrong, outlineVariant = border,
        error = warningText, onError = onWarning, errorContainer = warningSoft, onErrorContainer = warningText,
        scrim = scrim,
    )
}
