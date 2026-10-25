package ru.finassist.pf.feature.statements.impl.ui

import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorContext
import ru.finassist.pf.core.common.time.ElapsedClock
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
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.SseReopenState
import ru.finassist.pf.core.api.reopeningStream
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
    data class Error(val code: String, val fileName: String, val config: ImportConfig?, val presentation: ErrorPresentation? = null) : UploadPhase
}

data class UploadUiState(
    val phase: UploadPhase = UploadPhase.Loading,
    /** The config the guide is drawn with; the guide stays on screen while a file is parsed (mockup `ImportGuideBusy`). */
    val config: ImportConfig? = null,
    /** The file was not sent for lack of network: the guide shows a notice with «Повторить» (mockup `ImportGuideOffline`). */
    val uploadOffline: Boolean = false,
    val uploadRetryReady: Boolean = true,
    val guideErrorPresentation: ErrorPresentation? = null,
    /** «Не скачивается?» sheet open. */
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
    private val clock: Clock,
    private val elapsedClock: ElapsedClock,
) : ViewModel() {
    private val _state = MutableStateFlow(UploadUiState())
    private val uploadGates = mutableMapOf<String, RequestRetryGate>()
    private var uploadReadinessJob: Job? = null
    private val configGate = RequestRetryGate(elapsedClock)
    val state: StateFlow<UploadUiState> = _state
    private var config: ImportConfig? = null
    private var uploadJob: Job? = null

    /** A picked file is still being sent: another pick meanwhile is ignored. Tests wait on it before picking again. */
    internal val uploadInFlight: Boolean get() = uploadJob?.isActive == true
    /** Re-open state of each upload's progress stream for the life of the screen ([SseReopenState]). */
    private val reopenStates = HashMap<String, SseReopenState>()

    private var progressJob: Job? = null

    /** Uploads whose interruption has been tracked: a screen started again under the same maintenance does not repeat it. */
    private val interruptedTracked = HashSet<String>()

    /**
     * `complete` that arrived while «Прервать» was in flight. DELETE of a finished upload removes its
     * operations (api.md 3.5), so the result screen must not open until DELETE is known to have failed.
     */
    private var heldComplete: Pair<String, ImportEvent.Complete>? = null

    /** The last picked file: «Повторить» after a network failure sends it again. */
    private var lastPicked: Uri? = null

    init {
        load()
    }

    fun load() {
        if (!configGate.isReady()) return
        if (!flags.isEnabled(Flag.STATEMENTS_UPLOAD)) {
            _state.update { it.copy(phase = UploadPhase.Disabled) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(phase = UploadPhase.Loading) }
            try {
                val c = api.getImportConfig()
                config = c
                _state.update { it.copy(phase = UploadPhase.Guide(c), config = c) }
            } catch (e: AppError) {
                configGate.record(e)
                _state.update {
                    if (e is AppError.Offline) it.copy(phase = UploadPhase.Offline) else it.copy(phase = UploadPhase.Guide(fallbackConfig()), config = fallbackConfig(), guideErrorPresentation = e.presentation)
                }
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
            clock.now().minusMonths(12), clock.now().plusDays(1),
        ),
    )

    fun showFallback(show: Boolean) = _state.update { it.copy(fallbackOpen = show) }

    fun onFilePicked(uri: Uri) {
        if (uploadInFlight) return
        lastPicked = uri
        _state.update { it.copy(uploadOffline = false, config = it.config ?: fallbackConfig()) }
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
            val retryGate = uploadGates.getOrPut(action) { RequestRetryGate(elapsedClock) }
            if (!retryGate.isReady()) return@launch
            uploadReadinessJob?.cancel()
            uploadReadinessJob = viewModelScope.launch { retryGate.readiness.collect { ready -> _state.update { it.copy(uploadRetryReady = ready) } } }
            val key = keys.keyFor(action, elapsedClock)
            if (key.replayWindow?.isOpen(elapsedClock) != true) {
                _state.update { it.copy(phase = UploadPhase.Error(ErrorCodes.PROCESSING_FAILED, file.name, config), uploadRetryReady = false) }
                return@launch
            }
            tracker.track(Events.STATEMENTS_UPLOAD_STARTED)
            _state.update { it.copy(phase = UploadPhase.Busy(file.name, 0f, uploadId = null)) }
            try {
                val accepted = uploadWithRetry(key, file, uri)
                // 202 is the final response of this action: re-picking the same file later is a new upload.
                keys.complete(action)
                _state.update { it.copy(phase = UploadPhase.Busy(accepted.fileName, 0.05f, accepted.uploadId)) }
                watch()
            } catch (e: AppError) {
                retryGate.record(e)
                // Explicit retry advice retains this exact original operation/key. Only the existing legacy
                // final file-rejection flow may finish it; fixing/reselecting a rejected file is a new intent.
                if (e is AppError.Api && e.response?.context != ErrorContext.RESULT_EVENT && e.requestRetryable == null &&
                    e.code in setOf(ErrorCodes.WRONG_FORMAT, ErrorCodes.WRONG_BANK, ErrorCodes.CSV_NOT_ACCEPTED, ErrorCodes.FILE_TOO_LARGE)) {
                    keys.complete(action)
                }
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
                if (e.requestRetryable == false || key.replayWindow?.isOpen(elapsedClock) != true) throw e
                delay((e.retryAfterSeconds ?: 2).coerceAtLeast(1) * 1000L)
                if (key.replayWindow?.isOpen(elapsedClock) != true) throw e
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
     * Opens (or re-opens) the progress stream; the first event is the current state (api.md SSE). A drop (network,
     * 5xx, a stream closed without `complete` / `error`) is re-opened by [reopeningStream] while the screen is
     * started — [stopWatching] ends it; any other error ends the upload with it.
     */
    fun watch() {
        val busy = _state.value.phase as? UploadPhase.Busy ?: return
        val uploadId = busy.uploadId ?: return
        if (progressJob?.isActive == true) return
        progressJob = viewModelScope.launch {
            try {
                // Opening it again after the screen came back is a re-open: a 429 is waited for, not an error (api.md «SSE»).
                reopeningStream(
                    isTerminal = { it !is ImportEvent.Progress },
                    state = reopenStates.getOrPut(uploadId) { SseReopenState() },
                    elapsedClock = elapsedClock,
                ) { api.streamProgress(uploadId) }
                    .collect { event -> handle(uploadId, busy.fileName, event) }
            } catch (e: AppError) {
                fail(e, busy.fileName)
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
                if ((_state.value.phase as? UploadPhase.Busy)?.cancelling == true) heldComplete = uploadId to event
                else finish(uploadId, event)
            }
            is ImportEvent.Error -> {
                if (event.code == ErrorCodes.CANCELLED) {
                    tracker.track(Events.STATEMENTS_UPLOAD_CANCELLED)
                    _state.update { it.copy(phase = UploadPhase.Guide(fallbackConfig()), askCancel = false) }
                } else {
                    tracker.track(Events.STATEMENTS_UPLOAD_FAILED, mapOf("code" to event.code))
                    _state.update { it.copy(phase = UploadPhase.Error(event.code, fileName, config, event.presentation), askCancel = false) }
                }
            }
        }
    }

    private fun finish(uploadId: String, event: ImportEvent.Complete) {
        repository.importCompleted(uploadId, event.result)
        tracker.track(Events.STATEMENTS_UPLOAD_COMPLETED, mapOf("new_count" to event.result.newCount.toString()))
        _state.update { it.copy(doneUploadId = uploadId, askCancel = false) }
    }

    fun stopWatching() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun fail(e: AppError, fileName: String) {
        if (e.response?.context != ErrorContext.RESULT_EVENT && (e is AppError.Maintenance || e is AppError.UpdateRequired)) return interrupted(e)
        val code = when (e) {
            is AppError.Api -> e.code
            is AppError.Offline -> OFFLINE
            else -> ErrorCodes.PROCESSING_FAILED
        }
        tracker.track(Events.STATEMENTS_UPLOAD_FAILED, mapOf("code" to code))
        _state.update {
            // No network: nothing reached the server — back to the guide with «Повторить» for the same file.
            if (code == OFFLINE) it.copy(phase = UploadPhase.Guide(fallbackConfig()), uploadOffline = true, askCancel = false)
            else it.copy(phase = UploadPhase.Error(code, fileName, config, e.presentation), askCancel = false)
        }
    }

    /**
     * Maintenance or an unsupported version (FIN-177): the server did not reject the file, the app is covered by its
     * screen. Not a failure of the upload — no error, no `statements.upload.failed`. Before the 202 nothing was
     * accepted: back to the guide, the key kept (a 503 is not a final answer). After it parsing may go on on the server:
     * the upload stays on screen, and [watch] picks up its state when the screen is started again.
     */
    private fun interrupted(e: AppError) {
        val uploadId = (_state.value.phase as? UploadPhase.Busy)?.uploadId
        if (uploadId == null || interruptedTracked.add(uploadId)) {
            val reason = if (e is AppError.Maintenance) "maintenance" else "update_required"
            tracker.track(Events.STATEMENTS_UPLOAD_INTERRUPTED, mapOf("reason" to reason))
        }
        _state.update { s ->
            if ((s.phase as? UploadPhase.Busy)?.uploadId != null) s
            else s.copy(phase = UploadPhase.Guide(fallbackConfig()), askCancel = false)
        }
    }

    /** «Повторить» in the no-network notice: sends the same file again (the same content keeps its key). */
    fun retryUpload() {
        if (!_state.value.uploadRetryReady) return
        val uri = lastPicked ?: return
        onFilePicked(uri)
    }

    fun askCancel(show: Boolean) = _state.update { it.copy(askCancel = show) }

    /**
     * «Прервать разбор». Before the 202 the request itself is cancelled (the key is kept: if the server got the
     * file, a retry of the same file returns that upload). After it — DELETE. A successful DELETE always means
     * «nothing saved», as the dialog promises: either parsing stopped (the stream ends with CANCELLED) or the
     * upload had already finished and its operations were deleted — then a `complete` that raced the DELETE is
     * dropped and the screen returns to the guide instead of opening the result.
     */
    fun cancelImport() {
        val busy = _state.value.phase as? UploadPhase.Busy ?: return
        val id = busy.uploadId
        if (id == null) {
            uploadJob?.cancel()
            _state.update { it.copy(phase = UploadPhase.Guide(fallbackConfig()), askCancel = false) }
            return
        }
        heldComplete = null
        _state.update { it.copy(phase = busy.copy(cancelling = true)) }
        viewModelScope.launch {
            try {
                api.deleteStatement(id)
            } catch (e: AppError) {
                _state.update { s ->
                    val b = s.phase as? UploadPhase.Busy
                    if (b == null) s else s.copy(phase = b.copy(cancelling = false), askCancel = false)
                }
                // DELETE failed but parsing had finished meanwhile: the import stands, show its result.
                heldComplete?.let { (uploadId, event) -> heldComplete = null; finish(uploadId, event) }
                return@launch
            }
            heldComplete = null
            stopWatching()
            // Harmless if parsing was merely stopped; required if a finished import was just deleted.
            repository.uploadDeleted(id)
            if (_state.value.phase is UploadPhase.Busy) {
                tracker.track(Events.STATEMENTS_UPLOAD_CANCELLED)
                _state.update { it.copy(phase = UploadPhase.Guide(fallbackConfig()), askCancel = false) }
            }
        }
    }

    /** «Выбрать другой файл» after an error. */
    fun backToGuide() = _state.update { it.copy(phase = UploadPhase.Guide(fallbackConfig()), uploadOffline = false) }

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
        const val OFFLINE = "OFFLINE"
    }
}
