package ru.finassist.pf.feature.statements.impl.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import ru.finassist.pf.core.storage.IdempotencyKeys
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.statements.impl.data.StatementsRepositoryImpl
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
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
    private val keys: IdempotencyKeys,
) : ViewModel() {
    private val _state = MutableStateFlow(UploadUiState())
    val state: StateFlow<UploadUiState> = _state
    private var config: ImportConfig? = null
    private var uploadJob: Job? = null
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
        if (uploadJob?.isActive == true) return
        uploadJob = viewModelScope.launch {
            val max = config?.maxFileSizeBytes ?: (10L * 1024 * 1024)
            // One pass off the main thread: real size (providers may not report it) and a content hash for the key.
            val file = try {
                withContext(Dispatchers.IO) { inspect(uri, max) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unreadable document (revoked permission, provider error): nothing was sent.
                _state.update { it.copy(phase = UploadPhase.Error(ErrorCodes.PROCESSING_FAILED, uri.lastPathSegment ?: "файл", config)) }
                return@launch
            }
            if (file.size > max) {
                _state.update { it.copy(phase = UploadPhase.Error(ErrorCodes.FILE_TOO_LARGE, file.name, config)) }
                return@launch
            }
            // api.md: the key belongs to «загрузить этот файл» — same content → same key until a final response
            // (survives process death); another file → another key.
            val action = "statements.upload:${file.sha256}"
            val key = keys.keyFor(action)
            tracker.track(Events.STATEMENTS_UPLOAD_STARTED)
            _state.update { it.copy(phase = UploadPhase.Busy(file.name, 0f, uploadId = null)) }
            try {
                val accepted = uploadWithRetry(key, file, uri)
                // 202 is the final response of this action: re-picking the same file later is a new upload.
                keys.complete(action)
                _state.update { it.copy(phase = UploadPhase.Busy(accepted.fileName, 0.05f, accepted.uploadId)) }
                watch()
            } catch (e: AppError) {
                // 4xx answers are final as well (stored by the server); network failures keep the key for a retry.
                if (e is AppError.Api) keys.complete(action)
                fail(e, file.name)
            }
        }
    }

    /** 409 REQUEST_IN_PROGRESS: the first request with this key is still running — wait and repeat it. */
    private suspend fun uploadWithRetry(key: IdempotencyKey, file: PickedFile, uri: Uri): ru.finassist.pf.core.api.model.UploadAccepted {
        repeat(IN_PROGRESS_RETRIES) {
            try {
                return api.uploadStatement(key, UploadFile(name = file.name, size = file.size, open = { open(uri) }))
            } catch (e: AppError.InProgress) {
                delay((e.retryAfterSeconds ?: 2).coerceIn(1, 30) * 1000L)
            }
        }
        return api.uploadStatement(key, UploadFile(name = file.name, size = file.size, open = { open(uri) }))
    }

    private fun open(uri: Uri): InputStream = try {
        context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
    } catch (e: SecurityException) {
        // Permission to the document was revoked: an I/O failure for OkHttp, not a crash on its thread.
        throw IOException(e)
    }

    /**
     * Opens (or re-opens) the progress stream; the first event is the current state (api.md SSE). A dropped
     * connection is retried with backoff while the screen is started — [stopWatching] ends it.
     */
    fun watch() {
        val busy = _state.value.phase as? UploadPhase.Busy ?: return
        val uploadId = busy.uploadId ?: return
        if (progressJob?.isActive == true) return
        progressJob = viewModelScope.launch {
            var backoff = 1_000L
            while (true) {
                try {
                    api.streamProgress(uploadId).collect { event ->
                        backoff = 1_000L
                        handle(uploadId, busy.fileName, event)
                    }
                    return@launch
                } catch (e: AppError) {
                    if (e !is AppError.Offline && e !is AppError.Server) {
                        fail(e, busy.fileName)
                        return@launch
                    }
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    private fun handle(uploadId: String, fileName: String, event: ImportEvent) {
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
                    _state.update { it.copy(phase = UploadPhase.Error(ErrorCodes.PROCESSING_FAILED, fileName, config), askCancel = false) }
                }
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
        _state.update { it.copy(phase = UploadPhase.Error(code, fileName, config), askCancel = false) }
    }

    fun askCancel(show: Boolean) = _state.update { it.copy(askCancel = show) }

    /**
     * «Прервать разбор». Before the 202 the request itself is cancelled (the key is kept: if the server got the
     * file, a retry of the same file returns that upload). After it — DELETE, and the stream ends with CANCELLED.
     */
    fun cancelImport() {
        val busy = _state.value.phase as? UploadPhase.Busy ?: return
        val id = busy.uploadId
        if (id == null) {
            uploadJob?.cancel()
            _state.update { it.copy(phase = UploadPhase.Guide(fallbackConfig()), askCancel = false) }
            return
        }
        _state.update { it.copy(phase = busy.copy(cancelling = true)) }
        viewModelScope.launch {
            try {
                api.deleteStatement(id)
                // If the server had already finished, the stream reports `complete` and the result screen opens.
            } catch (e: AppError) {
                _state.update { s ->
                    val b = s.phase as? UploadPhase.Busy
                    if (b == null) s else s.copy(phase = b.copy(cancelling = false), askCancel = false)
                }
            }
        }
    }

    /** «Выбрать другой файл» after an error. */
    fun backToGuide() = _state.update { it.copy(phase = UploadPhase.Guide(fallbackConfig())) }

    fun consumeDone() = _state.update { it.copy(doneUploadId = null) }

    private class PickedFile(val name: String, val size: Long, val sha256: String)

    /** Reads the file once: display name, real byte count (stops counting past [max]) and SHA-256. */
    private fun inspect(uri: Uri, max: Long): PickedFile {
        var name = uri.lastPathSegment ?: "statement.ofx"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (n >= 0 && !c.isNull(n)) name = c.getString(n)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        open(uri).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                size += read
                if (size > max) break
                digest.update(buffer, 0, read)
            }
        }
        return PickedFile(name, size, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private companion object {
        const val IN_PROGRESS_RETRIES = 3
    }
}
