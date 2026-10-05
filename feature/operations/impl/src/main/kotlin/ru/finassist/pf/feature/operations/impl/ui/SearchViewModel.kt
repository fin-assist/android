package ru.finassist.pf.feature.operations.impl.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.navigation.OperationsFilter
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flags
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.operations.api.CategoryInfo
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import ru.finassist.pf.feature.operations.impl.data.OperationsRepository
import ru.finassist.pf.feature.operations.impl.domain.SearchResult
import javax.inject.Inject

/** Search (api.md 4.1): whole selection in one response, 12-month default period, filters as chips. */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: OperationsRepository,
    flags: FeatureFlags,
    private val tracker: Tracker,
) : ViewModel() {

    enum class Sheet { None, Period, Category, Amount }
    enum class PeriodChoice { Last12, AllTime, Custom }

    data class UiState(
        val filter: OperationsFilter,
        val allTime: Boolean = false,
        val periodChoice: PeriodChoice = PeriodChoice.Last12,
        val result: SearchResult? = null,
        val loading: Boolean = false,
        val error: AppError? = null,
        val sheet: Sheet = Sheet.None,
        val categories: List<CategoryInfo> = emptyList(),
        val showPeriod: Boolean = true,
        val showCategory: Boolean = true,
        val showAmount: Boolean = true,
        val showKind: Boolean = true,
        /** Preset filters from analytics / assistant that have no chip of their own, shown as one chip. */
        val presetName: String? = null,
        /** The search started with any filter or query: results mode vs initial. */
        val started: Boolean = false,
    ) {
        val hasFilters: Boolean get() = filter.categoryId != null || filter.amountFrom != null || filter.amountTo != null || filter.kind != null || filter.selection != null || filter.transferMode != null || periodChoice != PeriodChoice.Last12
    }

    private val initial = savedState.toRoute<OperationsRoutes.Search>().filter
    private val _state = MutableStateFlow(
        UiState(
            filter = initial,
            periodChoice = if (initial.from != null || initial.to != null) PeriodChoice.Custom else PeriodChoice.Last12,
            showPeriod = flags.isEnabled(Flags.searchFilterPeriod),
            showCategory = flags.isEnabled(Flags.searchFilterCategory),
            showAmount = flags.isEnabled(Flags.searchFilterAmount),
            showKind = flags.isEnabled(Flags.searchFilterKind),
            presetName = initial.selectionName ?: if (initial.transferMode != null || initial.selection != null) "Как на «Аналитике»" else null,
            started = !initial.isEmpty,
        ),
    )
    val state: StateFlow<UiState> = _state
    private var job: Job? = null

    init {
        viewModelScope.launch { _state.update { it.copy(categories = runCatching { repo.categoriesOrLoad() }.getOrDefault(emptyList())) } }
        // Query typing is debounced; filter changes search immediately.
        viewModelScope.launch {
            _state.map { it.filter.q }.distinctUntilChanged().drop(1).debounce(300).collect { search() }
        }
        if (!initial.isEmpty) search()
    }

    fun onQuery(q: String) = _state.update { it.copy(filter = it.filter.copy(q = q), started = true) }

    fun search() {
        val s = _state.value
        if (!s.started) return
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val r = repo.search(s.filter.copy(q = s.filter.q?.takeIf { it.isNotBlank() }), allTime = s.allTime)
                _state.update { it.copy(loading = false, result = r) }
                tracker.track("search.performed", mapOf("count" to r.totalCount.toString()))
            } catch (e: AppError) {
                _state.update { it.copy(loading = false, error = e) }
            }
        }
    }

    fun onSheet(sheet: Sheet) = _state.update { it.copy(sheet = sheet) }

    fun onPeriod(choice: PeriodChoice, from: String? = null, to: String? = null) {
        _state.update {
            it.copy(
                sheet = Sheet.None, periodChoice = choice, allTime = choice == PeriodChoice.AllTime, started = true,
                filter = it.filter.copy(from = if (choice == PeriodChoice.Custom) from else null, to = if (choice == PeriodChoice.Custom) to else null),
            )
        }
        search()
    }

    fun onAllTime() = onPeriod(PeriodChoice.AllTime)

    fun onCategory(id: String?) { _state.update { it.copy(sheet = Sheet.None, filter = it.filter.copy(categoryId = id), started = true) }; search() }

    fun onAmount(from: Long?, to: Long?) { _state.update { it.copy(sheet = Sheet.None, filter = it.filter.copy(amountFrom = from, amountTo = to), started = true) }; search() }

    fun onToggleExpensesOnly() {
        _state.update { it.copy(filter = it.filter.copy(kind = if (it.filter.kind == "expense") null else "expense"), started = true) }
        search()
    }

    fun onReset() {
        _state.update { it.copy(filter = OperationsFilter(q = it.filter.q), allTime = false, periodChoice = PeriodChoice.Last12, presetName = null, started = !it.filter.q.isNullOrBlank(), result = if (it.filter.q.isNullOrBlank()) null else it.result) }
        search()
    }
}
