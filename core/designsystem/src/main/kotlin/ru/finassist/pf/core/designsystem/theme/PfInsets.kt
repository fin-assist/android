package ru.finassist.pf.core.designsystem.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Fixed system bars for renders without a real window (design-check snapshots, previews). */
data class PfSystemBars(val statusBars: WindowInsets, val navigationBars: WindowInsets)

/** Null in the app: the real window insets apply. */
val LocalPfSystemBars = staticCompositionLocalOf<PfSystemBars?> { null }

/**
 * System bar insets for screens and components. Always go through these instead of `WindowInsets.statusBars` /
 * `navigationBars`, so a render outside a real window can supply the bars the mockups reserve (24 dp each).
 */
object PfInsets {
    val statusBars: WindowInsets
        @Composable get() = LocalPfSystemBars.current?.statusBars ?: WindowInsets.statusBars

    val navigationBars: WindowInsets
        @Composable get() = LocalPfSystemBars.current?.navigationBars ?: WindowInsets.navigationBars
}
