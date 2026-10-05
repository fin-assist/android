package ru.finassist.pf.feature.analytics.impl.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.PeriodType
import ru.finassist.pf.core.navigation.AnalyticsParams
import ru.finassist.pf.core.network.codes.TransferMode
import ru.finassist.pf.core.network.dto.AnalyticsDto
import ru.finassist.pf.core.network.dto.AnalyticsPeriodItemDto
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flags
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.analytics.api.AnalyticsRoutes
import ru.finassist.pf.feature.analytics.impl.data.AnalyticsPrefs
import ru.finassist.pf.feature.analytics.impl.data.AnalyticsRepository
import ru.finassist.pf.feature.assistant.api.AssistantLimitRepository
import ru.finassist.pf.feature.operations.api.CategoriesRepository
import ru.finassist.pf.feature.statements.api.StatementsRepository
import javax.inject.Inject

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: AnalyticsRepository,
    private val prefs: AnalyticsPrefs,
    private val statements: StatementsRepository,
    private val categories: CategoriesRepository,
    private val assistantLimit: AssistantLimitRepository,
    private val flags: FeatureFlags,
    private val tracker: Tracker,
) : ViewModel() {

    /** Which blocks the remote config allows; read at screen boundaries. */
    data class Blocks(
        val tiles: Boolean = true,
        val expenseCategories: Boolean = true,
        val incomeCategories: Boolean = true,
        val monthlyChart: Boolean = true,
        val regularPayments: Boolean = true,
        val notableSpending: Boolean = true,
        val bankFees: Boolean = true,
        val smallFrequent: Boolean = true,
        val assistant: Boolean = true,
        val transfersFilter: Boolean = true,
        val upload: Boolean = true,
    )

    data class UiState(
        val loading: Boolean = true,
        val data: AnalyticsDto? = null,
        val error: AppError? = null,
        val periodType: PeriodType = PeriodType.Month,
        val transferMode: TransferMode = TransferMode.With,
        val blocks: Blocks = Blocks(),
        /** AskCard numbers; null until loaded (card shows the default «5 вопросов в день»). */
        val limitRemaining: Int? = null,
        val limitTotal: Int = 5,
        val hintCategories: Boolean = false,
        val hintAsk: Boolean = false,
        val showPeriodSheet: Boolean = false,
        val periods: List<AnalyticsPeriodItemDto>? = null,
        val periodsError: Boolean = false,
        val showTransfersSheet: Boolean = false,
        val allCategories: AllCategories? = null,
        val dismissing: Set<String> = emptySet(),
    )

    enum class AllCategories { Expense, Income }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var requestedDate: String? = null
    private var loadJob: Job? = null
    private var recalcJob: Job? = null

    init {
        val params = runCatching { savedState.toRoute<AnalyticsRoutes.Analytics>(AnalyticsRoutes.Analytics.typeMap).params }.getOrNull()
        viewModelScope.launch {
            // A chip from the assistant brings its own params; otherwise the last chosen mode is restored.
            val mode = params?.transferMode ?: prefs.transferMode.first()
            _state.update {
                it.copy(
                    periodType = PeriodType.fromWire(params?.period) ?: PeriodType.Month,
                    transferMode = TransferMode.fromWireOrWith(mode),
                )
            }
            requestedDate = params?.date
            refreshFlags()
            load()
        }
        viewModelScope.launch { statements.dataChanged.collect { load() } }
        viewModelScope.launch { categories.categories.collect { /* keeps the cache warm for icons */ } }
        viewModelScope.launch { prefs.dismissed(HINT_CATEGORIES).collect { d -> _state.update { it.copy(hintCategories = !d && it.data?.hasData == true) } } }
        viewModelScope.launch { prefs.dismissed(HINT_ASK).collect { d -> _state.update { it.copy(hintAsk = !d) } } }
        loadLimit()
    }

    fun refreshFlags() = _state.update {
        it.copy(
            blocks = Blocks(
                tiles = flags.isEnabled(Flags.analyticsBlockTiles),
                expenseCategories = flags.isEnabled(Flags.analyticsBlockExpenseCategories),
                incomeCategories = flags.isEnabled(Flags.analyticsBlockIncomeCategories),
                monthlyChart = flags.isEnabled(Flags.analyticsBlockMonthlyChart),
                regularPayments = flags.isEnabled(Flags.analyticsBlockRegularPayments),
                notableSpending = flags.isEnabled(Flags.analyticsBlockNotableSpending),
                bankFees = flags.isEnabled(Flags.analyticsBlockBankFees),
                smallFrequent = flags.isEnabled(Flags.analyticsBlockSmallFrequent),
                assistant = flags.isEnabled(Flags.assistant),
                transfersFilter = flags.isEnabled(Flags.analyticsFilterTransfers),
                upload = flags.isEnabled(Flags.statementsUpload),
            ),
        )
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val s = _state.value
            _state.update { it.copy(loading = it.data == null, error = null) }
            try {
                val dto = repo.analytics(s.periodType.wire, requestedDate, s.transferMode.wire)
                requestedDate = dto.params?.date
                _state.update { it.copy(loading = false, data = dto, error = null) }
                prefs.dismissed(HINT_CATEGORIES).first().let { d -> _state.update { it.copy(hintCategories = !d && dto.hasData) } }
                // Numbers may still change on the server (category edit, deletion) — poll a few times.
                if (dto.state?.recalculating == true) scheduleRecalcReload()
            } catch (e: AppError) {
                _state.update { it.copy(loading = false, error = e) }
            }
        }
    }

    private fun scheduleRecalcReload() {
        recalcJob?.cancel()
        recalcJob = viewModelScope.launch { delay(3000); load() }
    }

    fun loadLimit() = viewModelScope.launch {
        runCatching { assistantLimit.limit() }.onSuccess { l -> _state.update { it.copy(limitRemaining = l.remaining, limitTotal = l.dailyMax) } }
    }

    fun onPeriodType(type: PeriodType) {
        if (type == _state.value.periodType) return
        tracker.track("analytics.period_type", mapOf("type" to type.wire))
        // Keep the date: the server picks the quarter/year that contains it (api.md 6.1 `date`).
        _state.update { it.copy(periodType = type) }
        load()
    }

    fun onPrevious() = _state.value.data?.navigation?.previous?.let { go(it) }
    fun onNext() = _state.value.data?.navigation?.next?.let { go(it) }

    private fun go(date: String) { requestedDate = date; load() }

    fun onPeriodSheet(show: Boolean) {
        _state.update { it.copy(showPeriodSheet = show, periodsError = false) }
        if (show) viewModelScope.launch {
            _state.update { it.copy(periods = null) }
            try { _state.update { it.copy(periods = repo.periods(it.periodType.wire)) } } catch (e: AppError) { _state.update { it.copy(periodsError = true) } }
        }
    }

    fun onPeriodPicked(key: String) { _state.update { it.copy(showPeriodSheet = false) }; go(key) }

    fun onTransfersSheet(show: Boolean) = _state.update { it.copy(showTransfersSheet = show) }

    fun onTransferMode(mode: TransferMode) {
        _state.update { it.copy(transferMode = mode, showTransfersSheet = false) }
        viewModelScope.launch { prefs.setTransferMode(mode.wire) }
        tracker.track("analytics.transfer_mode", mapOf("mode" to mode.wire))
        load()
    }

    fun onAllCategories(which: AllCategories?) = _state.update { it.copy(allCategories = which) }

    fun dismissHint(id: String, forever: Boolean) = viewModelScope.launch {
        // «Понятно» hides the hint for this session only; «Больше не показывать» persists.
        if (forever) prefs.dismiss(id)
        _state.update { if (id == HINT_CATEGORIES) it.copy(hintCategories = false) else it.copy(hintAsk = false) }
    }

    /** «Не подписка»: the row disappears and the card total is recalculated on the server. */
    fun notSubscription(id: String) = viewModelScope.launch {
        _state.update { it.copy(dismissing = it.dismissing + id) }
        try {
            repo.dismissRegularPayment(id)
            tracker.track("analytics.regular_payment.dismissed")
            load()
        } catch (e: AppError) {
            if (e is AppError.NotFound) load()
        } finally {
            _state.update { it.copy(dismissing = it.dismissing - id) }
        }
    }

    companion object {
        const val HINT_CATEGORIES = "hint_categories"
        const val HINT_ASK = "hint_ask"
    }
}
