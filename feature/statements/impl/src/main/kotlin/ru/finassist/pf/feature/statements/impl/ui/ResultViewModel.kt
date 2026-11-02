package ru.finassist.pf.feature.statements.impl.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.firstOrNull
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.SourceTimeZone
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

data class ResultUiState(
    val result: ImportResult? = null,
    val error: ErrorPresentation? = null,
    val retrying: Boolean = false,
    val retryReady: Boolean = true,
    val sourceZoneUnsupported: Boolean = false,
)

@HiltViewModel
class ResultViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repository: StatementsRepository,
    flags: FeatureFlags,
    private val api: StatementsApi,
    elapsedClock: ElapsedClock,
) : ViewModel() {
    val uploadEnabled = flags.isEnabled(Flag.STATEMENTS_UPLOAD)
    val uploadId: String = savedState.toRoute<StatementsRoutes.Result>().uploadId
    private val _state = MutableStateFlow(ResultUiState())
    val state: StateFlow<ResultUiState> = _state
    private val retryGate = RequestRetryGate(elapsedClock)
    init {
        repository.lastResult(uploadId)?.let(::accept)
        viewModelScope.launch { retryGate.readiness.collect { _state.value = _state.value.copy(retryReady = it) } }
    }

    private fun accept(result: ImportResult) {
        try { _state.value = ResultUiState(result.inSourceZone()) }
        catch (e: AppError) { failed(e) }
    }

    private fun failed(e: AppError) {
        val old = _state.value
        _state.value = old.copy(error = if (old.sourceZoneUnsupported) old.error else e.presentation, retrying = false,
            sourceZoneUnsupported = old.sourceZoneUnsupported || (e is AppError.Api && e.code == SourceTimeZone.UNSUPPORTED))
    }

    /** Re-open only on explicit action; an unknown local zone never starts an automatic loop. */
    fun retry() {
        if (_state.value.retrying || !retryGate.isReady()) return
        _state.value = _state.value.copy(retrying = true)
        viewModelScope.launch {
            try {
                val event = withTimeoutOrNull(30_000) { api.streamProgress(uploadId).firstOrNull { it !is ImportEvent.Progress } }
                    ?: throw AppError.Offline()
                when (event) {
                    is ImportEvent.Complete -> accept(event.result)
                    is ImportEvent.Error -> _state.value = _state.value.copy(error = if (_state.value.sourceZoneUnsupported) _state.value.error else event.presentation, retrying = false)
                    is ImportEvent.Progress -> Unit
                }
            } catch (e: AppError) { retryGate.record(e); failed(e) }
        }
    }
}
