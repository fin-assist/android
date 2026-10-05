package ru.finassist.pf.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing, radius and size tokens. Everything is a multiple of 4 dp; values outside the scale are not used. */
@Immutable
data class PfDimens(
    val space1: Dp = 4.dp,
    val space2: Dp = 8.dp,
    val space3: Dp = 12.dp,
    val space4: Dp = 16.dp,
    val space5: Dp = 20.dp,
    val space6: Dp = 24.dp,
    val space8: Dp = 32.dp,
    val space12: Dp = 48.dp,

    val radiusSm: Dp = 10.dp,
    val radiusMd: Dp = 12.dp,
    val radiusLg: Dp = 14.dp,
    val radiusXl: Dp = 18.dp,
    val radius2xl: Dp = 24.dp,
    val radiusFull: Dp = 999.dp,

    val iconSm: Dp = 12.dp,
    val iconMd: Dp = 20.dp,
    val iconLg: Dp = 22.dp,
    val tile: Dp = 40.dp,
    val touch: Dp = 48.dp,
    val control: Dp = 48.dp,
    val field: Dp = 52.dp,
    val row: Dp = 56.dp,
    val tabbar: Dp = 80.dp,
    val mark: Dp = 56.dp,
    val status: Dp = 64.dp,
    /** Space under the Android status bar / gesture bar in the mockups; real insets come from WindowInsets. */
    val insetTop: Dp = 24.dp,
    val insetBottom: Dp = 24.dp,
    /** Screen side margins. */
    val screenMargin: Dp = 20.dp,
)

val LocalPfDimens = staticCompositionLocalOf { PfDimens() }
