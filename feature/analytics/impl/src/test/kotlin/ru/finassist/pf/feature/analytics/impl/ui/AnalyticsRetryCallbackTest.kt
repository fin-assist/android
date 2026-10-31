package ru.finassist.pf.feature.analytics.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.designsystem.theme.PfTheme
import kotlin.test.assertEquals

/** JVM-only callback regression: no emulator, network or screenshot capture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnalyticsRetryCallbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `paused recalculation retry refreshes shown data instead of resetting a failed period`() {
        val data = ApiJson.decodeFromString(Analytics.serializer(), javaClass.getResource("/analytics/september.json")!!.readText())
        val handlers = Handlers()
        val state = AnalyticsUiState(
            loading = false,
            data = data.copy(blocks = emptyList(), state = data.state!!.copy(recalculating = true)),
            blocks = AnalyticsBlocks(assistant = false, transfersFilter = false),
            recalculationPaused = true,
            periodChangeErrorPresentation = ErrorPresentation(),
            periodChangeRetryReady = false,
        )
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                PfTheme {
                    AnalyticsContent(state, AnalyticsActions({}, {}, {}, { _, _ -> }, null), handlers)
                }
            }
        }
        compose.onNodeWithTag(AnalyticsTags.RETRY).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, handlers.refreshes)
            assertEquals(0, handlers.loads)
            assertEquals(0, handlers.periodRetries)
        }
    }

    @Test fun `refresh indicator tag follows the actual read lifecycle`() {
        val state = mutableStateOf(AnalyticsUiState(loading = false, refreshing = true, blocks = AnalyticsBlocks()))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                PfTheme {
                    AnalyticsContent(state.value, AnalyticsActions({}, {}, {}, { _, _ -> }, null), Handlers())
                }
            }
        }
        compose.onNodeWithTag("ds.refresh.indicator").assertExists()
        compose.runOnIdle { state.value = state.value.copy(refreshing = false) }
        compose.onNodeWithTag("ds.refresh.indicator").assertDoesNotExist()
    }

    private class Handlers : AnalyticsHandlers {
        var refreshes = 0
        var loads = 0
        var periodRetries = 0
        override fun refresh() { refreshes++ }
        override fun load(quiet: Boolean) { loads++ }
        override fun retryPeriodChange() { periodRetries++ }
        override fun consumeSnackbar() = Unit
        override fun setPeriodType(type: PeriodTypeCode) = Unit
        override fun goTo(key: PeriodKey) = Unit
        override fun openSheet(sheet: AnalyticsSheet?) = Unit
        override fun nextHint(stop: Boolean) = Unit
        override fun setTransferMode(mode: TransferMode) = Unit
        override fun currentTransferMode() = TransferMode.WITH
        override fun toggleExpanded(id: String) = Unit
        override fun onAction(block: Block, action: BlockAction, navigate: (BlockAction) -> Unit) = Unit
        override fun onBlockShown(block: Block) = Unit
    }
}
