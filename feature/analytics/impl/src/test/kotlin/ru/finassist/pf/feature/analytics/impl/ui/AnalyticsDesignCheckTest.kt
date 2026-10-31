package ru.finassist.pf.feature.analytics.impl.ui

import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.common.time.PeriodKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.screenshot.DesignCheck
import androidx.compose.runtime.CompositionLocalProvider
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.designsystem.theme.LocalPfClock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Artboards `Analytics` / `AnalyticsDark` and their states (`AnalyticsNoTransfers`, `AnalyticsTransfers`,
 * `AnalyticsHintCategories`, `AnalyticsHintAsk`, `AnalyticsLimit`, `AnalyticsYear`, `AnalyticsEmpty`, `AnalyticsEarly`,
 * `AnalyticsStale`, `AnalyticsPartial`): September 2026 by the 25th, numbers as in `Analytics.dc.html` renderVals(), laid
 * out as blocks by the server (golden responses in `src/test/resources/analytics`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnalyticsDesignCheckTest {
    private fun day(m: Int, d: Int) = OffsetDateTime.of(2026, m, d, 0, 0, 0, 0, ZoneOffset.ofHours(3))

    /**
     * Golden responses laid out by the server's composer (`AnalyticsDesignFixturesTest` in `:mock:backend` keeps them up
     * to date): the snapshots show the texts the server sends (FIN-4).
     */
    private fun golden(name: String): Analytics =
        ApiJson.decodeFromString(Analytics.serializer(), javaClass.getResource("/analytics/$name.json")!!.readText())

    private fun september(noTransfers: Boolean = false) = golden(if (noTransfers) "september_no_transfers" else "september")
    private val year2026 = golden("year_2026")
    private val early = golden("early")
    private val stale = golden("stale")
    private val analyticsState get() = september().state!!

    private val blocks = AnalyticsBlocks()

    private fun ui(data: Analytics) = AnalyticsUiState(
        loading = false,
        data = data,
        blocks = blocks,
        limit = AssistantLimit(remaining = 5, dailyMax = 5, resetsAt = day(9, 27)),
    )

    private val actions = AnalyticsActions(openSearch = {}, openChat = {}, openUpload = {}, openUnreadLines = { _, _ -> }, back = null)

    private class Handlers(private val mode: TransferMode) : AnalyticsHandlers {
        override fun load(quiet: Boolean) = Unit
        override fun retryPeriodChange() = Unit
        override fun consumeSnackbar() = Unit
        override fun setPeriodType(type: PeriodTypeCode) = Unit
        override fun goTo(key: PeriodKey) = Unit
        override fun openSheet(sheet: AnalyticsSheet?) = Unit
        override fun nextHint(stop: Boolean) = Unit
        override fun setTransferMode(mode: TransferMode) = Unit
        override fun currentTransferMode() = mode
        override fun toggleExpanded(id: String) = Unit
        override fun onAction(block: Block, action: BlockAction, navigate: (BlockAction) -> Unit) = Unit
        override fun onBlockShown(block: Block) = Unit
    }

    /** A fixed «now» in the profile zone: the stale banner dates its day by it, not by the machine's clock and zone. */
    private val clock = object : Clock {
        override val zone: ZoneId = Clock.MOSCOW
        override fun now(): OffsetDateTime = OffsetDateTime.of(2026, 10, 12, 12, 0, 0, 0, ZoneOffset.ofHours(3))
    }

    private fun capture(name: String, state: AnalyticsUiState, heightDp: Int, dark: Boolean = false, popups: Boolean = false) =
        DesignCheck.capture(name, dark = dark, heightDp = heightDp, tab = 1, popups = popups) {
            CompositionLocalProvider(LocalPfClock provides clock) {
                AnalyticsContent(state, actions, Handlers(state.data?.params?.transferMode ?: TransferMode.WITH))
            }
        }

    // FIN-394: proposed states; authored without running UI/DesignCheck.
    @Test fun analyticsRecalculating() = capture("AnalyticsRecalculating", ui(september().copy(state = analyticsState.copy(recalculating = true))), heightDp = 2848)
    @Test fun analyticsRecalculationPaused() = capture("AnalyticsRecalculationPaused", ui(september().copy(state = analyticsState.copy(recalculating = true))).copy(recalculationPaused = true), heightDp = 2848)
    @Test fun analyticsUpdating() = capture("AnalyticsUpdating", AnalyticsUiState(blocks = blocks), heightDp = 844)
    @Test fun analyticsPreparationTimeout() = capture("AnalyticsPreparationTimeout", AnalyticsUiState(loading = false, blocks = blocks, preparationTimedOut = true), heightDp = 844)
    @Test fun analyticsPreparationTimeoutDark() = capture("AnalyticsPreparationTimeoutDark", AnalyticsUiState(loading = false, blocks = blocks, preparationTimedOut = true), heightDp = 844, dark = true)
    @Test fun analyticsUpdatingDark() = capture("AnalyticsUpdatingDark", AnalyticsUiState(blocks = blocks), heightDp = 844, dark = true)
    @Test fun analyticsPreparingWithoutData() = capture("AnalyticsPreparingWithoutData", ui(september().copy(hasData = false,
        state = analyticsState.copy(recalculating = true))), heightDp = 844)

    private fun noData() = golden("no_data").also { response ->
        kotlin.test.assertTrue(response.hasData, "another period still has financial data")
        kotlin.test.assertEquals(ru.finassist.pf.core.api.model.Coverage.NO_DATA, response.period?.coverage)
        val tiles = response.blocks.single { it.id == "tiles" }
        kotlin.test.assertEquals(ru.finassist.pf.core.api.model.BlockStatus.NONE, tiles.status)
        kotlin.test.assertEquals("За этот период данных нет", tiles.emptyText)
        kotlin.test.assertTrue(tiles.decode(ru.finassist.pf.core.api.model.TileGroupData.serializer())!!.tiles.isEmpty())
    }

    @Test fun analyticsNoData() = capture("AnalyticsNoData", ui(noData()), heightDp = 844)
    @Test fun analyticsNoDataDark() = capture("AnalyticsNoDataDark", ui(noData()), heightDp = 844, dark = true)
    @Test fun analyticsNoDataTilesHidden() = capture("AnalyticsNoDataTilesHidden", ui(noData()).copy(blocks = blocks.copy(hidden = setOf("tiles"))), heightDp = 844)

    @Test fun analyticsForecastHistory() = capture("AnalyticsForecastHistory", ui(golden("rolling_year")), heightDp = 1600)
    @Test fun analyticsForecastHistoryDark() = capture("AnalyticsForecastHistoryDark", ui(golden("rolling_year")), heightDp = 1600, dark = true)

    // FIN-391: proposed artboards, authored and compiled only; no UI/DesignCheck execution.
    @Test fun analyticsPeriodChangeFailed() = capture("AnalyticsPeriodChangeFailed", ui(september()).copy(
        periodChangeErrorPresentation = ErrorPresentation()), heightDp = 2960)
    @Test fun analyticsPeriodChangeFailedDark() = capture("AnalyticsPeriodChangeFailedDark", ui(september()).copy(
        periodChangeErrorPresentation = ErrorPresentation()), heightDp = 2960, dark = true)
    @Test fun analyticsPeriodChangeRetryBlocked() = capture("AnalyticsPeriodChangeRetryBlocked", ui(september()).copy(
        periodChangeRetryReady = false, periodChangeErrorPresentation = ErrorPresentation()), heightDp = 2960)

    @Test fun analyticsPeriodAndDismissalErrors() = capture("AnalyticsPeriodAndDismissalErrors", ui(september()).copy(
        periodChangeErrorPresentation = ErrorPresentation(), errorPresentation = ErrorPresentation(),
        snackbar = "Не получилось убрать"), heightDp = 3080)

    @Test fun analytics() = capture("Analytics", ui(september()), heightDp = 2848)

    @Test fun analyticsDark() = capture("AnalyticsDark", ui(september()), heightDp = 2848, dark = true)

    @Test fun analyticsNoTransfers() = capture("AnalyticsNoTransfers", ui(september(noTransfers = true)), heightDp = 2792)

    @Test fun analyticsTransfers() =
        capture("AnalyticsTransfers", ui(september()).copy(sheet = AnalyticsSheet.TRANSFERS), heightDp = 844, popups = true)

    @Test fun analyticsHintCategories() =
        capture("AnalyticsHintCategories", ui(september()).copy(hint = AnalyticsHint.CATEGORIES), heightDp = 2848)

    @Test fun analyticsHintAsk() = capture("AnalyticsHintAsk", ui(september()).copy(hint = AnalyticsHint.ASK), heightDp = 2848)

    @Test fun analyticsLimit() =
        capture("AnalyticsLimit", ui(september()).copy(limit = AssistantLimit(remaining = 0, dailyMax = 5, resetsAt = day(9, 27))), heightDp = 2872)

    @Test fun analyticsYear() = capture("AnalyticsYear", ui(year2026), heightDp = 1700)

    @Test fun analyticsEmpty() = capture("AnalyticsEmpty", ui(Analytics(hasData = false)).copy(limit = null), heightDp = 844)

    @Test fun analyticsEarly() = capture("AnalyticsEarly", ui(early), heightDp = 2096)

    @Test fun analyticsStale() = capture("AnalyticsStale", ui(stale), heightDp = 2952)

    @Test fun analyticsPartial() =
        capture("AnalyticsPartial", ui(september().copy(state = analyticsState.copy(unreadLinesCount = 5))), heightDp = 2900)
    // FIN-223 fixture: proposed artboard; snapshots are not executed under the current instruction.
    @Test fun serverWarning() = capture("AnalyticsServerWarning", ui(september()).copy(errorPresentation = ErrorPresentation(UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"), ErrorSeverity.WARNING)), heightDp = 2960)

}
