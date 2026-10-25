package ru.finassist.pf.feature.operations.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.OperationsApi
import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.OperationItem
import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.ReportZone
import ru.finassist.pf.core.navigation.PfNavTypes
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.operations.api.CategoriesRepository
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import ru.finassist.pf.feature.operations.api.OperationsRepository
import ru.finassist.pf.feature.operations.api.OperationRecords
import java.time.OffsetDateTime
import ru.finassist.pf.feature.operations.impl.domain.OperationFormat
import java.time.LocalDate
import javax.inject.Inject

/** Which filter chips are available (feature toggles `search.filter.*`). */
data class SearchChips(val period: Boolean, val category: Boolean, val amount: Boolean, val kind: Boolean)

sealed interface SearchResult {
    /** Nothing entered yet: hint, no request. */
    data object Initial : SearchResult
    data object Loading : SearchResult
    data class Found(
        val items: List<OperationItem>,
        val totalCount: Int,
        val range: DateRange?,
        val hasOlderData: Boolean,
    ) : SearchResult
    data object TooMany : SearchResult
    data object Offline : SearchResult
    data object Failed : SearchResult
}

/** Open filter sheet. */
enum class SearchSheet { PERIOD, CATEGORY, AMOUNT }

data class SearchUiState(
    val filter: OperationsFilter = OperationsFilter(),
    val allTime: Boolean = false,
    val result: SearchResult = SearchResult.Initial,
    val chips: SearchChips,
    val sheet: SearchSheet? = null,
    val categories: List<Category> = emptyList(),
    /** The preset `selection` from analytics no longer exists: shown once above the results. */
    val selectionDropped: Boolean = false,
    /** First day of the default 12-month window, from the last search made with it (period sheet: «С октября 2025»). */
    val windowFrom: LocalDate? = null,
    val errorPresentation: ErrorPresentation? = null,
    val requestRetryable: Boolean? = null,
    val retryReady: Boolean = true,
) {
    val hasFilters: Boolean get() = !filter.isEmpty || allTime
    /** Nothing to reset besides the text: «Сбросить фильтры» on the empty result then clears the query instead of repeating it. */
    val onlyQuery: Boolean get() = filter.copy(q = null).isEmpty && !allTime
}

/**
 * Search (api.md 4.1, search mode): whole result in one response, no paging. Without a period the server
 * searches the last 12 months; «Искать за всё время» repeats the query with `all_time`.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val api: OperationsApi,
    private val categoriesRepository: CategoriesRepository,
    operations: OperationsRepository,
    records: OperationRecords,
    flags: FeatureFlags,
    private val tracker: Tracker,
    reportZone: ReportZone,
    private val elapsedClock: ElapsedClock,
) : ViewModel() {
    private val preset = savedState.toRoute<OperationsRoutes.Search>(PfNavTypes.MAP).filter

    private val _state = MutableStateFlow(
        SearchUiState(
            filter = preset,
            chips = SearchChips(
                period = flags.isEnabled(Flag.SEARCH_FILTER_PERIOD),
                category = flags.isEnabled(Flag.SEARCH_FILTER_CATEGORY),
                amount = flags.isEnabled(Flag.SEARCH_FILTER_AMOUNT),
                kind = flags.isEnabled(Flag.SEARCH_FILTER_KIND),
            ),
        ),
    )
    private val retryGate = RequestRetryGate(elapsedClock)
    init { viewModelScope.launch { retryGate.readiness.collect { ready -> _state.update { it.copy(retryReady = ready) } } } }
    val state: StateFlow<SearchUiState> = _state
    private var searchJob: Job? = null

    init {
        if (!preset.isEmpty) search(debounce = false)
        viewModelScope.launch { runCatching { categoriesRepository.categories() }.onSuccess { c -> _state.update { it.copy(categories = c) } } }
        // A category changed in the detail screen: refresh the visible result so the row moves out/in.
        viewModelScope.launch { operations.categoryChanges.collect { if (_state.value.hasFilters) search(debounce = false) } }
        viewModelScope.launch {
            records.records.collect { updated ->
                val old = _state.value.result as? SearchResult.Found
                val timeChanged = old?.items?.any { row -> updated.any { it.id == row.id && it.occurredAt != row.occurredAt } } == true
                _state.update { state ->
                    val found = state.result as? SearchResult.Found
                    if (found == null) state else state.copy(result = found.copy(
                        items = found.items.map { row -> updated.firstOrNull { it.id == row.id }?.toItem() ?: row },
                    ))
                }
                if (timeChanged) search(debounce = false, quiet = true)
            }
        }
        // The 12-month window without a period is counted in the profile zone: a zone that changes the effective one
        // (the device's until the profile is known) asks again, quietly — the shown result stays until the new one (FIN-92).
        // An explicit period or «за всё время» does not depend on the zone.
        viewModelScope.launch {
            reportZone.profile.map { reportZone.current }.distinctUntilChanged().drop(1).collect {
                val s = _state.value
                if (s.hasFilters && !s.allTime && s.filter.from == null && s.filter.to == null) search(debounce = false, quiet = true)
            }
        }
    }

    fun setQuery(q: String) = updateFilter(debounce = true) { it.copy(q = q.ifEmpty { null }) }

    fun setPeriod(from: OffsetDateTime?, to: OffsetDateTime?, allTime: Boolean) {
        if (allTime != _state.value.allTime) retryGate.clear()
        _state.update { it.copy(allTime = allTime && from == null && to == null, sheet = null) }
        updateFilter { it.copy(from = from, to = to) }
    }

    fun setCategory(categoryId: String?) {
        _state.update { it.copy(sheet = null) }
        updateFilter { it.copy(categoryId = categoryId) }
    }

    fun setAmount(from: Money?, to: Money?) {
        _state.update { it.copy(sheet = null) }
        updateFilter { it.copy(amountFrom = from, amountTo = to) }
    }

    /**
     * «Только расходы» toggles `kind=expense`; a preset `income` is cleared by the same chip. A kind this client does not
     * know belongs to the «Как на „Аналитике“» chip: this one replaces it with `expense`.
     */
    fun toggleKind() = updateFilter { it.copy(kind = if (it.kind?.isKnown == true) null else OperationKindFilter.EXPENSE) }

    /** Removes the «Как на „Аналитике“» chip: drops `transfer_mode`, `selection` and a `kind` this client does not know. */
    fun clearAnalyticsScope() = updateFilter {
        it.copy(transferMode = null, selection = null, selectionName = null, kind = it.kind?.takeIf { k -> k.isKnown })
    }

    fun searchAllTime() {
        retryGate.clear()
        _state.update { it.copy(allTime = true) }
        updateFilter { it.copy(from = null, to = null) }
    }

    /** Drops the filters and keeps the query — unless the query is the only filter: then it is the one to drop. */
    fun reset() {
        val onlyQuery = _state.value.onlyQuery
        _state.update { it.copy(allTime = false, selectionDropped = false) }
        updateFilter { if (onlyQuery) OperationsFilter() else OperationsFilter(q = it.q) }
    }

    fun openSheet(sheet: SearchSheet?) = _state.update { it.copy(sheet = sheet) }

    fun retry() { if (retryGate.isReady()) search(debounce = false) }

    private fun updateFilter(debounce: Boolean = false, change: (OperationsFilter) -> OperationsFilter) {
        val changed = change(_state.value.filter)
        if (changed != _state.value.filter) retryGate.clear()
        _state.update { it.copy(filter = changed) }
        search(debounce)
    }

    /**
     * [quiet] — a refresh of the same filter: a shown result stays on screen until the new one, and stays on a failure.
     * While a search for a changed filter is still pending, the shown result is not that filter's: the refresh is an
     * ordinary search then.
     */
    private fun search(debounce: Boolean, quiet: Boolean = false) {
        if (!retryGate.isReady()) return
        val refresh = quiet && searchJob?.isActive != true
        searchJob?.cancel()
        val s = _state.value
        if (!s.hasFilters) {
            _state.update { it.copy(result = SearchResult.Initial) }
            return
        }
        searchJob = viewModelScope.launch {
            if (debounce) delay(350)
            val keep = refresh && _state.value.result is SearchResult.Found
            _state.update { it.copy(errorPresentation = null, requestRetryable = null) }
            if (!keep) _state.update { it.copy(result = SearchResult.Loading) }
            val filter = _state.value.filter
            try {
                val allTime = _state.value.allTime
                val list = api.listOperations(OperationsQuery(filter = filter, allTime = allTime))
                tracker.track(Events.OPERATIONS_SEARCH_SUBMITTED, mapOf("count" to (list.totalCount ?: list.items.size).toString()))
                val window = OperationFormat.defaultWindowStart(list.range, filter, allTime)
                _state.update {
                    it.copy(
                        windowFrom = window ?: it.windowFrom,
                        result = SearchResult.Found(
                            items = list.items,
                            totalCount = list.totalCount ?: list.items.size,
                            range = list.range,
                            hasOlderData = list.hasOlderData == true,
                        ),
                    )
                }
            } catch (e: AppError) {
                retryGate.record(e)
                _state.update { it.copy(errorPresentation = e.presentation.takeUnless { e is AppError.Offline }, requestRetryable = e.requestRetryable) }
                when {
                    keep -> Unit
                    e is AppError.Api && e.response?.context != ErrorContext.RESULT_EVENT && e.code == ErrorCodes.TOO_MANY_RESULTS -> _state.update { it.copy(result = SearchResult.TooMany) }
                    e is AppError.Api && e.response?.context != ErrorContext.RESULT_EVENT && e.code == ErrorCodes.SELECTION_NOT_FOUND -> {
                        // api.md: open the search without the stale selection.
                        _state.update { it.copy(filter = it.filter.copy(selection = null, selectionName = null), selectionDropped = true) }
                        search(debounce = false)
                    }
                    e is AppError.Offline -> _state.update { it.copy(result = SearchResult.Offline) }
                    else -> _state.update { it.copy(result = SearchResult.Failed) }
                }
            }
        }
    }
}
