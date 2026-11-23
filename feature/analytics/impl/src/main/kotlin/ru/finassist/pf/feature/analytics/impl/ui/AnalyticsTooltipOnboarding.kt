package ru.finassist.pf.feature.analytics.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import ru.finassist.pf.core.api.RequestOwner
import ru.finassist.pf.core.blocks.LocalTooltipOnboarding
import ru.finassist.pf.core.blocks.TooltipOnboarding
import ru.finassist.pf.core.designsystem.components.LocalSuppressPopups

@Composable
internal fun AnalyticsTooltipOnboarding(
    state: AnalyticsUiState,
    claim: (suspend (RequestOwner) -> Boolean)?,
    content: @Composable () -> Unit,
) {
    val owner = state.owner
    val controller = remember(owner) { TooltipOnboarding() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val direction = LocalLayoutDirection.current
    val latestClaim by rememberUpdatedState(claim)
    val suppressed = LocalSuppressPopups.current || state.sheet != null
    if (owner != null && claim != null && !suppressed) {
        LaunchedEffect(controller, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                controller.show(direction) { latestClaim?.invoke(owner) == true }
            }
        }
    }
    CompositionLocalProvider(LocalTooltipOnboarding provides controller, content = content)
}
