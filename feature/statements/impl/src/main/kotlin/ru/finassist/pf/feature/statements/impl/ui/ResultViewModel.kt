package ru.finassist.pf.feature.statements.impl.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.feature.statements.api.StatementsRepository
import ru.finassist.pf.feature.statements.api.StatementsRoutes
import ru.finassist.pf.feature.statements.impl.domain.inSourceZone

data class ResultUiState(val result: ImportResult? = null, val error: ErrorPresentation? = null, val retrying: Boolean = false)

@HiltViewModel
class ResultViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repository: StatementsRepository,
    flags: FeatureFlags,
    private val api: StatementsApi,
) : ViewModel() {
    val uploadEnabled = flags.isEnabled(Flag.STATEMENTS_UPLOAD)
    val uploadId: String = savedState.toRoute<StatementsRoutes.Result>().uploadId
    private val _state = MutableStateFlow(ResultUiState())
    val state: StateFlow<ResultUiState> = _state
    init { repository.lastResult(uploadId)?.let(::accept) }

    private fun accept(result: ImportResult) {
        try { _state.value = ResultUiState(result.inSourceZone()) }
        catch (e: AppError) { _state.value = _state.value.copy(error = e.presentation, retrying = false) }
    }

    /** Re-open only on explicit action; an unknown local zone never starts an automatic loop. */
    fun retry() {
        if (_state.value.retrying) return
        _state.value = _state.value.copy(retrying = true)
        viewModelScope.launch {
            try {
                when (val event = api.streamProgress(uploadId).first { it !is ImportEvent.Progress }) {
                    is ImportEvent.Complete -> accept(event.result)
                    is ImportEvent.Error -> _state.value = _state.value.copy(error = event.presentation, retrying = false)
                    is ImportEvent.Progress -> Unit
                }
            } catch (e: AppError) { _state.value = _state.value.copy(error = e.presentation, retrying = false) }
        }
    }
}
