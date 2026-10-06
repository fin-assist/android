package ru.finassist.pf.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Entry point of the design system. Material 3 is kept underneath only as plumbing (ripples, text selection,
 * bottom-sheet and dialog scaffolding); every visible colour and text style comes from the Pf tokens.
 *
 * @param darkTheme resolved from the profile theme setting (`system` → [isSystemInDarkTheme]).
 */
@Composable
fun PfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkPfColors else LightPfColors
    val typography = PfTypography.Default
    val dimens = PfDimens()
    CompositionLocalProvider(
        LocalPfColors provides colors,
        LocalPfTypography provides typography,
        LocalPfDimens provides dimens,
    ) {
        MaterialTheme(colorScheme = materialScheme(colors)) {
            CompositionLocalProvider(
                LocalContentColor provides colors.text,
                LocalTextStyle provides typography.body,
                content = content,
            )
        }
    }
}

/** Accessors: `PfTheme.colors.accent`, `PfTheme.type.title1`, `PfTheme.dimens.space4`. */
object PfTheme {
    val colors: PfColorScheme
        @Composable @ReadOnlyComposable get() = LocalPfColors.current
    val type: PfTypography
        @Composable @ReadOnlyComposable get() = LocalPfTypography.current
    val dimens: PfDimens
        @Composable @ReadOnlyComposable get() = LocalPfDimens.current
}

private fun materialScheme(c: PfColorScheme): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = c.accent,
        secondary = c.accent,
        onSecondary = c.onAccent,
        background = c.bg,
        onBackground = c.text,
        surface = c.surface,
        onSurface = c.text,
        surfaceVariant = c.surface,
        onSurfaceVariant = c.textMuted,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.surface,
        surfaceContainerLow = c.surface,
        surfaceContainerLowest = c.surface,
        outline = c.borderStrong,
        outlineVariant = c.border,
        error = c.warningText,
        onError = c.onWarning,
        errorContainer = c.warningSoft,
        onErrorContainer = c.warningText,
        scrim = c.scrim,
    )
}
