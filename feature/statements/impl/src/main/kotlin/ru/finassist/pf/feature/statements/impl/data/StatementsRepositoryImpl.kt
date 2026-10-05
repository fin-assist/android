package ru.finassist.pf.feature.statements.impl.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.network.api.PfApi
import ru.finassist.pf.core.network.client.PfJson
import ru.finassist.pf.core.network.client.apiCall
import ru.finassist.pf.core.network.dto.ImportConfigDto
import ru.finassist.pf.core.network.dto.ImportProgressEventDto
import ru.finassist.pf.core.network.dto.ImportResultDto
import ru.finassist.pf.core.network.dto.StatementsListDto
import ru.finassist.pf.core.network.dto.UnreadLineDto
import ru.finassist.pf.core.network.dto.UploadAcceptedDto
import ru.finassist.pf.core.network.sse.SseClient
import ru.finassist.pf.core.network.sse.asStreamError
import ru.finassist.pf.feature.statements.api.StatementsRepository
import ru.finassist.pf.feature.statements.api.UploadsSummary
import javax.inject.Inject
import javax.inject.Singleton

/** Progress stream of one upload, already decoded. */
sealed interface ImportEvent {
    data class Progress(val stage: String, val percent: Int) : ImportEvent
    data class Complete(val result: ImportResultDto) : ImportEvent
    /** `error` event: PROCESSING_FAILED / CANCELLED / unknown code (= generic parse failure). */
    data class Failed(val code: String) : ImportEvent
}

@Singleton
class StatementsRepositoryImpl @Inject constructor(
    private val api: PfApi,
    private val sse: SseClient,
) : StatementsRepository {

    private val _dataChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val dataChanged: Flow<Unit> = _dataChanged

    override suspend fun summary(): UploadsSummary? =
        list().summary?.let { UploadsSummary(it.uploadCount, it.operationCount) }

    suspend fun config(): ImportConfigDto = io { api.getImportConfig() }

    suspend fun list(): StatementsListDto = io { api.listStatements() }

    suspend fun unreadLines(uploadId: String?, from: String?, to: String?): List<UnreadLineDto> =
        io { api.listUnreadLines(uploadId, from, to) }.lines

    /** Sends the file; synchronous checks (size, format, bank) come back as [AppError] subclasses. */
    suspend fun upload(key: String, fileName: String, bytes: ByteArray): UploadAcceptedDto = io {
        val part = MultipartBody.Part.createFormData("file", fileName, bytes.toRequestBody("application/octet-stream".toMediaType()))
        api.uploadStatement(key, part)
    }

    /** Cancels a running parse or deletes a finished upload; a repeat for an already deleted upload is fine (204). */
    suspend fun delete(uploadId: String) {
        io { api.deleteStatement(uploadId) }
        _dataChanged.tryEmit(Unit)
    }

    /**
     * Progress of an upload. Reconnects with backoff on transport failures — the first event after reconnect is
     * the current state, so nothing is lost (api.md «SSE»). Completes after the terminal event.
     */
    fun progress(uploadId: String): Flow<ImportEvent> = flow {
        var attempt = 0
        while (true) {
            var terminal = false
            try {
                sse.stream("v1/statements/$uploadId/progress").collect { event ->
                    when (event.name) {
                        "progress" -> {
                            val p = PfJson.decodeFromString(ImportProgressEventDto.serializer(), event.data)
                            emit(ImportEvent.Progress(p.stage, p.percent))
                        }
                        "complete" -> {
                            terminal = true
                            val r = PfJson.decodeFromString(ImportResultDto.serializer(), event.data)
                            _dataChanged.tryEmit(Unit)
                            emit(ImportEvent.Complete(r))
                        }
                        "error" -> { terminal = true; emit(ImportEvent.Failed(event.asStreamError().code)) }
                    }
                }
            } catch (e: AppError) {
                // 404: the upload is gone (cancelled from another screen or never existed) — surface as cancelled.
                if (e is AppError.NotFound) { emit(ImportEvent.Failed("CANCELLED")); return@flow }
                if (!e.isRetryable && e !is AppError.Offline) throw e
            }
            if (terminal) return@flow
            attempt++
            delay(minOf(1000L shl minOf(attempt, 4), 10_000L))
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { apiCall { block() } }
}
