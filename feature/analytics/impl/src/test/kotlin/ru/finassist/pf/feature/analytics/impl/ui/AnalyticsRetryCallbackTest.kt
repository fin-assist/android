package ru.finassist.pf.feature.analytics.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.designsystem.theme.PfTheme
import java.time.OffsetDateTime
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

    @Test
    @Config(qualifiers = "w320dp-h400dp-mdpi")
    fun `fullscreen offline retry refreshes the shown context`() = assertFullscreenRetry(
        AnalyticsUiState(loading = false, offline = true, blocks = AnalyticsBlocks(),
            periodChangeErrorPresentation = ErrorPresentation(), periodChangeRetryReady = false),
    )

    @Test
    @Config(qualifiers = "w320dp-h400dp-mdpi")
    fun `fullscreen preparation timeout retry refreshes the shown context`() = assertFullscreenRetry(
        AnalyticsUiState(loading = false, preparationTimedOut = true, blocks = AnalyticsBlocks(),
            periodChangeErrorPresentation = ErrorPresentation(), periodChangeRetryReady = false),
    )

    private fun assertFullscreenRetry(state: AnalyticsUiState) {
        val handlers = Handlers()
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                PfTheme {
                    AnalyticsContent(state, AnalyticsActions({}, {}, {}, { _, _ -> }, null), handlers)
                }
            }
        }
        compose.onNodeWithTag(AnalyticsTags.RETRY).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, handlers.refreshes)
            assertEquals(0, handlers.loads)
            assertEquals(0, handlers.periodRetries)
        }
    }

    @Test fun `stored quota error is visible only alongside its assistant card`() {
        val data = ApiJson.decodeFromString(Analytics.serializer(), javaClass.getResource("/analytics/september.json")!!.readText())
        val state = mutableStateOf(AnalyticsUiState(loading = false, offline = true, blocks = AnalyticsBlocks(),
            limitErrorPresentation = ErrorPresentation(connectionFailure = true)))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                PfTheme {
                    AnalyticsContent(state.value, AnalyticsActions({}, {}, {}, { _, _ -> }, null), Handlers())
                }
            }
        }
        val quotaError = compose.onNodeWithText("Не получилось обновить лимит вопросов", substring = true)
        quotaError.assertDoesNotExist()
        compose.onNodeWithTag(AnalyticsTags.ASK).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(offline = false, data = Analytics(hasData = false)) }
        quotaError.assertDoesNotExist()
        compose.onNodeWithTag(AnalyticsTags.ASK).assertDoesNotExist()
        // A quota failure may arrive before the analytics response: keep it for the later card.
        compose.runOnIdle { state.value = state.value.copy(data = data.copy(blocks = emptyList())) }
        quotaError.assertExists()
        compose.onNodeWithTag(AnalyticsTags.ASK).assertExists()
        compose.runOnIdle { state.value = state.value.copy(loading = true) }
        quotaError.assertDoesNotExist()
        compose.onNodeWithTag(AnalyticsTags.ASK).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(loading = false, blocks = AnalyticsBlocks(assistant = false)) }
        quotaError.assertDoesNotExist()
        compose.onNodeWithTag(AnalyticsTags.ASK).assertDoesNotExist()
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

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `assistant help updates to the server limit and stays open`() {
        val data = ApiJson.decodeFromString(Analytics.serializer(), javaClass.getResource("/analytics/september.json")!!.readText())
        val analytics = data.copy(blocks = emptyList())
        val state = mutableStateOf(AnalyticsUiState(
            loading = false,
            data = analytics,
            blocks = AnalyticsBlocks(),
        ))
        compose.setContent {
            PfTheme {
                AnalyticsContent(state.value, AnalyticsActions({}, {}, {}, { _, _ -> }, null), Handlers())
            }
        }

        val popup = compose.onNodeWithTag(AnalyticsTags.ASK_HELP_POPUP)
        val initialText = compose.onNodeWithText("Помощник отвечает по вашим операциям, 5 вопросов в день")
        val serverText = compose.onNodeWithText("Помощник отвечает по вашим операциям, 10 вопросов в день")
        compose.onNodeWithTag(AnalyticsTags.ASK_HELP).assertIsDisplayed().performClick()
        popup.assertIsDisplayed()
        initialText.assertIsDisplayed()

        compose.runOnIdle {
            state.value = state.value.copy(
                limit = AssistantLimit(remaining = 8, dailyMax = 10, resetsAt = OffsetDateTime.parse("2026-09-26T00:00:00+03:00")),
            )
        }
        popup.assertIsDisplayed()
        serverText.assertIsDisplayed()
        initialText.assertDoesNotExist()

        // Return to the card with the quota already loaded and open its help again.
        compose.runOnIdle { state.value = state.value.copy(data = null) }
        popup.assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(data = analytics) }
        compose.onNodeWithTag(AnalyticsTags.ASK_HELP).performClick()
        serverText.assertIsDisplayed()
        initialText.assertDoesNotExist()
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
        override fun setTransferMode(mode: TransferMode) = Unit
        override fun currentTransferMode() = TransferMode.WITH
        override fun toggleExpanded(id: String) = Unit
        override fun onAction(block: Block, action: BlockAction, navigate: (BlockAction) -> Unit) = Unit
        override fun onBlockShown(block: Block) = Unit
    }
}
