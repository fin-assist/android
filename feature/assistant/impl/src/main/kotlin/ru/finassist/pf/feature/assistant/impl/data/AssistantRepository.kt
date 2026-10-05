package ru.finassist.pf.feature.assistant.impl.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.network.api.PfApi
import ru.finassist.pf.core.network.client.PfJson
import ru.finassist.pf.core.network.client.apiCall
import ru.finassist.pf.core.network.codes.ConsentType
import ru.finassist.pf.core.network.dto.AnswerBlockEventDto
import ru.finassist.pf.core.network.dto.AnswerChunkEventDto
import ru.finassist.pf.core.network.dto.AnswerDoneEventDto
import ru.finassist.pf.core.network.dto.AnswerSnapshotEventDto
import ru.finassist.pf.core.network.dto.AnswerSourceDto
import ru.finassist.pf.core.network.dto.AskRequestDto
import ru.finassist.pf.core.network.dto.AskResponseDto
import ru.finassist.pf.core.network.dto.AssistantConsentGrantDto
import ru.finassist.pf.core.network.dto.ChipDto
import ru.finassist.pf.core.network.dto.ConsentDocumentDto
import ru.finassist.pf.core.network.dto.MessagesListDto
import ru.finassist.pf.core.network.dto.ProfileDto
import ru.finassist.pf.core.network.dto.RetryResponseDto
import ru.finassist.pf.core.network.sse.SseClient
import ru.finassist.pf.core.network.sse.asStreamError
import ru.finassist.pf.feature.assistant.api.AssistantLimit
import ru.finassist.pf.feature.assistant.api.AssistantLimitRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Decoded events of `GET /v1/assistant/messages/{answer_id}/events` (api.md 7.2). */
sealed interface AnswerEvent {
    data class Snapshot(val snapshot: AnswerSnapshotEventDto) : AnswerEvent
    data class Chunk(val chunk: AnswerChunkEventDto) : AnswerEvent
    data class Block(val block: AnswerBlockEventDto) : AnswerEvent
    data class Source(val source: AnswerSourceDto) : AnswerEvent
    data class Chip(val chip: ChipDto) : AnswerEvent
    data class Done(val done: AnswerDoneEventDto) : AnswerEvent
    /** `error` event or a terminal transport failure: the attempt is refunded by the server. */
    data class Failed(val code: String, val remainingLimit: Int?) : AnswerEvent
}

@Singleton
class AssistantRepository @Inject constructor(
    private val api: PfApi,
    private val sse: SseClient,
) : AssistantLimitRepository {

    /** Fires after the consent screen succeeded, so an open chat can send the question it was holding. */
    val consentGranted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override suspend fun limit(): AssistantLimit = io { api.getAssistantLimit() }.let { AssistantLimit(it.remaining, it.dailyMax, it.resetsAt) }

    suspend fun profile(): ProfileDto = io { api.getProfile() }
    suspend fun history(beforeId: String? = null, limit: Int = 20): MessagesListDto = io { api.listMessages(limit, beforeId) }
    suspend fun ask(key: String, text: String, transferMode: String): AskResponseDto = io { api.ask(key, AskRequestDto(text, transferMode)) }
    suspend fun retry(answerId: String, key: String): RetryResponseDto = io { api.retryAnswer(answerId, key) }
    suspend fun consentDocument(): ConsentDocumentDto = io { api.getConsentDocument(ConsentType.ASSISTANT) }

    suspend fun grantConsent(version: String): ProfileDto {
        val p = io { api.grantAssistantConsent(AssistantConsentGrantDto(version)) }
        consentGranted.tryEmit(Unit)
        return p
    }

    /** Answer stream with reconnection; the first event after a reconnect is a full snapshot, so nothing is lost. */
    fun answer(answerId: String): Flow<AnswerEvent> = flow {
        var attempt = 0
        while (true) {
            var terminal = false
            try {
                sse.stream("v1/assistant/messages/$answerId/events").collect { e ->
                    when (e.name) {
                        "snapshot" -> emit(AnswerEvent.Snapshot(PfJson.decodeFromString(AnswerSnapshotEventDto.serializer(), e.data)))
                        "chunk" -> emit(AnswerEvent.Chunk(PfJson.decodeFromString(AnswerChunkEventDto.serializer(), e.data)))
                        "block" -> emit(AnswerEvent.Block(PfJson.decodeFromString(AnswerBlockEventDto.serializer(), e.data)))
                        "source" -> emit(AnswerEvent.Source(PfJson.decodeFromString(AnswerSourceDto.serializer(), e.data)))
                        "chip" -> emit(AnswerEvent.Chip(PfJson.decodeFromString(ChipDto.serializer(), e.data)))
                        "done" -> { terminal = true; emit(AnswerEvent.Done(PfJson.decodeFromString(AnswerDoneEventDto.serializer(), e.data))) }
                        "error" -> { terminal = true; val err = e.asStreamError(); emit(AnswerEvent.Failed(err.code, err.remainingLimit)) }
                    }
                }
            } catch (e: AppError) {
                // 404: the answer was replaced by a retry or never existed.
                if (e is AppError.NotFound) { emit(AnswerEvent.Failed("NOT_FOUND", null)); return@flow }
                if (!e.isRetryable && e !is AppError.Offline) throw e
            }
            if (terminal) return@flow
            attempt++
            delay(minOf(1000L shl minOf(attempt, 4), 10_000L))
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { apiCall { block() } }
}
