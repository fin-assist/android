package ru.finassist.pf.feature.statements.impl.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.RussianDates.plural
import ru.finassist.pf.core.network.codes.UploadStatus
import ru.finassist.pf.core.network.dto.UploadDto
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flags
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.statements.impl.data.StatementsRepositoryImpl
import ru.finassist.pf.feature.statements.impl.domain.dayRangeWithYear
import ru.finassist.pf.feature.statements.impl.domain.uploadedText
import javax.inject.Inject

@HiltViewModel
class UploadHistoryViewModel @Inject constructor(
    private val repo: StatementsRepositoryImpl,
    private val flags: FeatureFlags,
    private val tracker: Tracker,
) : ViewModel() {

    data class Row(val id: String, val fileName: String, val period: String, val uploaded: String, val processing: Boolean, val unreadCount: Int)

    data class UiState(
        val loading: Boolean = true,
        val rows: List<Row> = emptyList(),
        val error: AppError? = null,
        val deleteCandidate: Row? = null,
        val deleting: Boolean = false,
        val deleteError: AppError? = null,
        /** «Загрузка удалена, аналитика пересчитана» — shown for a few seconds. */
        val deletedNotice: Boolean = false,
        val uploadEnabled: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init { refreshFlags(); load() }

    fun refreshFlags() = _state.update { it.copy(uploadEnabled = flags.isEnabled(Flags.statementsUpload)) }

    fun load(): Job = viewModelScope.launch {
        _state.update { it.copy(loading = it.rows.isEmpty()) }
        try {
            val list = repo.list()
            val rows = list.uploads.map(::row)
            _state.update { it.copy(loading = false, rows = rows, error = null) }
            // A row still parsing (api.md 3.4 `processing`): poll until it is done or gone.
            if (rows.any { it.processing }) { delay(3000); load() }
        } catch (e: AppError) { _state.update { it.copy(loading = false, error = e) } }
    }

    fun askDelete(row: Row?) = _state.update { it.copy(deleteCandidate = row, deleteError = null) }

    fun confirmDelete() = viewModelScope.launch {
        val row = _state.value.deleteCandidate ?: return@launch
        _state.update { it.copy(deleting = true, deleteError = null) }
        try {
            repo.delete(row.id)
            tracker.track("statements.upload.deleted")
            _state.update { it.copy(deleting = false, deleteCandidate = null, rows = it.rows.filter { r -> r.id != row.id }, deletedNotice = true) }
            load()
            delay(4000)
            _state.update { it.copy(deletedNotice = false) }
        } catch (e: AppError) {
            // 404 = already gone (deleted elsewhere): treat as success.
            if (e is AppError.NotFound) { _state.update { it.copy(deleting = false, deleteCandidate = null) }; load() }
            else _state.update { it.copy(deleting = false, deleteError = e) }
        }
    }

    private fun row(u: UploadDto): Row {
        val processing = UploadStatus.fromWire(u.status) == UploadStatus.Processing
        val period = if (processing) "Разбираем…" else listOfNotNull(
            dayRangeWithYear(u.firstOperationAt, u.lastOperationAt, withYear = false),
            plural(u.operationCount.toLong(), "операция", "операции", "операций"),
        ).joinToString(" · ")
        return Row(u.uploadId, u.fileName, period, uploadedText(u.uploadedAt), processing, u.unreadCount)
    }
}
