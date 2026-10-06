package ru.finassist.pf.feature.analytics.impl.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.AnalyticsApi
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.AnalyticsPeriodItem
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.operations.api.OperationsRepository
import ru.finassist.pf.feature.statements.api.StatementsRepository
import javax.inject.Inject

/** Which blocks of the screen are on (toggles `analytics.block.*`, `analytics.filter.transfers`, `assistant`). */
data class AnalyticsBlocks(
    val tiles: Boolean,
    val expenseCategories: Boolean,
    val incomeCategories: Boolean,
    val monthlyChart: Boolean,
    val regularPayments: Boolean,
    val notableSpending: Boolean,
    val bankFees: Boolean,
    val smallFrequent: Boolean,
    val transfersFilter: Boolean,
    val assistant: Boolean,
    val upload: Boolean,
)

enum class AnalyticsSheet { PERIODS, TRANSFERS }

/** Contextual hints after the first import, shown one after another. */
enum class AnalyticsHint { CATEGORIES, ASK }

data class AnalyticsUiState(
    val loading: Boolean = true,
    val offline: Boolean = false,
    val data: Analytics? = null,
    val blocks: AnalyticsBlocks,
    val sheet: AnalyticsSheet? = null,
    val periods: List<AnalyticsPeriodItem>? = null,
    val limit: AssistantLimit? = null,
    val allExpenseCategories: Boolean = false,
    val snackbar: String? = null,
    val hint: AnalyticsHint? = null,
)

/**
 * Analytics (api.md 6.1–6.3). Parameters live here: period type, period key and transfer mode; every change
 * re-requests the server. Data changes elsewhere (import, delete, category edit) reload the current params.
 */
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val api: AnalyticsApi,
    private val assistantApi: AssistantApi,
    private val statements: StatementsRepository,
    operations: OperationsRepository,
    flags: FeatureFlags,
    private val tracker: Tracker,
) : ViewModel() {
    private var period: PeriodTypeCode = PeriodTypeCode.MONTH
    private var date: PeriodKey? = null
    private var transferMode: TransferMode = TransferMode.WITH
    private var loadJob: Job? = null

    private val _state = MutableStateFlow(
        AnalyticsUiState(
            blocks = AnalyticsBlocks(
                tiles = flags.isEnabled(Flag.ANALYTICS_BLOCK_TILES),
                expenseCategories = flags.isEnabled(Flag.ANALYTICS_BLOCK_EXPENSE_CATEGORIES),
                incomeCategories = flags.isEnabled(Flag.ANALYTICS_BLOCK_INCOME_CATEGORIES),
                monthlyChart = flags.isEnabled(Flag.ANALYTICS_BLOCK_MONTHLY_CHART),
                regularPayments = flags.isEnabled(Flag.ANALYTICS_BLOCK_REGULAR_PAYMENTS),
                notableSpending = flags.isEnabled(Flag.ANALYTICS_BLOCK_NOTABLE_SPENDING),
                bankFees = flags.isEnabled(Flag.ANALYTICS_BLOCK_BANK_FEES),
                smallFrequent = flags.isEnabled(Flag.ANALYTICS_BLOCK_SMALL_FREQUENT),
                transfersFilter = flags.isEnabled(Flag.ANALYTICS_FILTER_TRANSFERS),
                assistant = flags.isEnabled(Flag.ASSISTANT),
                upload = flags.isEnabled(Flag.STATEMENTS_UPLOAD),
            ),
        ),
    )
    val state: StateFlow<AnalyticsUiState> = _state

    /** Set by the `Period` route (assistant chip); the tab root starts with the server's default period. */
    fun init(params: AnalyticsParams?) {
        if (_state.value.data != null || loadJob != null) return
        if (params != null) {
            period = params.period
            date = params.date
            transferMode = allowedMode(params.transferMode)
        }
        tracker.track(Events.ANALYTICS_OPENED)
        load()
    }

    init {
        viewModelScope.launch { merge(statements.events, operations.categoryChanges).collect { load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!quiet) _state.update { it.copy(loading = it.data == null, offline = false) }
            try {
                val a = api.getAnalytics(period, date, transferMode)
                a.params?.let { p ->
                    period = p.period
                    date = p.date
                    transferMode = allowedMode(p.transferMode)
                }
                _state.update { it.copy(loading = false, offline = false, data = a) }
                if (a.hasData && _state.value.hint == null && statements.consumeFirstImportHints()) {
                    _state.update { it.copy(hint = AnalyticsHint.CATEGORIES) }
                }
                if (_state.value.blocks.assistant) refreshLimit()
                // The server is still recalculating after an edit: ask again shortly (api.md 6.1 `recalculating`).
                if (a.state?.recalculating == true) {
                    delay(RECALC_POLL_MS)
                    load(quiet = true)
                }
            } catch (e: AppError) {
                _state.update { it.copy(loading = false, offline = it.data == null) }
            }
        }
    }

    private suspend fun refreshLimit() {
        runCatching { assistantApi.getLimit() }.onSuccess { l -> _state.update { it.copy(limit = l) } }
    }

    /** Segmented control: keeps the current date so «Месяц → Квартал» opens the quarter containing it. */
    fun setPeriodType(type: PeriodTypeCode) {
        if (type == period) return
        period = type
        tracker.track(Events.ANALYTICS_PERIOD_CHANGED, mapOf("period" to type.code))
        load()
    }

    fun goTo(key: PeriodKey) {
        date = key
        _state.update { it.copy(sheet = null) }
        tracker.track(Events.ANALYTICS_PERIOD_CHANGED, mapOf("period" to period.code))
        load()
    }

    fun setTransferMode(mode: TransferMode) {
        _state.update { it.copy(sheet = null) }
        if (mode == transferMode) return
        transferMode = mode
        tracker.track(Events.ANALYTICS_TRANSFERS_CHANGED, mapOf("mode" to mode.code))
        load()
    }

    fun currentTransferMode(): TransferMode = transferMode

    /** With `analytics.filter.transfers` off every request uses `with` (docs/flags.md), whatever a chip says. */
    private fun allowedMode(mode: TransferMode): TransferMode =
        if (_state.value.blocks.transfersFilter) mode.effective else TransferMode.WITH

    /** Next hint, or none: «Не показывать» skips the rest too. */
    fun nextHint(stop: Boolean) = _state.update {
        it.copy(hint = if (stop || it.hint == AnalyticsHint.ASK || !it.blocks.assistant) null else AnalyticsHint.ASK)
    }

    fun openSheet(sheet: AnalyticsSheet?) {
        _state.update { it.copy(sheet = sheet, periods = if (sheet == AnalyticsSheet.PERIODS) null else it.periods) }
        if (sheet == AnalyticsSheet.PERIODS) {
            // The list is requested when the sheet opens, not in advance (api.md 6.2).
            viewModelScope.launch {
                runCatching { api.listPeriods(period) }.onSuccess { list -> _state.update { it.copy(periods = list.periods) } }
            }
        }
    }

    fun toggleAllCategories() = _state.update { it.copy(allExpenseCategories = !it.allExpenseCategories) }

    /** «Не подписка» (api.md 6.3): the payment disappears and the total is recalculated. */
    fun dismissRegular(id: String) {
        viewModelScope.launch {
            try {
                api.dismissRegularPayment(id)
                tracker.track(Events.ANALYTICS_REGULAR_DISMISSED)
                _state.update { it.copy(snackbar = "Убрали из регулярных платежей") }
                load(quiet = true)
            } catch (e: AppError) {
                _state.update { it.copy(snackbar = if (e is AppError.Offline) "Нет сети — не получилось убрать" else "Не получилось убрать. Попробуйте ещё раз") }
            }
        }
    }

    fun consumeSnackbar() = _state.update { it.copy(snackbar = null) }

    fun onTileOpened(tile: String) = tracker.track(Events.ANALYTICS_TILE_OPENED, mapOf("tile" to tile))

    private companion object {
        const val RECALC_POLL_MS = 3_000L
    }
}
