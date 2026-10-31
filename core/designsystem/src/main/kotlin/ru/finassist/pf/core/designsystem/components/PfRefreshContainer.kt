package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Refreshes a screen's read requests, including when its body is empty or cannot scroll. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PfRefreshContainer(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    Box(modifier.pullToRefresh(refreshing, state, enabled = enabled, onRefresh = onRefresh)) {
        // A non-consuming scroll target supplies gestures for empty/error states without imposing
        // unbounded layout constraints on the nested LazyColumn or the screen's weighted content.
        Box(Modifier.fillMaxSize().scrollable(rememberScrollableState { 0f }, Orientation.Vertical)) {
            content()
        }
        PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = refreshing,
            containerColor = PfTheme.colors.surface,
            color = PfTheme.colors.text,
            modifier = Modifier.align(Alignment.TopCenter).testTag("ds.refresh.indicator"),
        )
    }
}
