package ru.finassist.pf.mock.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.model.AnswerBlockEvent
import ru.finassist.pf.core.api.model.AnswerChunkEvent
import ru.finassist.pf.core.api.model.AnswerDoneEvent
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.AnswerErrorEvent
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AskResponse
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.Message
import ru.finassist.pf.core.api.model.MessageRole
import ru.finassist.pf.core.api.model.MessagesList
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.mock.AssistantStore
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.domain.AssistantEngine
import java.time.LocalDate
import java.util.UUID

/** Assistant (api.md 7): daily limit, consent check, server-side generation streamed as events. */
class MockAssistantApi(private val backend: MockBackend) : AssistantApi {

    private val engine = AssistantEngine(backend.ledger, backend.catalog, backend.operationScope, backend::coverage)
    private val store get() = backend.assistant

    private fun moscowToday(): LocalDate = backend.now().atZoneSameInstant(Clock.MOSCOW).toLocalDate()
    private fun remaining(): Int = DAILY_MAX - (store.usage[moscowToday()] ?: 0)
    private fun resetsAt() = moscowToday().plusDays(1).atStartOfDay(Clock.MOSCOW).toOffsetDateTime()

    override suspend fun listMessages(limit: Int?, beforeId: String?): MessagesList {
        backend.simulateNetwork()
        backend.requireSession()
        val size = (limit ?: 20).coerceIn(1, 50)
        return backend.mutex.withLock {
            val all = store.history()
            val end = beforeId?.let { id -> all.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: all.size
            val page = all.subList(maxOf(0, end - size), end)
            MessagesList(messages = page, hasOlder = end - size > 0)
        }
    }

    override suspend fun ask(key: IdempotencyKey, request: AskRequest): AskResponse {
        backend.simulateNetwork()
        backend.requireSession()
        if (request.text.isBlank() || request.text.length > 2000) throw AppError.Api(ErrorCodes.VALIDATION_ERROR, 400)
        if (request.transferMode == TransferMode.UNKNOWN) throw AppError.Api(ErrorCodes.VALIDATION_ERROR, 400)
        return backend.idempotent("assistant.ask:${key.value}") {
            checkConsentAndLimit()
            val question = Message(id = UUID.randomUUID().toString(), role = MessageRole.USER, text = request.text, createdAt = backend.now())
            val answer = backend.mutex.withLock {
                store.questions += question
                store.usage[moscowToday()] = (store.usage[moscowToday()] ?: 0) + 1
                AssistantStore.Answer(UUID.randomUUID().toString(), question.id, backend.now()).also { store.answers[it.id] = it }
            }
            generate(answer, request.text, request.transferMode ?: TransferMode.WITH)
            AskResponse(question = question, answerId = answer.id, remainingLimit = remaining())
        }
    }

    private fun checkConsentAndLimit() {
        if (!backend.profile.assistantConsent.granted) throw AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403)
        if (remaining() <= 0) throw AppError.Api(ErrorCodes.LIMIT_EXCEEDED, 403, resetsAt = resetsAt())
    }

    /** Generation runs on the backend scope: the client can drop the stream and reconnect. */
    private fun generate(answer: AssistantStore.Answer, text: String, mode: TransferMode) {
        backend.scope.launch {
            delay(backend.config.networkDelayMs * 2)
            val outcome = backend.mutex.withLock { engine.answer(text, mode, moscowToday()) }
            when (outcome) {
                AssistantEngine.Outcome.CannotAnswer -> {
                    refund()
                    answer.append(AnswerEvent.Error(AnswerErrorEvent(AnswerErrorCode.CANNOT_ANSWER, "cannot answer from data", remaining())))
                }
                is AssistantEngine.Outcome.Answered -> {
                    val a = outcome.answer
                    var index = 0
                    for (block in a.blocks) {
                        if (block.type == BlockType.TEXT) {
                            val words = block.text.orEmpty().split(" ")
                            words.forEachIndexed { i, w ->
                                answer.append(AnswerEvent.Chunk(AnswerChunkEvent(index, if (i == 0) w else " $w")))
                                delay(backend.config.assistantChunkDelayMs)
                            }
                        } else {
                            answer.append(AnswerEvent.BlockAdded(AnswerBlockEvent(index, block)))
                            delay(backend.config.assistantChunkDelayMs * 4)
                        }
                        index++
                    }
                    answer.append(AnswerEvent.Source(AnswerSource(calculatedAt = backend.now(), dataRange = a.dataRange, transferMode = mode, changedSince = emptyList())))
                    a.chips.forEach { answer.append(AnswerEvent.ChipAdded(it)) }
                    if (!a.charged) refund()
                    answer.append(AnswerEvent.Done(AnswerDoneEvent(charged = a.charged, remainingLimit = remaining())))
                }
            }
        }
    }

    private suspend fun refund() = backend.mutex.withLock {
        store.usage[moscowToday()] = ((store.usage[moscowToday()] ?: 1) - 1).coerceAtLeast(0)
    }

    override fun streamAnswer(answerId: String): Flow<AnswerEvent> = flow {
        val answer = backend.mutex.withLock { store.answers[answerId] }
        if (answer == null || answer.replaced) throw AppError.Api(ErrorCodes.NOT_FOUND, 404)
        val (snapshot, seen) = answer.snapshot()
        emit(AnswerEvent.Snapshot(snapshot))
        if (snapshot.status != AnswerStatus.GENERATING) {
            // Already finished: the terminal event comes right after the snapshot (api.md 7.2).
            synchronized(answer) { answer.log.lastOrNull { it is AnswerEvent.Done || it is AnswerEvent.Error } }?.let { emit(it) }
            return@flow
        }
        var next = seen
        // Follow the log as it grows; stop after the terminal event.
        emitAll(
            answer.version.transformWhile { size ->
                var keepGoing = true
                while (next < size) {
                    val event = synchronized(answer) { answer.log[next] }
                    next++
                    emit(event)
                    if (event is AnswerEvent.Done || event is AnswerEvent.Error) { keepGoing = false; break }
                }
                keepGoing
            },
        )
    }

    override suspend fun retry(key: IdempotencyKey, answerId: String): RetryResponse {
        backend.simulateNetwork()
        backend.requireSession()
        return backend.idempotent("assistant.retry:${key.value}") {
            val old = backend.mutex.withLock { store.answers[answerId] } ?: throw AppError.Api(ErrorCodes.NOT_FOUND, 404)
            if (old.replaced || old.status != AnswerStatus.FAILED || old.errorCode != AnswerErrorCode.MODEL_ERROR) {
                throw AppError.Api(ErrorCodes.RETRY_NOT_ALLOWED, 422)
            }
            checkConsentAndLimit()
            val question = store.questions.first { it.id == old.questionId }
            val fresh = backend.mutex.withLock {
                old.replaced = true
                store.usage[moscowToday()] = (store.usage[moscowToday()] ?: 0) + 1
                AssistantStore.Answer(UUID.randomUUID().toString(), old.questionId, backend.now()).also { store.answers[it.id] = it }
            }
            generate(fresh, question.text.orEmpty(), TransferMode.WITH)
            RetryResponse(answerId = fresh.id, remainingLimit = remaining())
        }
    }

    override suspend fun getLimit(): AssistantLimit {
        backend.simulateNetwork()
        backend.requireSession()
        return AssistantLimit(remaining = remaining(), dailyMax = DAILY_MAX, resetsAt = resetsAt())
    }

    private companion object {
        const val DAILY_MAX = 5
    }
}
