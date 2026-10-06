package ru.finassist.pf.feature.statements.impl.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.UploadFile
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.statements.impl.data.StatementsRepositoryImpl
import javax.inject.Inject

/** What the upload screen shows. */
sealed interface UploadPhase {
    data object Loading : UploadPhase

    /** Feature toggle `statements.upload` is off. */
    data object Disabled : UploadPhase

    /** Could not load the config: no network. */
    data object Offline : UploadPhase

    data class Guide(val config: ImportConfig) : UploadPhase

    /** File sent, server is parsing; [uploadId] is null until `202` arrives. */
    data class Busy(val fileName: String, val progress: Float, val uploadId: String?, val cancelling: Boolean = false) : UploadPhase

    /** Upload rejected or parsing failed; [code] is from api.md 3.2 / 3.3. */
    data class Error(val code: String, val fileName: String, val config: ImportConfig?) : UploadPhase
}

data class UploadUiState(
    val phase: UploadPhase = UploadPhase.Loading,
    /** «Не скачивается?» expanded. */
    val fallbackOpen: Boolean = false,
    val askCancel: Boolean = false,
    /** Finished import: the screen navigates to the result. */
    val doneUploadId: String? = null,
)

@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: StatementsApi,
    private val repository: StatementsRepositoryImpl,
    private val flags: FeatureFlags,
    private val tracker: Tracker,
) : ViewModel() {
    private val _state = MutableStateFlow(UploadUiState())
    val state: StateFlow<UploadUiState> = _state
    private var config: ImportConfig? = null
    private var pickedKey: Pair<Uri, IdempotencyKey>? = null
    private var progressJob: Job? = null

    init {
        load()
    }

    fun load() {
        if (!flags.isEnabled(Flag.STATEMENTS_UPLOAD)) {
            _state.update { it.copy(phase = UploadPhase.Disabled) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(phase = UploadPhase.Loading) }
            try {
                val c = api.getImportConfig()
                config = c
                _state.update { it.copy(phase = UploadPhase.Guide(c)) }
            } catch (e: AppError) {
                _state.update { it.copy(phase = if (e is AppError.Offline) UploadPhase.Offline else UploadPhase.Guide(fallbackConfig())) }
            }
        }
    }

    /** Config is only links and numbers; without it the guide still works with the defaults from api.md. */
    private fun fallbackConfig() = config ?: ImportConfig(
        downloadUrl = "https://www.tbank.ru/login/",
        loginUrl = "https://www.tbank.ru/login/",
        maxFileSizeBytes = 10L * 1024 * 1024,
        acceptedFormats = listOf("OFX"),
        suggestedPeriod = ru.finassist.pf.core.common.time.DateRange(
            java.time.OffsetDateTime.now().minusMonths(12), java.time.OffsetDateTime.now().plusDays(1),
        ),
    )

    fun toggleFallback() = _state.update { it.copy(fallbackOpen = !it.fallbackOpen) }

    fun onFilePicked(uri: Uri) {
        val (name, size) = describe(uri)
        // One logical action = one key; picking the same file again after a failure reuses it (api.md «Идемпотентность»).
        val key = pickedKey?.takeIf { it.first == uri }?.second ?: IdempotencyKey.random().also { pickedKey = uri to it }
        val max = config?.maxFileSizeBytes ?: (10L * 1024 * 1024)
        if (size > max) {
            _state.update { it.copy(phase = UploadPhase.Error(ErrorCodes.FILE_TOO_LARGE, name, config)) }
            return
        }
        tracker.track(Events.STATEMENTS_UPLOAD_STARTED)
        _state.update { it.copy(phase = UploadPhase.Busy(name, 0f, uploadId = null)) }
        viewModelScope.launch {
            try {
                val accepted = api.uploadStatement(
                    key,
                    UploadFile(name = name, size = size, open = { context.contentResolver.openInputStream(uri) ?: error("cannot open $uri") }),
                )
                _state.update { it.copy(phase = UploadPhase.Busy(accepted.fileName, 0.05f, accepted.uploadId)) }
                watch()
            } catch (e: AppError) {
                fail(e, name)
            }
        }
    }

    /** Opens (or re-opens on resume) the progress stream; the first event is the current state (api.md SSE). */
    fun watch() {
        val busy = _state.value.phase as? UploadPhase.Busy ?: return
        val uploadId = busy.uploadId ?: return
        if (progressJob?.isActive == true) return
        progressJob = viewModelScope.launch {
            try {
                api.streamProgress(uploadId).collect { event ->
                    when (event) {
                        is ImportEvent.Progress -> _state.update { s ->
                            val b = s.phase as? UploadPhase.Busy ?: return@update s
                            s.copy(phase = b.copy(progress = (event.progress.percent / 100f).coerceIn(0.05f, 1f)))
                        }
                        is ImportEvent.Complete -> {
                            repository.importCompleted(uploadId, event.result)
                            tracker.track(Events.STATEMENTS_UPLOAD_COMPLETED, mapOf("new_count" to event.result.newCount.toString()))
                            _state.update { it.copy(doneUploadId = uploadId, askCancel = false) }
                        }
                        is ImportEvent.Error -> {
                            if (event.code == ErrorCodes.CANCELLED) {
                                tracker.track(Events.STATEMENTS_UPLOAD_CANCELLED)
                                _state.update { it.copy(phase = UploadPhase.Guide(fallbackConfig()), askCancel = false) }
                            } else {
                                tracker.track(Events.STATEMENTS_UPLOAD_FAILED, mapOf("code" to event.code))
                                _state.update { it.copy(phase = UploadPhase.Error(ErrorCodes.PROCESSING_FAILED, busy.fileName, config), askCancel = false) }
                            }
                        }
                    }
                }
            } catch (e: AppError) {
                // Dropped stream: keep the busy state, the screen re-calls watch() on resume; other errors end the import.
                if (e !is AppError.Offline) fail(e, busy.fileName)
            }
        }
    }

    fun stopWatching() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun fail(e: AppError, fileName: String) {
        val code = when (e) {
            is AppError.Api -> e.code
            is AppError.Offline -> "OFFLINE"
            else -> ErrorCodes.PROCESSING_FAILED
        }
        tracker.track(Events.STATEMENTS_UPLOAD_FAILED, mapOf("code" to code))
        _state.update { it.copy(phase = UploadPhase.Error(code, fileName, config)) }
    }

    fun askCancel(show: Boolean) = _state.update { it.copy(askCancel = show) }

    /** «Прервать разбор»: DELETE of a processing upload; the stream then ends with `CANCELLED`. */
    fun cancelImport() {
        val busy = _state.value.phase as? UploadPhase.Busy ?: return
        val id = busy.uploadId
        if (id == null) {
            _state.update { it.copy(phase = UploadPhase.Guide(fallbackConfig()), askCancel = false) }
            return
        }
        _state.update { it.copy(phase = busy.copy(cancelling = true)) }
        viewModelScope.launch {
            runCatching { api.deleteStatement(id) }
            // If the server had already finished, the stream will report `complete` and the result screen opens.
        }
    }

    /** «Выбрать другой файл» after an error. */
    fun backToGuide() = _state.update { it.copy(phase = UploadPhase.Guide(fallbackConfig())) }

    fun consumeDone() = _state.update { it.copy(doneUploadId = null) }

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "statement.ofx"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        if (size < 0) size = runCatching { context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L }.getOrDefault(0L)
        return name to size
    }
}
