package ru.finassist.pf.feature.analytics.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.error.ErrorPresentation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import ru.finassist.pf.core.api.RequestOwner
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.AnalyticsApi
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsIntegrity
import ru.finassist.pf.core.common.time.SourceTimeZone
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.AnalyticsPeriodItem
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.blocks.CatalogV1
import ru.finassist.pf.core.blocks.AnalyticsPeriodTargets
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
import ru.finassist.pf.feature.analytics.impl.domain.AnalyticsSnapshots
import ru.finassist.pf.feature.analytics.impl.domain.MonthlyChartPages
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
    val sourceZoneUnsupported: Boolean = false,
    val unknownMutation: Boolean = false,
    val unknownReadRecoveryAvailable: Boolean = false,
    val unknownReadRecoveryUsed: Boolean = false,
    val data: Analytics? = null,
    val blocks: AnalyticsBlocks,
    val sheet: AnalyticsSheet? = null,
    val periods: List<AnalyticsPeriodItem>? = null,
    val periodsTimeZone: String? = null,
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
    /** Read status is separate from the immutable last-ready financial response. */
    val recalculating: Boolean = false,
    val chartBlock: Block? = null,
    val chartLoading: Boolean = false,
    val chartError: ErrorPresentation? = null,
    val chartRetryReady: Boolean = true,
    val chartHasOlder: Boolean = false,
    val chartHasNewer: Boolean = false,
    val owner: RequestOwner? = null,
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
    private val session: SessionRepository,
) : ViewModel(), AnalyticsHandlers {
    private var period: PeriodTypeCode = PeriodTypeCode.MONTH
    private var date: PeriodKey? = null
    /** Successful selections belong to their own segment, independent of all other segments. */
    private val selectedDates = mutableMapOf<PeriodTypeCode, PeriodKey>()
    private var transferMode: TransferMode = TransferMode.WITH
    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var readInterruptedByMutation = false
    private val snapshots = AnalyticsSnapshots()
    private val chartPages = MonthlyChartPages()
    private var chartJob: Job? = null
    private var chartGeneration = 0L
    private var chartFailure: Pair<String, Boolean>? = null
    private val chartRetryGate = RequestRetryGate(elapsedClock)
    private var chartRevisionRecoveryUsed = false
    private var recalculationPolls = 0
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
    init { viewModelScope.launch { chartRetryGate.readiness.collect { ready -> _state.update { it.copy(chartRetryReady = ready) } } } }
    private var periodsJob: Job? = null
    private var periodsGeneration = 0L
    private val periodsRetryGate = RequestRetryGate(elapsedClock)
    private data class DismissalContext(val params: AnalyticsParams, val timeZone: String?, val readyVersion: Int?) {
        fun differsFrom(original: DismissalContext) = params != original.params || timeZone != original.timeZone ||
            (readyVersion != null && original.readyVersion != null && readyVersion > original.readyVersion)
    }
    private val dismissalGates = mutableMapOf<String, RequestRetryGate>()
    private val dismissalContexts = mutableMapOf<String, DismissalContext>()
    private var regularMutations = Mutex()
    private var inFlightOwner: RequestOwner? = null
    private var requestOwner: RequestOwner? = null
    private val mutationJobs = mutableSetOf<Job>()
    private val pendingRegularMutations = mutableSetOf<String>()
    private val mutationContexts = mutableMapOf<String, AnalyticsParams>()
    private val unknownMutationFailures = mutableSetOf<String>()

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

    init {
        viewModelScope.launch {
            session.state.collect { state ->
                val signed = state as? SessionState.SignedIn
                val next = signed?.authSessionEpoch?.takeIf { it.isNotBlank() && signed.userId.isNotBlank() }
                    ?.let { RequestOwner(signed.userId, it) }
                val previous = requestOwner
                if (previous?.userId == next?.userId && previous?.authSessionEpoch == next?.authSessionEpoch) return@collect
                requestOwner = next
                invalidateAnalyticsRead()
                invalidatePeriodsRequest()
                mutationJobs.toList().forEach(Job::cancel)
                mutationJobs.clear()
                regularMutations = Mutex()
                inFlightOwner = null
                pendingRegularMutations.clear()
                mutationContexts.clear()
                dismissalGates.clear()
                dismissalContexts.clear()
                snapshots.resetOwner()
                chartPages.clear()
                chartFailure = null
                chartRevisionRecoveryUsed = false
                chartRetryGate.clear()
                retryGate.clear()
                periodsRetryGate.clear()
                periodChangeRetryGate.clear()
                limitRetryGate.clear()
                limitReadPending = false
                failedPeriodRequest = null
                selectedDates.clear()
                unknownMutationFailures.clear()
                exposed.clear()
                shownExperiments = null
                period = PeriodTypeCode.MONTH
                date = null
                transferMode = TransferMode.WITH
                importPollRevision = null
                importPolls = 0
                recalculationPolls = 0
                _state.value = AnalyticsUiState(blocks = readBlocks(flags), owner = next)
                if (initialized && next != null) load()
            }
        }
    }

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
            reportZone.profile.map { reportZone.current }.distinctUntilChanged().drop(1).collect {
                if (loadJob != null) reloadChangedData()
            }
        }
        // Remote config can land after the screen opened (first launch, background sync): re-read the blocks.
        viewModelScope.launch {
            flags.changes.collect {
                _state.update { it.copy(blocks = readBlocks(flags)) }
                // The transfers filter was switched off remotely: from now on requests use `with` (docs/flags.md).
                var reload = false
                if (!_state.value.blocks.transfersFilter) {
                    if (failedPeriodRequest?.third?.let { it != TransferMode.WITH } == true) clearPeriodChangeFailure()
                    if (transferMode != TransferMode.WITH) {
                        transferMode = TransferMode.WITH
                        reload = true
                    }
                }
                // A new experiment variant: the server composes the screen anew (FIN-9).
                if (shownExperiments != null && shownExperiments != experiments) {
                    snapshots.clear()
                    reload = true
                }
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
        else if (!_state.value.preparationTimedOut && (!_state.value.recalculationPaused || _state.value.sourceZoneUnsupported) && failedPeriodRequest == null && loadJob?.isActive != true) load(quiet = true)
    }

    /** Actual data/zone changes invalidate the old request context, including the period directory. */
    private fun reloadChangedData(invalidate: Boolean = false, clearSnackbar: Boolean = true) {
        if (!initialized) return
        clearRestriction(periodsChanged = true)
        importPolls = 0
        recalculationPolls = 0
        // An import or deletion invalidates the old totals. Other edits keep the screen and its scroll position.
        if (invalidate) {
            snapshots.clear()
            updateUnknownMutationNotice()
            chartPages.clear()
            updateChartState()
            _state.update { it.copy(data = null, loading = true, offline = false,
                snackbar = if (clearSnackbar) null else it.snackbar, preparationTimedOut = false) }
        }
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
        val typeChanged = request.first != period
        period = request.first
        date = request.second
        transferMode = request.third
        clearRestriction(periodsChanged = typeChanged)
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
        if (readAnalytics) recalculationPolls = 0
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

    override fun load(quiet: Boolean) {
        recalculationPolls = 0
        startLoad(quiet, updateLimit = true)
    }

    private fun invalidateAnalyticsRead(forMutation: Boolean = false) {
        if (forMutation && loadJob?.isActive == true) readInterruptedByMutation = true
        loadGeneration++
        loadJob?.cancel()
        chartGeneration++
        chartJob?.cancel()
        _state.update { it.copy(chartLoading = false) }
    }

    private fun startLoad(quiet: Boolean, updateLimit: Boolean, preservePeriodChangeFailure: Boolean = false) {
        val owner = requestOwner ?: return
        if (inFlightOwner != null) {
            readInterruptedByMutation = true
            return
        }
        if (!retryGate.isReady()) return
        readInterruptedByMutation = false
        if (_state.value.preparationTimedOut) importPolls = 0
        invalidateAnalyticsRead()
        val generation = loadGeneration
        val requested = Triple(period, date, transferMode)
        loadJob = viewModelScope.launch(owner) {
            if (!quiet) _state.update { it.copy(loading = it.data == null, offline = false, errorPresentation = null, requestRetryable = null) }
            _state.update { it.copy(recalculationPaused = false) }
            try {
                val pendingImport = statements.pendingAnalyticsImport()
                if (generation != loadGeneration) return@launch
                if (pendingImport != importPollRevision) {
                    importPollRevision = pendingImport
                    importPolls = 0
                }
                val exp = experiments
                val a = api.getAnalytics(requested.first, requested.second, requested.third, supports = supports, exp = exp)
                // Cancellation is advisory: transports and fakes can still complete after a newer read or write.
                if (generation != loadGeneration) return@launch
                AnalyticsIntegrity.requireValid(a)
                val requestedParams = requested.second?.let { key ->
                    val normalized = if (key.isCalendar) PeriodType.fromCode(requested.first.code)?.let { PeriodKey.of(it, key.start()) } ?: key else key
                    AnalyticsParams(requested.first, normalized, requested.third)
                }
                if (a.hasData && (a.params!!.period != requested.first || a.params!!.transferMode != requested.third ||
                    (requestedParams != null && a.params != requestedParams))) throw AppError.Server(500)
                val recalculating = snapshots.isWaiting(a)
                if (!recalculating && a.hasData) chartPages.validate(a)
                // `complete` confirms the ledger commit, while the analytics projection can still be absent.
                // Never turn that known non-empty import into an instruction to upload the same file again.
                if (pendingImport != null && !a.hasData) {
                    importPolls++
                    val expired = importPolls >= MAX_IMPORT_POLLS
                    _state.update { it.copy(data = null, loading = !expired, offline = false,
                        preparationTimedOut = expired, errorPresentation = null, requestRetryable = null) }
                    if (!expired) {
                        delay(RECALC_POLL_MS)
                        if (generation != loadGeneration) return@launch
                        startLoad(quiet = true, updateLimit = updateLimit, preservePeriodChangeFailure = preservePeriodChangeFailure)
                    }
                    return@launch
                }
                if (pendingImport != null && !recalculating) statements.analyticsImportReady(pendingImport)
                if (generation != loadGeneration) return@launch
                val showImportHint = !recalculating && a.hasData && _state.value.hint == null &&
                    statements.consumeFirstImportHints()
                if (generation != loadGeneration) return@launch
                importPolls = 0
                // A pull refresh reads the shown period; its result cannot retire another target's refusal.
                if (!preservePeriodChangeFailure) clearPeriodChangeFailure()
                a.params?.let { p ->
                    val periodsChanged = period != p.period
                    period = p.period
                    date = p.date
                    selectedDates[p.period] = p.date
                    transferMode = allowedMode(p.transferMode)
                    if (periodsChanged) resetPeriodsContext()
                }
                recalculationPolls = if (recalculating) recalculationPolls + 1 else 0
                val expired = recalculating && recalculationPolls >= MAX_IMPORT_POLLS
                val displayed = snapshots.display(a, requestedParams)
                if (!recalculating || displayed != _state.value.data) {
                    if (displayed == null) chartPages.clear() else chartPages.accept(displayed)
                    chartFailure = null
                    chartRetryGate.clearOriginalRefusal()
                    updateChartState()
                }
                val newExposure = !recalculating || displayed != _state.value.data
                if (newExposure) {
                    exposed.clear()
                    shownExperiments = exp
                }
                _state.update { it.copy(loading = displayed == null && !expired && !snapshots.freezesUnknownReads, offline = false, errorPresentation = null, sourceZoneUnsupported = false,
                    requestRetryable = null, preparationTimedOut = displayed == null && expired, data = displayed,
                    hint = if (showImportHint) AnalyticsHint.CATEGORIES else it.hint,
                    recalculating = recalculating, recalculationPaused = expired || snapshots.freezesUnknownReads, response = it.response + if (newExposure) 1 else 0) }
                updateUnknownMutationNotice()
                if (generation != loadGeneration) return@launch
                if (updateLimit && _state.value.blocks.assistant) refreshLimit()
                // The server is still recalculating after an edit: ask again shortly (api.md 6.1 `recalculating`).
                if (recalculating && !expired && !snapshots.freezesUnknownReads) {
                    delay(RECALC_POLL_MS)
                    if (generation != loadGeneration) return@launch
                    startLoad(quiet = true, updateLimit = updateLimit, preservePeriodChangeFailure = preservePeriodChangeFailure)
                }
            } catch (e: AppError) {
                if (generation != loadGeneration) return@launch
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
                val failedSwitch = _state.value.data != null && requested != Triple(period, date, transferMode)
                if (failedSwitch) {
                    failedPeriodRequest = requested
                    periodChangeRetryGate.record(e)
                } else if (!preservePeriodChangeFailure) clearPeriodChangeFailure()
                _state.update {
                    it.copy(
                        loading = false,
                        sourceZoneUnsupported = it.sourceZoneUnsupported || e.rawCode == SourceTimeZone.UNSUPPORTED,
                        periodChangeErrorPresentation = when {
                            failedSwitch -> e.presentation
                            preservePeriodChangeFailure -> it.periodChangeErrorPresentation
                            else -> null
                        },
                        recalculationPaused = it.recalculating,
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

    private fun updateChartState() = _state.update { it.copy(chartBlock = chartPages.block(),
        chartHasOlder = chartPages.cursor(true) != null, chartHasNewer = chartPages.cursor(false) != null,
        chartError = if (chartFailure == null) null else it.chartError) }

    override fun loadChartPage(older: Boolean, retry: Boolean) {
        val owner = requestOwner ?: return
        if (snapshots.freezesUnknownReads || _state.value.recalculating || chartJob?.isActive == true || !chartRetryGate.isReady() || (!retry && chartFailure != null)) return
        val cursor = if (retry) chartFailure?.first ?: return else chartPages.cursor(older) ?: return
        val direction = if (retry) chartFailure!!.second else older
        val context = chartPages.context ?: return
        val generation = ++chartGeneration
        _state.update { it.copy(chartLoading = true, chartError = null) }
        chartJob = viewModelScope.launch(owner) {
            try {
                val page = api.getMonthlyChartPage(cursor)
                if (generation != chartGeneration || chartPages.context != context) return@launch
                chartPages.append(page, cursor, direction)
                chartFailure = null
                chartRevisionRecoveryUsed = false
                chartRetryGate.clearOriginalRefusal()
                updateChartState()
            } catch (e: AppError) {
                if (generation != chartGeneration || chartPages.context != context) return@launch
                chartRetryGate.record(e)
                chartFailure = cursor to direction
                val expired = e is AppError.Api && e.details.any { it.field == "cursor" && it.code == "OUT_OF_RANGE" }
                if (expired && !chartRevisionRecoveryUsed) {
                    chartRevisionRecoveryUsed = true
                    chartPages.clear()
                    updateChartState()
                    startLoad(quiet = true, updateLimit = false, preservePeriodChangeFailure = true)
                } else _state.update { it.copy(chartError = e.presentation) }
            } finally {
                if (generation == chartGeneration) _state.update { it.copy(chartLoading = false) }
            }
        }
    }

    private suspend fun refreshLimit(reportOffline: Boolean = false) {
        val owner = requestOwner ?: return
        if (limitReadPending || !limitRetryGate.isReady()) return
        limitReadPending = true
        try {
            val limit = withContext(owner) { assistantApi.getLimit() }
            if (requestOwner !== owner) return
            _state.update { it.copy(limit = limit, limitErrorPresentation = null) }
        } catch (failure: AppError) {
            if (requestOwner !== owner) return
            limitRetryGate.record(failure)
            if (reportOffline || failure !is AppError.Offline) _state.update { it.copy(limitErrorPresentation = failure.presentation) }
        } finally {
            if (requestOwner === owner) limitReadPending = false
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
        if (type.isRolling) return PeriodKey(type.code)
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
        return PeriodKey.of(target, last.atZoneSameInstant(SourceTimeZone.require(shown.timeZone)).toLocalDate())
    }

    override fun goTo(key: PeriodKey) {
        _state.update { it.copy(sheet = null) }
        if (failedPeriodRequest == Triple(period, key, transferMode)) {
            retryPeriodChange()
            return
        }
        if (key == date) return
        clearRestriction()
        date = key
        tracker.track(Events.ANALYTICS_PERIOD_CHANGED, mapOf("period" to period.code))
        load()
    }

    override fun setTransferMode(mode: TransferMode) {
        _state.update { it.copy(sheet = null) }
        val target = allowedMode(mode)
        if (failedPeriodRequest == Triple(period, date, target)) {
            retryPeriodChange()
            return
        }
        if (target == transferMode) return
        clearRestriction()
        transferMode = target
        tracker.track(Events.ANALYTICS_TRANSFERS_CHANGED, mapOf("mode" to target.code))
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
        val owner = requestOwner ?: return
        if (!periodsRetryGate.isReady()) return
        invalidatePeriodsRequest()
        val generation = periodsGeneration
        val type = period
        val zone = reportZone.current
        _state.update { it.copy(periods = null, periodsFailed = false, periodsErrorPresentation = null, periodsRetryable = null) }
        periodsJob = viewModelScope.launch(owner) {
            try {
                val list = api.listPeriods(type)
                if (!periodsContextIsCurrent(generation, type, zone)) return@launch
                if (list.periods.isNotEmpty()) SourceTimeZone.require(list.timeZone)
                _state.update { it.copy(periods = list.periods, periodsTimeZone = list.timeZone) }
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
            ActionType.SELECT_ANALYTICS_PERIOD -> selectChartMonth(block, action)
            ActionType.UNKNOWN -> Unit
        }
    }

    /** A chart action changes the type and exact key in one request, with the current transfer mode. */
    private fun selectChartMonth(block: Block, action: BlockAction) {
        if (block.id != "monthly_chart" || block.type != ru.finassist.pf.core.api.model.BlockType.CHART || block.v != 1 || !block.status.hasData) return
        val target = AnalyticsPeriodTargets.of(action.period, action.date)?.takeIf { it.period == PeriodTypeCode.MONTH } ?: return
        val request = Triple(target.period, target.date, transferMode)
        if (request == Triple(period, date, transferMode)) return
        if (failedPeriodRequest == request) {
            retryPeriodChange()
            return
        }
        val changedType = period != target.period
        period = target.period
        date = target.date
        _state.update { it.copy(sheet = null) }
        clearRestriction(periodsChanged = changedType)
        tracker.track(Events.ANALYTICS_PERIOD_CHANGED, mapOf("period" to target.period.code))
        load()
    }

    /** Exposure (FIN-9): the block was drawn — once per block id and response. */
    override fun onBlockShown(block: Block) {
        val id = block.id ?: return
        val exp = shownExperiments ?: return
        if (!exposed.add(id)) return
        tracker.track(Events.ANALYTICS_BLOCK_EXPOSED, mapOf("block" to id, "exp" to exp))
    }

    /** Full server block replacement, serialized across payment IDs; reads cannot prove an unknown write. */
    fun dismissRegular(id: String) {
        val owner = requestOwner ?: return
        val mutex = regularMutations
        if (id in pendingRegularMutations) return
        val shown = _state.value.data?.takeIf { it.params?.period == PeriodTypeCode.MONTH }
        val context = shown?.params ?: mutationContexts[id] ?: return
        val signature = shown?.let { DismissalContext(context, it.timeZone, it.calculation?.calcVersion) }
        val gate = dismissalGates.getOrPut(id) { RequestRetryGate(elapsedClock) }
        if (signature != null && dismissalContexts[id]?.let(signature::differsFrom) == true) {
            // Only this explicit command changes the original-request scope. Endpoint waiting survives.
            gate.clearOriginalRefusal()
        }
        if (!gate.isReady() || !pendingRegularMutations.add(id)) return
        signature?.let { dismissalContexts[id] = it }
        mutationContexts[id] = context
        snapshots.unknownOutcome(id)
        updateUnknownMutationNotice()
        invalidateAnalyticsRead(forMutation = true)
        val job = viewModelScope.launch(owner) {
            try {
                mutex.withLock {
                    if (requestOwner !== owner) return@withLock
                    invalidateAnalyticsRead(forMutation = true)
                    val generation = loadGeneration
                    var receivedUpdate = false
                    try {
                        val update = try {
                            inFlightOwner = owner
                            api.dismissRegularPayment(id, context.date, context.transferMode)
                        } finally { if (inFlightOwner === owner) inFlightOwner = null }
                        if (requestOwner !== owner) return@withLock
                        receivedUpdate = true
                        AnalyticsIntegrity.requireValid(update)
                        if (update.params != context) throw AppError.Server(500)
                        val replaced = snapshots.apply(update)
                        snapshots.resolved(id)
                        unknownMutationFailures.remove(id)
                        mutationContexts.remove(id)
                        tracker.track(Events.ANALYTICS_REGULAR_DISMISSED)
                        if (_state.value.data?.params == context && replaced != null) {
                            exposed.removeAll(update.blocks.mapNotNull { it.id }.toSet())
                            _state.update { it.copy(data = replaced, recalculating = true, response = it.response + 1,
                                snackbar = "Убрали из регулярных платежей", errorPresentation = null) }
                        }
                        // The response is sufficient for this block. The existing read path observes completion
                        // of the rest of the calculation, retaining the new block below the version barrier.
                        reloadChangedData(clearSnackbar = false)
                    } catch (e: AppError) {
                        if (requestOwner !== owner) return@withLock
                        gate.record(e)
                        val unavailable = e.rawCode == "ANALYTICS_CONTEXT_UNAVAILABLE" && e.requestRetryable == false
                        val invalidDate = e is AppError.Api && e.httpStatus == 400 && e.rawCode == "VALIDATION_ERROR" &&
                            e.details.any { it.field == "date" && it.code == "OUT_OF_RANGE" }
                        val unknown = receivedUpdate || e is AppError.Offline || e is AppError.Server || e is AppError.Unknown ||
                            e is AppError.Api && e.httpStatus >= 500
                        if (!unknown && (id !in unknownMutationFailures || unavailable || invalidDate)) {
                            snapshots.resolved(id)
                            unknownMutationFailures.remove(id)
                            mutationContexts.remove(id)
                        }
                        if (unknown) {
                            unknownMutationFailures += id
                            if (generation == loadGeneration && readInterruptedByMutation) {
                                // A deferred selection did not load: keep controls and future reads on the shown snapshot.
                                readInterruptedByMutation = false
                                _state.value.data?.params?.let { shown ->
                                    val changedType = period != shown.period
                                    period = shown.period
                                    date = shown.date
                                    transferMode = allowedMode(shown.transferMode)
                                    if (changedType) resetPeriodsContext()
                                }
                            }
                        }
                        _state.update { it.copy(errorPresentation = e.presentation,
                            recalculating = it.recalculating || unknown,
                            recalculationPaused = it.recalculationPaused || unknown || (generation == loadGeneration && it.recalculating),
                            snackbar = if (e is AppError.Offline) "Не получилось убрать" else "Не получилось убрать. Попробуйте ещё раз") }
                        if (unavailable || invalidDate) {
                            // 422 means saved; a date OUT_OF_RANGE means not saved. Both retire the obsolete
                            // month and read the current default once, never retrying a PUT with old params.
                            period = PeriodTypeCode.MONTH
                            date = null
                            selectedDates.remove(PeriodTypeCode.MONTH)
                            _state.update { it.copy(snackbar = if (unavailable)
                                e.presentation.textOrFallback("Изменение сохранено, но данные для этого периода недоступны. Обновите аналитику")
                                else "Период больше недоступен. Изменение не сохранено") }
                            clearRestriction(periodsChanged = true)
                            startLoad(quiet = true, updateLimit = true)
                        } else if (generation == loadGeneration && readInterruptedByMutation && !unknown) {
                            startLoad(quiet = true, updateLimit = true, preservePeriodChangeFailure = true)
                        }
                    }
                }
            } finally {
                if (requestOwner === owner) {
                    pendingRegularMutations.remove(id)
                    updateUnknownMutationNotice()
                }
            }
        }
        mutationJobs += job
        job.invokeOnCompletion { mutationJobs.remove(job) }
    }

    override fun consumeSnackbar() = _state.update { it.copy(snackbar = null, errorPresentation = null) }

    private fun updateUnknownMutationNotice() {
        _state.update { it.copy(unknownMutation = unknownMutationFailures.isNotEmpty(),
            unknownReadRecoveryAvailable = unknownMutationFailures.isNotEmpty() && pendingRegularMutations.isEmpty() && snapshots.freezesUnknownReads,
            unknownReadRecoveryUsed = unknownMutationFailures.isNotEmpty() && !snapshots.freezesUnknownReads) }
    }

    /** The user can resume reading without claiming an unknown command succeeded or retrying a forbidden PUT. */
    override fun recoverUnknownMutationRead() {
        if (requestOwner == null || !_state.value.unknownReadRecoveryAvailable || !retryGate.isReady() || pendingRegularMutations.isNotEmpty()) return
        val hasSnapshot = _state.value.data != null
        if (!hasSnapshot) _state.update { it.copy(loading = true) }
        snapshots.allowReadsDespiteUnknownOutcomes()
        updateUnknownMutationNotice()
        invalidateAnalyticsRead()
        load(quiet = hasSnapshot)
    }

    private companion object {
        const val RECALC_POLL_MS = 3_000L
        const val MAX_IMPORT_POLLS = 10
        const val BLOCK_FLAG_PREFIX = "analytics.block."
        const val TILES_ID = "tiles"

        /** What this client draws (api.md 6.1 `supports`), from the renderer registry (FIN-7). */
        val supports: String = CatalogV1.registry().supports
    }
}
