package ru.finassist.pf.mock.api

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import ru.finassist.pf.mock.MockConfig
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
            val question = Message(id = UUID.randomUUID().toString(), role = MessageRole.USER, text = request.text, createdAt = backend.now())
            val mode = request.transferMode ?: TransferMode.WITH
            val (answer, remainingLimit) = backend.mutex.withLock {
                // Checked under the lock with the charge: two questions at the last remaining one get one answer.
                checkConsentAndLimit()
                store.questions += question
                store.usage[moscowToday()] = (store.usage[moscowToday()] ?: 0) + 1
                // Read with the charge, before generation starts: a refund (CANNOT_ANSWER, not charged) is
                // reported by the answer's own terminal event, never by this response (FIN-76).
                AssistantStore.Answer(UUID.randomUUID().toString(), question.id, backend.now(), mode, store.changes).also { store.answers[it.id] = it } to remaining()
            }
            generate(answer, request.text)
            AskResponse(question = question, answerId = answer.id, remainingLimit = remainingLimit)
        }
    }

    private fun checkConsentAndLimit() {
        if (backend.profile.assistantConsent?.granted != true) throw AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403)
        if (remaining() <= 0) throw AppError.Api(ErrorCodes.LIMIT_EXCEEDED, 403, resetsAt = resetsAt())
    }

    /**
     * Generation runs on the backend scope: the client can drop the stream and reconnect. The engine never fails
     * like a model; [MockConfig.assistantModelErrors] makes the next answers fail with MODEL_ERROR (refunded, retryable).
     */
    private fun generate(answer: AssistantStore.Answer, text: String) {
        val mode = answer.transferMode
        backend.scope.launch {
            try {
                // api.md 7.2: a generation not finished in 3 minutes ends with MODEL_ERROR (refunded, retryable).
                withTimeout(backend.config.answerTimeoutMs) { produce(answer, text, mode) }
            } catch (e: TimeoutCancellationException) {
                // The deadline may pass inside a non-cancellable ending that already sent the terminal event.
                if (answer.status == AnswerStatus.GENERATING) fail(answer, AnswerErrorCode.MODEL_ERROR, "generation timed out")
            }
        }
    }

    private suspend fun produce(answer: AssistantStore.Answer, text: String, mode: TransferMode) {
        delay(backend.config.networkDelayMs * 2)
        var calculatedAt = backend.now()
        val outcome = backend.mutex.withLock {
            val modelErrors = backend.config.assistantModelErrors
            if (modelErrors > 0) {
                backend.config = backend.config.copy(assistantModelErrors = modelErrors - 1)
                null
            } else {
                // The data the answer is calculated on: `calculated_at` and the version `changed_since` counts from.
                calculatedAt = backend.now()
                answer.calculatedOn = store.changes.version
                engine.answer(text, mode, moscowToday())
            }
        }
        when (outcome) {
            null -> fail(answer, AnswerErrorCode.MODEL_ERROR, "model failed")
            AssistantEngine.Outcome.CannotAnswer -> fail(answer, AnswerErrorCode.CANNOT_ANSWER, "cannot answer from data")
            AssistantEngine.Outcome.OffTopic -> fail(answer, AnswerErrorCode.OFF_TOPIC, "off_topic tool called")
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
                // The ending is not cut by the timeout: a refund followed by the timeout's own refund would return
                // two attempts for one answer.
                withContext(NonCancellable) {
                    // What changed while the answer was streaming is reported already here; the history recounts it (7.3).
                    val source = AnswerSource(calculatedAt = calculatedAt, dataRange = a.dataRange, transferMode = mode, changedSince = emptyList())
                    answer.append(AnswerEvent.Source(backend.mutex.withLock { store.changes.restamp(source, answer.calculatedOn) }))
                    a.chips.forEach { answer.append(AnswerEvent.ChipAdded(it)) }
                    if (!a.charged) refund()
                    answer.append(AnswerEvent.Done(AnswerDoneEvent(charged = a.charged, remainingLimit = remaining())))
                }
            }
        }
    }

    /**
     * Any failure refunds the attempt (api.md 7); the text and `retryable` are the server's (FIN-159). Not cut by the
     * generation timeout: the refund and the `error` event go together.
     */
    private suspend fun fail(answer: AssistantStore.Answer, code: AnswerErrorCode, message: String) = withContext(NonCancellable) {
        refund()
        answer.append(
            AnswerEvent.Error(
                AnswerErrorEvent(
                    code = code, message = message, remainingLimit = remaining(),
                    text = ERROR_TEXTS.getValue(code), retryable = code == AnswerErrorCode.MODEL_ERROR,
                ),
            ),
        )
    }

    private suspend fun refund() = backend.mutex.withLock {
        store.usage[moscowToday()] = ((store.usage[moscowToday()] ?: 1) - 1).coerceAtLeast(0)
    }

    override fun streamAnswer(answerId: String): Flow<AnswerEvent> = backend.droppable(answerEvents(answerId)) { event ->
        event is AnswerEvent.Done || event is AnswerEvent.Error ||
            (event is AnswerEvent.Snapshot && event.event.status != AnswerStatus.GENERATING)
    }

    private fun answerEvents(answerId: String): Flow<AnswerEvent> = flow {
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
        // The latency counts as the server's work on the request: a repeat with the same key while the first one is
        // on its way gets 409 REQUEST_IN_PROGRESS, as a double tap on «Повторить» would on a real server (FIN-75).
        val network: suspend () -> Unit = {
            backend.simulateNetwork()
            backend.requireSession()
        }
        return backend.idempotent("assistant.retry:${key.value}", onRepeat = network) {
            network()
            val (fresh, remainingLimit, question) = backend.mutex.withLock {
                // One critical section from the checks to the charge, as in [ask]: two retries of the same answer
                // replace it once, and the last remaining question is not spent twice.
                val old = store.answers[answerId] ?: throw AppError.Api(ErrorCodes.NOT_FOUND, 404)
                if (!old.retryAllowed) {
                    throw AppError.Api(ErrorCodes.RETRY_NOT_ALLOWED, 422)
                }
                checkConsentAndLimit()
                val question = store.questions.first { it.id == old.questionId }
                old.replaced = true
                store.usage[moscowToday()] = (store.usage[moscowToday()] ?: 0) + 1
                // Same question, same transfer mode; the limit is read with the charge, as in [ask].
                val fresh = AssistantStore.Answer(UUID.randomUUID().toString(), old.questionId, backend.now(), old.transferMode, store.changes)
                    .also { store.answers[it.id] = it }
                Triple(fresh, remaining(), question)
            }
            generate(fresh, question.text.orEmpty())
            RetryResponse(answerId = fresh.id, remainingLimit = remainingLimit)
        }
    }

    override suspend fun getLimit(): AssistantLimit {
        backend.simulateNetwork()
        backend.requireSession()
        return AssistantLimit(remaining = remaining(), dailyMax = DAILY_MAX, resetsAt = resetsAt())
    }

    companion object {
        private const val DAILY_MAX = 5

        /** Texts of the failures (api.md 7.2): the client shows them instead of the answer. */
        val ERROR_TEXTS: Map<AnswerErrorCode, String> = mapOf(
            AnswerErrorCode.MODEL_ERROR to "Не получилось ответить. Вопрос не потрачен",
            AnswerErrorCode.CANNOT_ANSWER to "Не получилось ответить — недостаточно данных, переформулируйте вопрос. Вопрос не потрачен",
            AnswerErrorCode.OFF_TOPIC to "Вопрос не по теме — помощник отвечает только на вопросы о ваших финансах. " +
                "С другими вопросами помогут универсальные ассистенты: Алиса AI, GigaChat, ChatGPT, Claude, DeepSeek",
        )
    }
}
