package ru.finassist.pf.feature.operations.impl.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import ru.finassist.pf.core.navigation.PfNavTypes
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.operations.api.CategoriesRepository
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import ru.finassist.pf.feature.operations.impl.data.OperationsRepositoryImpl
import java.time.OffsetDateTime
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
) {
    val hasFilters: Boolean get() = !filter.isEmpty || allTime
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
    operations: OperationsRepositoryImpl,
    flags: FeatureFlags,
    private val tracker: Tracker,
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
    val state: StateFlow<SearchUiState> = _state
    private var searchJob: Job? = null

    init {
        if (!preset.isEmpty) search(debounce = false)
        viewModelScope.launch { runCatching { categoriesRepository.categories() }.onSuccess { c -> _state.update { it.copy(categories = c) } } }
        // A category changed in the detail screen: refresh the visible result so the row moves out/in.
        viewModelScope.launch { operations.categoryChanges.collect { if (_state.value.hasFilters) search(debounce = false) } }
    }

    fun setQuery(q: String) = updateFilter(debounce = true) { it.copy(q = q.ifEmpty { null }) }

    fun setPeriod(from: OffsetDateTime?, to: OffsetDateTime?, allTime: Boolean) {
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

    /** «Только расходы» toggles `kind=expense`; a preset `income` is cleared by the same chip. */
    fun toggleKind() = updateFilter { it.copy(kind = if (it.kind == null) OperationKindFilter.EXPENSE else null) }

    /** Removes the «Как на „Аналитике“» chip: drops `transfer_mode` and `selection`. */
    fun clearAnalyticsScope() = updateFilter { it.copy(transferMode = null, selection = null, selectionName = null) }

    fun searchAllTime() {
        _state.update { it.copy(allTime = true) }
        updateFilter { it.copy(from = null, to = null) }
    }

    fun reset() {
        _state.update { it.copy(allTime = false, selectionDropped = false) }
        updateFilter { OperationsFilter(q = it.q) }
    }

    fun openSheet(sheet: SearchSheet?) = _state.update { it.copy(sheet = sheet) }

    fun retry() = search(debounce = false)

    private fun updateFilter(debounce: Boolean = false, change: (OperationsFilter) -> OperationsFilter) {
        _state.update { it.copy(filter = change(it.filter)) }
        search(debounce)
    }

    private fun search(debounce: Boolean) {
        searchJob?.cancel()
        val s = _state.value
        if (!s.hasFilters) {
            _state.update { it.copy(result = SearchResult.Initial) }
            return
        }
        searchJob = viewModelScope.launch {
            if (debounce) delay(350)
            _state.update { it.copy(result = SearchResult.Loading) }
            val filter = _state.value.filter
            try {
                val list = api.listOperations(OperationsQuery(filter = filter, allTime = _state.value.allTime))
                tracker.track(Events.OPERATIONS_SEARCH_SUBMITTED, mapOf("count" to (list.totalCount ?: list.items.size).toString()))
                _state.update {
                    it.copy(
                        result = SearchResult.Found(
                            items = list.items,
                            totalCount = list.totalCount ?: list.items.size,
                            range = list.range,
                            hasOlderData = list.hasOlderData == true,
                        ),
                    )
                }
            } catch (e: AppError) {
                when {
                    e is AppError.Api && e.code == ErrorCodes.TOO_MANY_RESULTS -> _state.update { it.copy(result = SearchResult.TooMany) }
                    e is AppError.Api && e.code == ErrorCodes.SELECTION_NOT_FOUND -> {
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
