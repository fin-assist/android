package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Virtualized history: only visible columns compose; stable month keys retain position when pages change. */
@Composable
fun PfPagedBarChart(
    chartId: String,
    points: List<BarPoint>,
    selectedMonth: String?,
    height: Dp,
    onPointClick: (String) -> Unit,
    onEdge: (older: Boolean) -> Unit,
) {
    val state = rememberLazyListState()
    val negative = points.any { (it.value ?: 0f) < 0f }
    LaunchedEffect(chartId, selectedMonth) {
        val index = points.indexOfFirst { it.key == selectedMonth }.takeIf { it >= 0 }
            ?: points.indexOfLast { it.value != null }.coerceAtLeast(0)
        state.scrollToItem((index - 3).coerceAtLeast(0))
    }
    LaunchedEffect(state, points.firstOrNull()?.key, points.lastOrNull()?.key) {
        snapshotFlow {
            val visible = state.layoutInfo.visibleItemsInfo
            when {
                !state.isScrollInProgress || visible.isEmpty() -> null
                visible.first().index < 3 -> true
                visible.last().index >= points.lastIndex - 2 -> false
                else -> null
            }
        }.distinctUntilChanged().collect { it?.let(onEdge) }
    }
    LazyRow(state = state, modifier = Modifier.testTag(chartId), horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space2)) {
        itemsIndexed(points, key = { _, point -> point.key }) { _, point ->
            PfBarChart(chartId, listOf(point), Modifier.width(PfTheme.dimens.touch),
                highlight = if (point.key == selectedMonth) 0 else -1, height = height,
                onPointClick = onPointClick, baselineHasNegative = negative, pointTagPrefix = chartId)
        }
    }
}
