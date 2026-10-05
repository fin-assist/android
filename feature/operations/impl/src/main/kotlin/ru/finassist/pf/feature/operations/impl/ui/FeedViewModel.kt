package ru.finassist.pf.feature.operations.impl.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flags
import ru.finassist.pf.feature.operations.impl.data.OperationsRepository
import ru.finassist.pf.feature.operations.impl.domain.FeedPage
import ru.finassist.pf.feature.operations.impl.domain.Operation
import ru.finassist.pf.feature.statements.api.StatementsRepository
import javax.inject.Inject

/** «Операции»: 3-month pages, month summary, stale notice, empty state. */
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repo: OperationsRepository,
    private val statements: StatementsRepository,
    private val flags: FeatureFlags,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val error: AppError? = null,
        val items: List<Operation> = emptyList(),
        val page: FeedPage? = null,
        val loadingMore: Boolean = false,
        val uploadEnabled: Boolean = true,
    ) {
        val isEmpty: Boolean get() = !loading && error == null && items.isEmpty() && page?.nextBefore == null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        load()
        viewModelScope.launch { statements.dataChanged.collect { load() } }
        viewModelScope.launch { repo.changed.collect { load() } }
    }

    fun refreshFlags() = _state.update { it.copy(uploadEnabled = flags.isEnabled(Flags.statementsUpload)) }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = it.items.isEmpty(), error = null) }
        try {
            val page = repo.feed()
            _state.update { it.copy(loading = false, items = page.items, page = page) }
        } catch (e: AppError) {
            _state.update { it.copy(loading = false, error = e) }
        }
    }

    fun loadMore() {
        val s = _state.value
        val before = s.page?.nextBefore ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = repo.feed(before)
                _state.update { it.copy(loadingMore = false, items = it.items + page.items, page = page.copy(summary = it.page?.summary.orEmpty(), stale = it.page?.stale ?: false, lastOperationAt = it.page?.lastOperationAt, lastUploadedAt = it.page?.lastUploadedAt)) }
            } catch (e: AppError) {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }
}
