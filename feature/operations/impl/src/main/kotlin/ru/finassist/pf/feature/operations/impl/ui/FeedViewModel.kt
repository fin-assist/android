package ru.finassist.pf.feature.operations.impl.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.OperationsApi
import ru.finassist.pf.core.api.model.FeedState
import ru.finassist.pf.core.api.model.MonthSummary
import ru.finassist.pf.core.api.model.OperationItem
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.ApiDateTime
import ru.finassist.pf.core.common.time.ReportZone
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.operations.impl.data.OperationsRepositoryImpl
import ru.finassist.pf.feature.statements.api.StatementsRepository
import javax.inject.Inject

data class FeedUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val offline: Boolean = false,
    /** Error while loading the next portion: keep the list, show a retry row. */
    val moreFailed: Boolean = false,
    val items: List<OperationItem> = emptyList(),
    val summary: List<MonthSummary> = emptyList(),
    val state: FeedState? = null,
    val nextBefore: ApiDateTime? = null,
    val uploadEnabled: Boolean = true,
) {
    val isEmpty: Boolean get() = !loading && !offline && items.isEmpty() && nextBefore == null
}

/**
 * Feed «Операции» (api.md 4.1, feed mode): three-month portions, newest first; the next portion by
 * `next_before`. Reloads from the top when a statement is imported/deleted, a category changes or the profile zone
 * changes — portions, month totals and `next_before` are counted in it on the server (FIN-92).
 */
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val api: OperationsApi,
    statements: StatementsRepository,
    operations: OperationsRepositoryImpl,
    private val flags: FeatureFlags,
    private val tracker: Tracker,
    reportZone: ReportZone,
) : ViewModel() {
    private val _state = MutableStateFlow(FeedUiState(uploadEnabled = flags.isEnabled(Flag.STATEMENTS_UPLOAD)))
    val state: StateFlow<FeedUiState> = _state
    private var generation = 0L
    private var firstPagePending = false
    // Keep the near-end demand across refresh: the UI may not emit it again for the same cursor.
    private var pendingLoadMore = false

    init {
        tracker.track(Events.OPERATIONS_FEED_OPENED)
        load()
        viewModelScope.launch {
            merge(statements.events, operations.categoryChanges).collect { refresh() }
        }
        viewModelScope.launch {
            operations.records.collect { updated ->
                val timeChanged = _state.value.items.any { row -> updated.any { it.id == row.id && it.occurredAt != row.occurredAt } }
                _state.update { state ->
                    state.copy(items = state.items.map { row -> updated.firstOrNull { it.id == row.id }?.toItem() ?: row })
                }
                if (timeChanged) refresh()
            }
        }
        // The first known zone is not a change: the first portion was asked for already.
        viewModelScope.launch { reportZone.profile.drop(1).filterNotNull().distinctUntilChanged().collect { refresh() } }
        viewModelScope.launch { flags.changes.collect { _state.update { it.copy(uploadEnabled = flags.isEnabled(Flag.STATEMENTS_UPLOAD)) } } }
    }

    fun load() {
        val requestGeneration = ++generation
        pendingLoadMore = pendingLoadMore || _state.value.loadingMore
        firstPagePending = true
        viewModelScope.launch {
            if (requestGeneration != generation) return@launch
            _state.update { it.copy(loading = it.items.isEmpty(), offline = false, loadingMore = false) }
            fetchFirst(requestGeneration)
        }
    }

    fun refresh() {
        val requestGeneration = ++generation
        pendingLoadMore = pendingLoadMore || _state.value.loadingMore
        firstPagePending = true
        viewModelScope.launch {
            if (requestGeneration != generation) return@launch
            _state.update { it.copy(refreshing = true, loadingMore = false) }
            fetchFirst(requestGeneration)
        }
    }

    private suspend fun fetchFirst(requestGeneration: Long) {
        try {
            val page = api.listOperations(OperationsQuery())
            if (requestGeneration != generation) return
            _state.update {
                it.copy(
                    loading = false, refreshing = false, offline = false, moreFailed = false,
                    items = page.items, summary = page.summary.orEmpty(), state = page.state, nextBefore = page.nextBefore,
                )
            }
        } catch (e: AppError) {
            if (requestGeneration != generation) return
            _state.update { it.copy(loading = false, refreshing = false, offline = it.items.isEmpty()) }
        }
        firstPagePending = false
        if (pendingLoadMore) {
            pendingLoadMore = false
            loadMore()
        }
    }

    /** Called when the list scrolls near its end. */
    fun loadMore() {
        if (firstPagePending) {
            pendingLoadMore = true
            return
        }
        val s = _state.value
        val before = s.nextBefore ?: return
        if (s.loadingMore || s.loading || s.refreshing || pendingLoadMore) return
        pendingLoadMore = true
        val requestGeneration = generation
        viewModelScope.launch {
            if (requestGeneration != generation) return@launch
            _state.update { it.copy(loadingMore = true, moreFailed = false) }
            try {
                val page = api.listOperations(OperationsQuery(before = before))
                if (requestGeneration != generation) return@launch
                pendingLoadMore = false
                _state.update {
                    it.copy(
                        loadingMore = false,
                        items = it.items + page.items.filter { new -> it.items.none { old -> old.id == new.id } },
                        summary = it.summary + page.summary.orEmpty(),
                        nextBefore = page.nextBefore,
                    )
                }
            } catch (e: AppError) {
                if (requestGeneration != generation) return@launch
                pendingLoadMore = false
                _state.update { it.copy(loadingMore = false, moreFailed = true) }
            }
        }
    }
}
