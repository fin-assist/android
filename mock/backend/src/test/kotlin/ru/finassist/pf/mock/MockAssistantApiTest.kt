package ru.finassist.pf.mock

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.AnswerErrorEvent
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChipScreen
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.Message
import ru.finassist.pf.core.api.model.MessageRole
import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.mock.api.MockAssistantApi
import ru.finassist.pf.mock.domain.AssistantEngine
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Assistant service (api.md 7): ask → streamed answer, refunds, daily limit, retry, idempotency, history. */
class MockAssistantApiTest {
    private val clock = TestClock(NOW)

    /** Krabsbir every month April — September; nothing in «Такси». */
    private val doc = statement(
        "2026-04-01", "2026-09-30",
        *(4..9).map { m -> trn("k$m", "2026-%02d-15T12:00".format(m), 100L * m, "Krabsbir", "Рестораны") }.toTypedArray(),
        trn("salary", "2026-09-05T10:00", 90_000, "ООО Ромашка", "Зарплата", debit = false),
    )

    private val backend = testBackend(clock, assistantConsent = true).apply { load(doc) }
    private val api = MockAssistantApi(backend)

    private fun key(k: String) = IdempotencyKey(k)
    private suspend fun ask(text: String, k: String = text, mode: TransferMode? = null) = api.ask(key(k), AskRequest(text, mode))
    private suspend fun events(answerId: String) = withTimeout(10_000) { api.streamAnswer(answerId).toList() }

    /** Waits until the answer is finished and returns the full stream of it. */
    private suspend fun finish(answerId: String): List<AnswerEvent> = events(answerId)

    /**
     * The finished answer as one snapshot. A live stream may fold early events into its first snapshot, so
     * assertions on content use the replay after the terminal event.
     */
    private suspend fun finalSnapshot(answerId: String) = finish(answerId).let { events(answerId).first() as AnswerEvent.Snapshot }.event

    @Test
    fun `an answered question streams the answer and ends with done, charged`() = runBlocking<Unit> {
        val response = ask("Сколько я трачу на Krabsbir?")
        assertEquals(4, response.remainingLimit)
        assertEquals(MessageRole.USER, response.question.role)
        assertEquals("Сколько я трачу на Krabsbir?", response.question.text)

        val events = finish(response.answerId)
        assertIs<AnswerEvent.Snapshot>(events.first())
        val done = assertIs<AnswerEvent.Done>(events.last()).event
        assertTrue(done.charged)
        assertEquals(4, done.remainingLimit)
        // Exactly one terminal event, and it is the last one.
        assertEquals(1, events.count { it is AnswerEvent.Done || it is AnswerEvent.Error })

        // A late subscriber gets the whole answer as one snapshot plus the terminal event.
        val replay = events(response.answerId)
        assertEquals(2, replay.size)
        val snapshot = assertIs<AnswerEvent.Snapshot>(replay[0]).event
        assertEquals(done, assertIs<AnswerEvent.Done>(replay[1]).event)
        assertEquals(AnswerStatus.COMPLETE, snapshot.status)
        val expected = (AssistantEngine(backend.ledger, backend.catalog, backend.operationScope, backend::coverage)
            .answer("Сколько я трачу на Krabsbir?", TransferMode.WITH, clock.at.toLocalDate()) as AssistantEngine.Outcome.Answered).answer
        assertEquals(expected.blocks, snapshot.blocks)
        assertEquals(expected.chips, snapshot.chips)
        assertEquals(listOf(BlockType.TEXT, BlockType.CHART, BlockType.ROWS), snapshot.blocks.map { it.type })
        assertEquals(TransferMode.WITH, snapshot.source?.transferMode)
        assertEquals(expected.dataRange, snapshot.source?.dataRange)
    }

    @Test
    fun `the answer is part of the history with its source and chips`() = runBlocking<Unit> {
        val response = ask("Сколько я трачу на Krabsbir?", mode = TransferMode.WITHOUT)
        finish(response.answerId)
        val messages = api.listMessages().messages
        assertEquals(listOf(response.question.id, response.answerId), messages.map { it.id })
        val answer = messages[1]
        assertEquals(MessageRole.ASSISTANT, answer.role)
        assertEquals(AnswerStatus.COMPLETE, answer.status)
        assertEquals(true, answer.charged)
        assertNull(answer.errorCode)
        assertEquals(TransferMode.WITHOUT, answer.source?.transferMode)
        assertEquals(listOf(ChipScreen.OPERATIONS, ChipScreen.ANALYTICS), answer.chips?.map { it.screen })
        assertEquals("s_merchant_krabsbir", answer.chips!![0].filters?.selection)
    }

    @Test
    fun `a question the data cannot answer fails with CANNOT_ANSWER and is refunded`() = runBlocking<Unit> {
        // The ask response may already include the refund: generation runs right away with zero latency.
        val response = ask("Какая погода на Марсе?")
        val error = assertIs<AnswerEvent.Error>(finish(response.answerId).last()).event
        assertEquals(AnswerErrorCode.CANNOT_ANSWER, error.code)
        assertEquals(5, error.remainingLimit)
        assertEquals(5, api.getLimit().remaining)

        val answer = api.listMessages().messages.last()
        assertEquals(AnswerStatus.FAILED, answer.status)
        assertEquals(AnswerErrorCode.CANNOT_ANSWER, answer.errorCode)
        assertEquals(emptyList(), answer.blocks)
        assertNull(answer.charged)
        assertNull(answer.chips)
        assertNull(answer.source)
    }

    @Test
    fun `a question about something with no operations is answered but not charged`() = runBlocking<Unit> {
        val response = ask("Расходы на такси")
        val done = assertIs<AnswerEvent.Done>(finish(response.answerId).last()).event
        assertEquals(false, done.charged)
        assertEquals(5, done.remainingLimit)
        assertEquals(5, api.getLimit().remaining)
        val snapshot = finalSnapshot(response.answerId)
        assertEquals(emptyList(), snapshot.chips)
        val text = snapshot.blocks.single().text.orEmpty()
        assertTrue(text.startsWith("За ") && text.contains("«Такси» не нашли"), text)
    }

    @Test
    fun `a general spending question counts every expense, a category question that category`() = runBlocking<Unit> {
        val chip = finalSnapshot(ask("Сколько я потратил?").answerId).chips.first()
        assertEquals(OperationKindFilter.EXPENSE, chip.filters?.kind)
        assertNull(chip.filters?.categoryId)

        val category = finalSnapshot(ask("Сколько уходит на рестораны").answerId)
        assertEquals("cat_restaurants", category.chips.first().filters?.categoryId)
    }

    @Test
    fun `an empty ledger cannot answer anything`() = runBlocking<Unit> {
        val empty = MockAssistantApi(testBackend(clock, assistantConsent = true))
        val response = empty.ask(key("e"), AskRequest("Сколько я трачу?"))
        val error = assertIs<AnswerEvent.Error>(withTimeout(10_000) { empty.streamAnswer(response.answerId).toList() }.last())
        assertEquals(AnswerErrorCode.CANNOT_ANSWER, error.event.code)
    }

    @Test
    fun `a repeated ask with the same key returns the same answer and is charged once`() = runBlocking<Unit> {
        val first = ask("Сколько я трачу на Krabsbir?", k = "same")
        val again = ask("Сколько я трачу на Krabsbir?", k = "same")
        assertEquals(first, again)
        finish(first.answerId)
        assertEquals(4, api.getLimit().remaining)
        assertEquals(2, api.listMessages().messages.size)
    }

    @Test
    fun `the sixth charged question of a Moscow day is LIMIT_EXCEEDED until midnight`() = runBlocking<Unit> {
        repeat(5) { finish(ask("Сколько я трачу на Krabsbir?", k = "q$it").answerId) }
        assertEquals(0, api.getLimit().remaining)
        val e = assertFailsWith<AppError.Api> { ask("Сколько я трачу на Krabsbir?", k = "q6") }
        assertEquals(ErrorCodes.LIMIT_EXCEEDED, e.code)
        assertEquals(403, e.httpStatus)
        assertEquals(OffsetDateTime.parse("2026-10-07T00:00:00+03:00"), e.resetsAt)
        val limit = api.getLimit()
        assertEquals(5, limit.dailyMax)
        assertEquals(OffsetDateTime.parse("2026-10-07T00:00:00+03:00"), limit.resetsAt)
        // The rejected question is not stored.
        assertEquals(10, api.listMessages(limit = 50).messages.size)

        // 21:30 UTC is already the next day in Moscow.
        clock.at = OffsetDateTime.parse("2026-10-06T21:30:00Z")
        assertEquals(5, api.getLimit().remaining)
        assertEquals(4, ask("Сколько я трачу на Krabsbir?", k = "q7").remainingLimit)
    }

    @Test
    fun `asking requires a session, the assistant consent and a valid request`() = runBlocking<Unit> {
        for (request in listOf(AskRequest(" "), AskRequest("a".repeat(2001)), AskRequest("Сколько?", TransferMode.UNKNOWN))) {
            val e = assertFailsWith<AppError.Api> { api.ask(key(request.text), request) }
            assertEquals(ErrorCodes.VALIDATION_ERROR, e.code)
            assertEquals(400, e.httpStatus)
        }
        ask("a".repeat(2000)).let { finish(it.answerId) }

        val noConsent = MockAssistantApi(testBackend(clock).apply { load(doc) })
        val e = assertFailsWith<AppError.Api> { noConsent.ask(key("c"), AskRequest("Сколько я трачу?")) }
        assertEquals(ErrorCodes.CONSENT_REQUIRED, e.code)
        assertEquals(403, e.httpStatus)
        assertEquals(5, noConsent.getLimit().remaining)

        val signedOut = MockAssistantApi(testBackend(clock, userId = ""))
        assertEquals(ErrorCodes.INVALID_TOKEN, assertFailsWith<AppError.Unauthorized> { signedOut.ask(key("s"), AskRequest("Сколько?")) }.code)
        assertFailsWith<AppError.Unauthorized> { signedOut.getLimit() }
        assertFailsWith<AppError.Unauthorized> { signedOut.listMessages() }
        assertFailsWith<AppError.Unauthorized> { signedOut.retry(key("r"), "a") }

        backend.config = backend.config.copy(offline = true)
        assertFailsWith<AppError.Offline> { ask("Сколько?") }
    }

    @Test
    fun `streaming an unknown answer is NOT_FOUND`() = runBlocking<Unit> {
        val e = assertFailsWith<AppError.Api> { api.streamAnswer("missing").toList() }
        assertEquals(ErrorCodes.NOT_FOUND, e.code)
        assertEquals(404, e.httpStatus)
    }

    /** The engine never fails with MODEL_ERROR; a model failure is put into the store the way generation would. */
    private fun failedAnswer(text: String, code: AnswerErrorCode = AnswerErrorCode.MODEL_ERROR): AssistantStore.Answer {
        val question = Message(id = "q-$text", role = MessageRole.USER, text = text, createdAt = clock.at)
        val answer = AssistantStore.Answer("a-$text", question.id, clock.at)
        answer.append(AnswerEvent.Error(AnswerErrorEvent(code, "model failed", 5)))
        backend.assistant.questions += question
        backend.assistant.answers[answer.id] = answer
        return answer
    }

    @Test
    fun `retry of a MODEL_ERROR answer replaces it with a fresh one and charges again`() = runBlocking<Unit> {
        val failed = failedAnswer("Сколько я трачу на Krabsbir?")
        val retry = api.retry(key("r1"), failed.id)
        assertNotEquals(failed.id, retry.answerId)
        assertEquals(4, retry.remainingLimit)
        // Same key, same response: no second replacement.
        assertEquals(retry, api.retry(key("r1"), failed.id))

        assertIs<AnswerEvent.Done>(finish(retry.answerId).last())
        assertEquals(ErrorCodes.NOT_FOUND, assertFailsWith<AppError.Api> { api.streamAnswer(failed.id).toList() }.code)
        // The question stays once; the history shows the new answer instead of the failed one.
        val history = api.listMessages().messages
        assertEquals(listOf("q-Сколько я трачу на Krabsbir?", retry.answerId), history.map { it.id })
        assertEquals(AnswerStatus.COMPLETE, history[1].status)

        // The replaced answer cannot be retried again.
        assertEquals(ErrorCodes.RETRY_NOT_ALLOWED, assertFailsWith<AppError.Api> { api.retry(key("r2"), failed.id) }.code)
    }

    @Test
    fun `the next answers set to fail end with MODEL_ERROR, free, and the retry after them is answered`() = runBlocking<Unit> {
        backend.config = backend.config.copy(assistantModelErrors = 1)
        val asked = ask("Сколько я трачу на Krabsbir?")
        val error = assertIs<AnswerEvent.Error>(finish(asked.answerId).last()).event
        assertEquals(AnswerErrorCode.MODEL_ERROR, error.code)
        assertEquals(5, error.remainingLimit)
        val failed = api.listMessages().messages.last()
        assertEquals(AnswerStatus.FAILED to AnswerErrorCode.MODEL_ERROR, failed.status to failed.errorCode)
        assertEquals(0, backend.config.assistantModelErrors)

        val retry = api.retry(key("r1"), asked.answerId)
        assertIs<AnswerEvent.Done>(finish(retry.answerId).last())
    }

    @Test
    fun `a retry repeated while the first one is on its way is REQUEST_IN_PROGRESS, then gets the same response`() = runBlocking<Unit> {
        val slow = testBackend(clock, config = FAST.copy(networkDelayMs = 200), assistantConsent = true).apply { load(doc) }
        val slowApi = MockAssistantApi(slow)
        val question = Message(id = "q1", role = MessageRole.USER, text = "Сколько я трачу на Krabsbir?", createdAt = clock.at)
        val failed = AssistantStore.Answer("a1", question.id, clock.at).apply {
            append(AnswerEvent.Error(AnswerErrorEvent(AnswerErrorCode.MODEL_ERROR, "model failed", 5)))
        }
        slow.assistant.questions += question
        slow.assistant.answers[failed.id] = failed

        // The first request holds the key through its latency.
        val first = async(start = CoroutineStart.UNDISPATCHED) { slowApi.retry(key("r1"), failed.id) }
        val busy = assertFailsWith<AppError.InProgress> { slowApi.retry(key("r1"), failed.id) }
        assertEquals(1, busy.retryAfterSeconds)
        val response = first.await()
        assertEquals(response, slowApi.retry(key("r1"), failed.id))
        assertEquals(4, slowApi.getLimit().remaining)
    }

    @Test
    fun `a request that failed frees its key`() = runBlocking<Unit> {
        val failed = failedAnswer("Сколько я трачу на Krabsbir?")
        backend.config = backend.config.copy(offline = true)
        assertFailsWith<AppError.Offline> { api.retry(key("r1"), failed.id) }
        backend.config = backend.config.copy(offline = false)
        assertIs<AnswerEvent.Done>(finish(api.retry(key("r1"), failed.id).answerId).last())
    }

    @Test
    fun `only a failed MODEL_ERROR answer can be retried`() = runBlocking<Unit> {
        val missing = assertFailsWith<AppError.Api> { api.retry(key("m"), "missing") }
        assertEquals(ErrorCodes.NOT_FOUND to 404, missing.code to missing.httpStatus)

        val cannot = failedAnswer("Погода", AnswerErrorCode.CANNOT_ANSWER)
        val e = assertFailsWith<AppError.Api> { api.retry(key("c"), cannot.id) }
        assertEquals(ErrorCodes.RETRY_NOT_ALLOWED to 422, e.code to e.httpStatus)

        val complete = ask("Сколько я трачу на Krabsbir?")
        finish(complete.answerId)
        assertEquals(ErrorCodes.RETRY_NOT_ALLOWED, assertFailsWith<AppError.Api> { api.retry(key("d"), complete.answerId) }.code)
    }

    @Test
    fun `retry needs the consent and a free attempt`() = runBlocking<Unit> {
        val failed = failedAnswer("Сколько я трачу на Krabsbir?")
        repeat(5) { finish(ask("Сколько я трачу на Krabsbir?", k = "q$it").answerId) }
        assertEquals(ErrorCodes.LIMIT_EXCEEDED, assertFailsWith<AppError.Api> { api.retry(key("r"), failed.id) }.code)

        backend.assistant.usage.clear()
        backend.profile = backend.profile.copy(assistantConsent = backend.profile.assistantConsent.copy(granted = false))
        assertEquals(ErrorCodes.CONSENT_REQUIRED, assertFailsWith<AppError.Api> { api.retry(key("r2"), failed.id) }.code)
        // Nothing was replaced by the rejected retries.
        assertEquals(false, failed.replaced)
    }

    @Test
    fun `history pages backwards from the newest message`() = runBlocking<Unit> {
        val ids = (1..3).map { i -> ask("Погода $i").also { finish(it.answerId) } }.flatMap { listOf(it.question.id, it.answerId) }
        val all = api.listMessages()
        assertEquals(ids, all.messages.map { it.id })
        assertEquals(false, all.hasOlder)

        val last = api.listMessages(limit = 2)
        assertEquals(ids.takeLast(2), last.messages.map { it.id })
        assertEquals(true, last.hasOlder)
        val before = api.listMessages(limit = 2, beforeId = last.messages.first().id)
        assertEquals(ids.subList(2, 4), before.messages.map { it.id })
        assertEquals(true, before.hasOlder)
        val first = api.listMessages(limit = 2, beforeId = before.messages.first().id)
        assertEquals(ids.take(2), first.messages.map { it.id })
        assertEquals(false, first.hasOlder)

        // Limit is clamped to 1..50; an unknown cursor means «from the newest».
        assertEquals(1, api.listMessages(limit = 0).messages.size)
        assertEquals(ids, api.listMessages(limit = 500).messages.map { it.id })
        assertEquals(ids.takeLast(2), api.listMessages(limit = 2, beforeId = "unknown").messages.map { it.id })
    }
}
