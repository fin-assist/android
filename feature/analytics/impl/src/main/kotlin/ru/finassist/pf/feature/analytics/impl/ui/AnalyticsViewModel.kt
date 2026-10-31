package ru.finassist.pf.feature.analytics.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.error.ErrorPresentation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.AnalyticsApi
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.AnalyticsPeriodItem
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.blocks.CatalogV1
import ru.finassist.pf.core.common.time.PeriodType
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.ReportZone
import ru.finassist.pf.core.toggles.Experiment
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.operations.api.OperationsRepository
import ru.finassist.pf.feature.statements.api.StatementsRepository
import javax.inject.Inject

/**
 * Switches read at the screen boundary: blocks switched off by `analytics.block.<id>` (FIN-4: a block id the client has
 * no flag for stays on), the transfers chip, the assistant card, the upload links.
 */
data class AnalyticsBlocks(
    val hidden: Set<String> = emptySet(),
    val transfersFilter: Boolean = true,
    val assistant: Boolean = true,
    val upload: Boolean = true,
)

enum class AnalyticsSheet { PERIODS, TRANSFERS }

/** Contextual hints after the first import, shown one after another. */
enum class AnalyticsHint { CATEGORIES, ASK }

data class AnalyticsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val data: Analytics? = null,
    val blocks: AnalyticsBlocks,
    val sheet: AnalyticsSheet? = null,
    val periods: List<AnalyticsPeriodItem>? = null,
    val limit: AssistantLimit? = null,
    /** Ids of lists shown in full («Все категории»). */
    val expanded: Set<String> = emptySet(),
    val snackbar: String? = null,
    val hint: AnalyticsHint? = null,
    val periodsFailed: Boolean = false,
    val periodsErrorPresentation: ErrorPresentation? = null,
    val periodsRetryable: Boolean? = null,
    val periodsRetryReady: Boolean = true,
    /** Number of the response on screen: the blocks of each one are exposed anew (FIN-9). */
    val response: Int = 0,
    val errorPresentation: ErrorPresentation? = null,
    val limitErrorPresentation: ErrorPresentation? = null,
    val requestRetryable: Boolean? = null,
    val retryReady: Boolean = true,
    val limitRetryReady: Boolean = true,
    val periodChangeErrorPresentation: ErrorPresentation? = null,
    val periodChangeRetryReady: Boolean = true,
    val preparationTimedOut: Boolean = false,
    val recalculationPaused: Boolean = false,
) {
    val periodChangeFailed: Boolean get() = periodChangeErrorPresentation != null
}

/**
 * Analytics (api.md 6.1–6.3). Parameters live here: period type, period key and transfer mode; every change
 * re-requests the server. Data changes elsewhere (import, delete, category edit) and a new profile zone (months
 * and periods are counted in it on the server, FIN-92) reload the current params.
 */
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val api: AnalyticsApi,
    private val assistantApi: AssistantApi,
    private val statements: StatementsRepository,
    operations: OperationsRepository,
    private val flags: FeatureFlags,
    private val tracker: Tracker,
    private val reportZone: ReportZone,
    private val elapsedClock: ElapsedClock,
) : ViewModel(), AnalyticsHandlers {
    private var period: PeriodTypeCode = PeriodTypeCode.MONTH
    private var date: PeriodKey? = null
    /** Successful selections belong to their own segment, independent of all other segments. */
    private val selectedDates = mutableMapOf<PeriodTypeCode, PeriodKey>()
    private var transferMode: TransferMode = TransferMode.WITH
    private var loadJob: Job? = null
    private var initialized = false
    private var importPolls = 0
    private var importPollRevision: Long? = null

    private val limitRetryGate = RequestRetryGate(elapsedClock)
    private var limitReadPending = false
    private val _state = MutableStateFlow(AnalyticsUiState(blocks = readBlocks(flags)))
    private val retryGate = RequestRetryGate(elapsedClock)
    private val periodChangeRetryGate = RequestRetryGate(elapsedClock)
    private var failedPeriodRequest: Triple<PeriodTypeCode, PeriodKey?, TransferMode>? = null
    init { viewModelScope.launch { periodChangeRetryGate.readiness.collect { ready -> _state.update { it.copy(periodChangeRetryReady = ready) } } } }
    init { viewModelScope.launch { retryGate.readiness.collect { ready -> _state.update { it.copy(retryReady = ready) } } } }
    init { viewModelScope.launch { limitRetryGate.readiness.collect { ready -> _state.update { it.copy(limitRetryReady = ready) } } } }
    val state: StateFlow<AnalyticsUiState> = _state
    private var periodsJob: Job? = null
    private var periodsGeneration = 0L
    private val periodsRetryGate = RequestRetryGate(elapsedClock)
    private val dismissalGates = mutableMapOf<String, RequestRetryGate>()

    init { viewModelScope.launch { periodsRetryGate.readiness.collect { ready -> _state.update { it.copy(periodsRetryReady = ready) } } } }

    private fun readBlocks(flags: FeatureFlags) = AnalyticsBlocks(
        hidden = Flag.entries.filter { it.key.startsWith(BLOCK_FLAG_PREFIX) && !flags.isEnabled(it) }
            .map { it.key.removePrefix(BLOCK_FLAG_PREFIX) }.toSet(),
        transfersFilter = flags.isEnabled(Flag.ANALYTICS_FILTER_TRANSFERS),
        assistant = flags.isEnabled(Flag.ASSISTANT),
        upload = flags.isEnabled(Flag.STATEMENTS_UPLOAD),
    )

    /** `key:variant,…` of the experiments over the composition (FIN-9), sent with every request. */
    private val experiments: String
        get() = Experiment.entries.joinToString(",") { "${it.key}:${flags.variant(it)}" }

    /** Blocks already reported as shown for the data on screen: one exposure per block and response. */
    private val exposed = HashSet<String>()

    /** [experiments] the data on screen was asked with: its exposures report these, not what the config says now. */
    private var shownExperiments: String? = null

    /** Set by the `Period` route (assistant chip); the tab root starts with the server's default period. */
    fun init(params: AnalyticsParams?) {
        if (initialized) return
        initialized = true
        if (params?.isValidSelection == true) {
            period = params.period
            date = params.date
            transferMode = allowedMode(params.transferMode)
        }
        tracker.track(Events.ANALYTICS_OPENED)
        load()
    }

    init {
        viewModelScope.launch {
            merge(statements.events.map { true }, operations.categoryChanges.map { false })
                .collect { invalidate -> reloadChangedData(invalidate = invalidate) }
        }
        viewModelScope.launch {
            reportZone.profile.map { reportZone.current }.distinctUntilChanged().drop(1).collect { if (loadJob != null) reloadChangedData() }
        }
        // Remote config can land after the screen opened (first launch, background sync): re-read the blocks.
        viewModelScope.launch {
            flags.changes.collect {
                _state.update { it.copy(blocks = readBlocks(flags)) }
                // The transfers filter was switched off remotely: from now on requests use `with` (docs/flags.md).
                var reload = false
                if (!_state.value.blocks.transfersFilter && transferMode != TransferMode.WITH) {
                    transferMode = TransferMode.WITH
                    reload = true
                }
                // A new experiment variant: the server composes the screen anew (FIN-9).
                if (shownExperiments != null && shownExperiments != experiments) reload = true
                if (reload && loadJob != null) {
                    clearRestriction()
                    load(quiet = true)
                }
            }
        }
    }

    /** Returning to a saved tab also refreshes changes whose event arrived while its view model was absent. */
    fun onResume(params: AnalyticsParams?) {
        if (!initialized) init(params)
        else if (!_state.value.preparationTimedOut && failedPeriodRequest == null && loadJob?.isActive != true) load(quiet = true)
    }

    /** Actual data/zone changes invalidate the old request context, including the period directory. */
    private fun reloadChangedData(invalidate: Boolean = false, clearSnackbar: Boolean = true) {
        if (!initialized) return
        clearRestriction(periodsChanged = true)
        importPolls = 0
        // An import or deletion invalidates the old totals. Other edits keep the screen and its scroll position.
        if (invalidate) _state.update { it.copy(data = null, loading = true, offline = false,
            snackbar = if (clearSnackbar) null else it.snackbar, preparationTimedOut = false) }
        load(quiet = !invalidate)
    }

    private fun clearRestriction(periodsChanged: Boolean = false) {
        clearPeriodChangeFailure()
        if (periodsChanged) resetPeriodsContext()
        retryGate.clear()
        _state.update { it.copy(requestRetryable = null, errorPresentation = null) }
    }

    private fun clearPeriodChangeFailure() {
        failedPeriodRequest = null
        periodChangeRetryGate.clear()
        _state.update { it.copy(periodChangeErrorPresentation = null) }
    }

    /** Explicit retry owns the failed target; a plain refresh still belongs to the period on screen. */
    override fun retryPeriodChange() {
        val request = failedPeriodRequest ?: return
        if (!periodChangeRetryGate.isReady()) return
        period = request.first
        date = request.second
        transferMode = request.third
        clearRestriction(periodsChanged = true)
        load()
    }

    private fun resetPeriodsContext() {
        invalidatePeriodsRequest()
        periodsRetryGate.clear()
        _state.update { it.copy(periodsRetryable = null) }
        if (_state.value.sheet == AnalyticsSheet.PERIODS) requestPeriods()
    }

    override fun refresh() {
        if (_state.value.refreshing) return
        val readAnalytics = loadJob?.isActive != true && retryGate.isReady()
        val readLimit = _state.value.blocks.assistant && !limitReadPending && limitRetryGate.isReady()
        if (!readAnalytics && !readLimit) return
        _state.update {
            if (readAnalytics) it.copy(refreshing = true, errorPresentation = null, snackbar = null, requestRetryable = null)
            else it.copy(refreshing = true)
        }
        viewModelScope.launch {
            val quotaJob = if (readLimit) launch { refreshLimit(reportOffline = true) } else null
            val analyticsJob = if (readAnalytics) {
                startLoad(quiet = true, updateLimit = false, preservePeriodChangeFailure = true)
                loadJob
            } else null
            try {
                analyticsJob?.join()
                quotaJob?.join()
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    override fun load(quiet: Boolean) = startLoad(quiet, updateLimit = true)

    private fun startLoad(quiet: Boolean, updateLimit: Boolean, preservePeriodChangeFailure: Boolean = false) {
        if (!retryGate.isReady()) return
        if (_state.value.preparationTimedOut) importPolls = 0
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!quiet) _state.update { it.copy(loading = it.data == null, offline = false, errorPresentation = null, requestRetryable = null) }
            _state.update { it.copy(recalculationPaused = false) }
            val requested = Triple(period, date, transferMode)
            try {
                val pendingImport = statements.pendingAnalyticsImport()
                if (pendingImport != importPollRevision) {
                    importPollRevision = pendingImport
                    importPolls = 0
                }
                val exp = experiments
                val a = api.getAnalytics(requested.first, requested.second, requested.third, supports = supports, exp = exp)
                // `complete` confirms the ledger commit, while the analytics projection can still be absent.
                // Never turn that known non-empty import into an instruction to upload the same file again.
                if (pendingImport != null && !a.hasData) {
                    importPolls++
                    val expired = importPolls >= MAX_IMPORT_POLLS
                    _state.update { it.copy(data = null, loading = !expired, offline = false,
                        preparationTimedOut = expired, errorPresentation = null, requestRetryable = null) }
                    if (!expired) {
                        delay(RECALC_POLL_MS)
                        startLoad(quiet = true, updateLimit = updateLimit, preservePeriodChangeFailure = preservePeriodChangeFailure)
                    }
                    return@launch
                }
                if (pendingImport != null && a.state?.recalculating != true) statements.analyticsImportReady(pendingImport)
                importPolls = 0
                // A pull refresh reads the shown period; its result cannot retire another target's refusal.
                if (!preservePeriodChangeFailure) clearPeriodChangeFailure()
                exposed.clear()
                shownExperiments = exp
                a.params?.let { p ->
                    val periodsChanged = period != p.period
                    period = p.period
                    date = p.date
                    selectedDates[p.period] = p.date
                    transferMode = allowedMode(p.transferMode)
                    if (periodsChanged) resetPeriodsContext()
                }
                _state.update { it.copy(loading = false, offline = false, errorPresentation = null, requestRetryable = null, preparationTimedOut = false, data = a, response = it.response + 1) }
                if (a.hasData && _state.value.hint == null && statements.consumeFirstImportHints()) {
                    _state.update { it.copy(hint = AnalyticsHint.CATEGORIES) }
                }
                if (updateLimit && _state.value.blocks.assistant) refreshLimit()
                // The server is still recalculating after an edit: ask again shortly (api.md 6.1 `recalculating`).
                if (a.state?.recalculating == true) {
                    delay(RECALC_POLL_MS)
                    startLoad(quiet = true, updateLimit = updateLimit, preservePeriodChangeFailure = preservePeriodChangeFailure)
                }
            } catch (e: AppError) {
                importPolls = 0
                retryGate.record(e)
                // The screen keeps showing the previous data: return the parameters to what it shows, or the
                // segment, the chip and the transfers sheet would describe a request that never succeeded.
                _state.value.data?.params?.let { p ->
                    val periodsChanged = period != p.period
                    period = p.period
                    date = p.date
                    transferMode = allowedMode(p.transferMode)
                    if (periodsChanged) resetPeriodsContext()
                }
                // The refusal belongs to the failed params, not the different period restored on screen.
                // Keep its explanation until the user requests that restored context; never retry automatically.
                if (requested != Triple(period, date, transferMode)) retryGate.clear()
                val failedSwitch = _state.value.data != null && requested.first != period
                if (failedSwitch) {
                    failedPeriodRequest = requested
                    periodChangeRetryGate.record(e)
                } else if (!preservePeriodChangeFailure) clearPeriodChangeFailure()
                _state.update {
                    it.copy(
                        loading = false,
                        periodChangeErrorPresentation = when {
                            failedSwitch -> e.presentation
                            preservePeriodChangeFailure -> it.periodChangeErrorPresentation
                            else -> null
                        },
                        recalculationPaused = it.data?.state?.recalculating == true,
                        errorPresentation = if (failedSwitch || (e is AppError.Offline && it.data == null)) null else e.presentation,
                        requestRetryable = e.requestRetryable,
                        offline = it.data == null,
                        snackbar = if (failedSwitch) null else if (quiet || it.data == null) it.snackbar
                        else if (e is AppError.Offline) "Прежний период сохранён" else "Не получилось обновить. Попробуйте ещё раз",
                    )
                }
            }
        }
    }

    private suspend fun refreshLimit(reportOffline: Boolean = false) {
        if (limitReadPending || !limitRetryGate.isReady()) return
        limitReadPending = true
        try {
            val limit = assistantApi.getLimit()
            _state.update { it.copy(limit = limit, limitErrorPresentation = null) }
        } catch (failure: AppError) {
            limitRetryGate.record(failure)
            if (reportOffline || failure !is AppError.Offline) _state.update { it.copy(limitErrorPresentation = failure.presentation) }
        } finally {
            limitReadPending = false
        }
    }

    /** Restore this segment's choice; only a first visit derives a key from the displayed period. */
    override fun setPeriodType(type: PeriodTypeCode) {
        if (type == PeriodTypeCode.UNKNOWN || type == period) return
        val targetDate = dateForType(type)
        // The segment is another entry point to the same failed request, including its wait/refusal.
        if (failedPeriodRequest == Triple(type, targetDate, transferMode)) {
            retryPeriodChange()
            return
        }
        date = targetDate
        period = type
        clearRestriction(periodsChanged = true)
        tracker.track(Events.ANALYTICS_PERIOD_CHANGED, mapOf("period" to type.code))
        load()
    }

    private fun dateForType(type: PeriodTypeCode): PeriodKey? {
        if (type == PeriodTypeCode.ALL_TIME) return PeriodKey.ALL_TIME
        selectedDates[type]?.let { return it }
        val target = PeriodType.fromCode(type.code) ?: return null
        val shown = _state.value.data
        val key = shown?.params?.date ?: date ?: return null
        // The API accepts exact or finer keys. Coarsening preserves the existing calendar anchor.
        if (key.isCalendar && key.type.ordinal <= target.ordinal) return key
        val info = shown?.period ?: return null
        // dataTo is an inclusive covered instant; only the calendar range has an exclusive end.
        val last = if (key.isCalendar && info.coverage == Coverage.COMPLETE) info.range.to.minusNanos(1) else info.dataTo
        if (last == null || last !in info.range) return null
        return PeriodKey.of(target, last.atZoneSameInstant(reportZone.current).toLocalDate())
    }

    override fun goTo(key: PeriodKey) {
        _state.update { it.copy(sheet = null) }
        if (key == date) return
        clearRestriction()
        date = key
        tracker.track(Events.ANALYTICS_PERIOD_CHANGED, mapOf("period" to period.code))
        load()
    }

    override fun setTransferMode(mode: TransferMode) {
        _state.update { it.copy(sheet = null) }
        if (mode == transferMode) return
        clearRestriction()
        transferMode = mode
        tracker.track(Events.ANALYTICS_TRANSFERS_CHANGED, mapOf("mode" to mode.code))
        load()
    }

    override fun currentTransferMode(): TransferMode = transferMode

    /** With `analytics.filter.transfers` off every request uses `with` (docs/flags.md), whatever a chip says. */
    private fun allowedMode(mode: TransferMode): TransferMode =
        if (_state.value.blocks.transfersFilter) mode.effective else TransferMode.WITH

    /** Next hint, or none: «Не показывать» skips the rest too. */
    override fun nextHint(stop: Boolean) = _state.update {
        it.copy(hint = if (stop || it.hint == AnalyticsHint.ASK || !it.blocks.assistant) null else AnalyticsHint.ASK)
    }

    override fun openSheet(sheet: AnalyticsSheet?) {
        _state.update { it.copy(sheet = sheet) }
        if (sheet == AnalyticsSheet.PERIODS) requestPeriods()
        else invalidatePeriodsRequest() // Closing preserves the current context's refusal/wait.
    }

    private fun invalidatePeriodsRequest() {
        periodsGeneration++
        periodsJob?.cancel()
        periodsJob = null
    }

    private fun periodsContextIsCurrent(generation: Long, type: PeriodTypeCode, zone: java.time.ZoneId): Boolean {
        if (generation != periodsGeneration || type != period) return false
        if (zone == reportZone.current) return true
        // Device fallback changes do not emit profile state. Refresh the visible directory instead of leaving a loader.
        if (reportZone.profile.value == null) resetPeriodsContext()
        return false
    }

    private fun requestPeriods() {
        if (!periodsRetryGate.isReady()) return
        invalidatePeriodsRequest()
        val generation = periodsGeneration
        val type = period
        val zone = reportZone.current
        _state.update { it.copy(periods = null, periodsFailed = false, periodsErrorPresentation = null, periodsRetryable = null) }
        periodsJob = viewModelScope.launch {
            try {
                val list = api.listPeriods(type)
                if (!periodsContextIsCurrent(generation, type, zone)) return@launch
                _state.update { it.copy(periods = list.periods) }
            } catch (e: AppError) {
                if (!periodsContextIsCurrent(generation, type, zone)) return@launch
                periodsRetryGate.record(e)
                _state.update { it.copy(periodsFailed = true, periodsErrorPresentation = e.presentation, periodsRetryable = e.requestRetryable) }
            }
        }
    }

    override fun toggleExpanded(id: String) = _state.update { s ->
        s.copy(expanded = if (id in s.expanded) s.expanded - id else s.expanded + id)
    }

    /** An action of a block (api.md «Действия»); `open_*` navigate, so the screen passes them on with [navigate]. */
    override fun onAction(block: Block, action: BlockAction, navigate: (BlockAction) -> Unit) {
        when (action.type) {
            ActionType.DISMISS_REGULAR_PAYMENT -> action.paymentId?.let(::dismissRegular)
            ActionType.OPEN_OPERATIONS -> {
                if (block.id == TILES_ID) tracker.track(Events.ANALYTICS_TILE_OPENED, mapOf("block" to TILES_ID))
                navigate(action)
            }
            ActionType.OPEN_ASSISTANT -> navigate(action)
            ActionType.UNKNOWN -> Unit
        }
    }

    /** Exposure (FIN-9): the block was drawn — once per block id and response. */
    override fun onBlockShown(block: Block) {
        val id = block.id ?: return
        val exp = shownExperiments ?: return
        if (!exposed.add(id)) return
        tracker.track(Events.ANALYTICS_BLOCK_EXPOSED, mapOf("block" to id, "exp" to exp))
    }

    /** «Не подписка» (api.md 6.3): the payment disappears and the total is recalculated. */
    fun dismissRegular(id: String) {
        val gate = dismissalGates.getOrPut(id) { RequestRetryGate(elapsedClock) }
        if (!gate.isReady()) return
        viewModelScope.launch {
            try {
                api.dismissRegularPayment(id)
                tracker.track(Events.ANALYTICS_REGULAR_DISMISSED)
                _state.update { it.copy(snackbar = "Убрали из регулярных платежей") }
                reloadChangedData(clearSnackbar = false)
            } catch (e: AppError) {
                gate.record(e)
                _state.update { it.copy(errorPresentation = e.presentation, snackbar = if (e is AppError.Offline) "Не получилось убрать" else "Не получилось убрать. Попробуйте ещё раз") }
            }
        }
    }

    override fun consumeSnackbar() = _state.update { it.copy(snackbar = null, errorPresentation = null) }

    private companion object {
        const val RECALC_POLL_MS = 3_000L
        const val MAX_IMPORT_POLLS = 10
        const val BLOCK_FLAG_PREFIX = "analytics.block."
        const val TILES_ID = "tiles"

        /** What this client draws (api.md 6.1 `supports`), from the renderer registry (FIN-7). */
        val supports: String = CatalogV1.registry().supports
    }
}
