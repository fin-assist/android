package ru.finassist.pf.feature.statements.impl.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.network.dto.ImportConfigDto
import ru.finassist.pf.core.network.dto.ImportResultDto
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flags
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.statements.impl.data.ImportEvent
import ru.finassist.pf.feature.statements.impl.data.PendingUploadStore
import ru.finassist.pf.feature.statements.impl.data.StatementsRepositoryImpl
import ru.finassist.pf.feature.statements.impl.domain.ImportFailure
import java.util.UUID
import javax.inject.Inject

/**
 * One screen, four phases (mockups ImportGuide → progress → ImportResult / ImportError). The phase lives in the
 * ViewModel so rotation and a short background trip keep the upload; the upload itself is resumed from
 * [PendingUploadStore] after process death.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: StatementsRepositoryImpl,
    private val pending: PendingUploadStore,
    private val flags: FeatureFlags,
    private val tracker: Tracker,
) : ViewModel() {

    sealed interface Phase {
        data object Guide : Phase
        data class Uploading(val fileName: String, val percent: Int = 0, val uploadId: String? = null, val askCancel: Boolean = false) : Phase
        data class Done(val result: ImportResultDto, val fileName: String, val uploadId: String) : Phase
        data class Failed(val failure: ImportFailure, val fileName: String) : Phase
    }

    data class UiState(
        val phase: Phase = Phase.Guide,
        val config: ImportConfigDto? = null,
        val configError: AppError? = null,
        /** Offline during the upload itself — «Нет связи с интернетом — файл не загружен» + «Повторить». */
        val uploadOffline: Boolean = false,
        val uploadEnabled: Boolean = true,
        /** «Прервать» failed (offline): parsing continues on the server. */
        val cancelError: Boolean = false,
        val showFallbackSteps: Boolean = false,
        val showPrivacy: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var lastFile: Pair<Uri, String>? = null
    private var progressJob: Job? = null
    private var uploadJob: Job? = null

    init {
        refreshFlags()
        loadConfig()
        resumePending()
    }

    fun refreshFlags() = _state.update { it.copy(uploadEnabled = flags.isEnabled(Flags.statementsUpload)) }

    fun loadConfig() = viewModelScope.launch {
        try {
            val c = repo.config()
            _state.update { it.copy(config = c, configError = null) }
        } catch (e: AppError) { _state.update { it.copy(configError = e) } }
    }

    /** Re-attach to an upload that was in flight when the process died. */
    private fun resumePending() = viewModelScope.launch {
        val p = pending.read() ?: return@launch
        // With an upload id the stream is re-attached; without one the POST never got its 202 and the key is kept
        // so that picking the same file again repeats the same action (api.md «Идемпотентность»).
        if (p.uploadId != null) {
            _state.update { it.copy(phase = Phase.Uploading(p.fileName, uploadId = p.uploadId)) }
            observe(p.uploadId, p.fileName)
        }
    }

    fun onFallbackSteps(show: Boolean) = _state.update { it.copy(showFallbackSteps = show) }
    fun onPrivacy(show: Boolean) = _state.update { it.copy(showPrivacy = show) }

    /** File chosen in the system picker. */
    fun onFilePicked(uri: Uri) {
        val (name, size) = queryNameAndSize(uri)
        lastFile = uri to name
        tracker.track("import.file_picked")
        viewModelScope.launch {
            val previous = pending.read()
            val key = previous?.takeIf { it.uploadId == null && it.fileName == name && it.fileSize == size }?.key ?: UUID.randomUUID().toString()
            upload(uri, name, size, key)
        }
    }

    /** «Повторить» after an offline failure of the POST: same file, same key — the server dedups the retry. */
    fun retryUpload() {
        val (uri, name) = lastFile ?: return
        viewModelScope.launch { val p = pending.read(); upload(uri, name, p?.fileSize ?: -1L, p?.key ?: UUID.randomUUID().toString()) }
    }

    private fun upload(uri: Uri, name: String, size: Long, key: String): Job = viewModelScope.launch {
        uploadJob = coroutineContext[Job]
        _state.update { it.copy(phase = Phase.Uploading(name), uploadOffline = false, cancelError = false) }
        pending.start(key, name, size)
        val max = _state.value.config?.maxFileSizeBytes ?: (10L * 1024 * 1024)
        val bytes = try {
            withContext(Dispatchers.IO) { readFile(uri, max) }
        } catch (e: FileTooLargeException) {
            pending.clear(); fail(ImportFailure.fromError(AppError.FileTooLarge, name), name); return@launch
        } catch (e: Exception) {
            pending.clear(); fail(ImportFailure("Не получилось открыть файл", "Выберите файл ещё раз — например, из папки «Загрузки»"), name); return@launch
        }
        try {
            val accepted = repo.upload(key, name, bytes)
            pending.accepted(accepted.uploadId)
            _state.update { it.copy(phase = Phase.Uploading(name, uploadId = accepted.uploadId)) }
            observe(accepted.uploadId, name)
        } catch (e: AppError) {
            when (e) {
                is AppError.Offline -> _state.update { it.copy(phase = Phase.Guide, uploadOffline = true) }
                is AppError.RequestInProgress -> { kotlinx.coroutines.delay((e.retryAfterSeconds ?: 2) * 1000L); retryUpload() }
                is AppError.IdempotencyConflict -> upload(uri, name, size, UUID.randomUUID().toString())
                else -> { pending.clear(); fail(ImportFailure.fromError(e, name), name) }
            }
        }
    }

    private fun observe(uploadId: String, name: String) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            try {
                repo.progress(uploadId).collect { event ->
                    when (event) {
                        is ImportEvent.Progress -> _state.update { s ->
                            val u = s.phase as? Phase.Uploading ?: return@update s
                            s.copy(phase = u.copy(percent = maxOf(u.percent, event.percent)))
                        }
                        is ImportEvent.Complete -> {
                            pending.clear()
                            tracker.track("import.completed", mapOf("first" to event.result.isFirstImport.toString(), "has_unread" to (event.result.unreadCount > 0).toString()))
                            _state.update { it.copy(phase = Phase.Done(event.result, name, uploadId)) }
                        }
                        is ImportEvent.Failed -> {
                            pending.clear()
                            if (event.code == "CANCELLED") _state.update { it.copy(phase = Phase.Guide) }
                            else { tracker.track("import.failed", mapOf("code" to event.code)); fail(ImportFailure.processing(), name) }
                        }
                    }
                }
            } catch (e: AppError) {
                pending.clear(); fail(ImportFailure.processing(), name)
            }
        }
    }

    fun onAskCancel(show: Boolean) = _state.update { s ->
        val u = s.phase as? Phase.Uploading ?: return@update s
        s.copy(phase = u.copy(askCancel = show))
    }

    /** «Прервать»: DELETE on the upload; the stream then ends with CANCELLED → back to the guide. */
    fun cancelUpload() = viewModelScope.launch {
        val u = _state.value.phase as? Phase.Uploading ?: return@launch
        val uploadId = u.uploadId
        if (uploadId == null) {
            // Nothing accepted yet: the POST is cancelled with its coroutine.
            uploadJob?.cancel(); progressJob?.cancel(); pending.clear(); _state.update { it.copy(phase = Phase.Guide) }; return@launch
        }
        try {
            repo.delete(uploadId)
            tracker.track("import.cancelled")
            // The stream ends with CANCELLED and returns the screen to the guide; close the dialog meanwhile.
            _state.update { it.copy(phase = u.copy(askCancel = false)) }
        } catch (e: AppError) {
            // Parsing may still be running on the server — say so instead of pretending it stopped.
            _state.update { it.copy(phase = u.copy(askCancel = false), cancelError = true) }
        }
    }

    /** «Выбрать другой файл» from the error screen. */
    fun backToGuide() = _state.update { it.copy(phase = Phase.Guide) }

    private fun fail(f: ImportFailure, name: String) = _state.update { it.copy(phase = Phase.Failed(f, name)) }

    private class FileTooLargeException : Exception()

    private fun readFile(uri: Uri, max: Long): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf); if (n < 0) break
                total += n
                if (total > max) throw FileTooLargeException()
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } ?: throw IllegalStateException("cannot open $uri")
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) return (c.getString(0) ?: "statement.ofx") to (if (c.isNull(1)) -1L else c.getLong(1))
            }
        }
        return (uri.lastPathSegment?.substringAfterLast('/') ?: "statement.ofx") to -1L
    }
}
