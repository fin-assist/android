package ru.finassist.pf.mock

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.mock.api.MockAssistantApi
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AssistantOffTopicApiTest {
    private val clock = TestClock("2026-10-01T12:00:00+03:00")
    private val request = AskRequest("What is the weather?")
    private val financial = AskRequest("Сколько я потратил?")

    private fun backend(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined): MockBackend =
        testBackend(clock, assistantConsent = true, dispatcher = dispatcher).apply {
            load(statement("2026-04-01", "2026-09-30", trn("expense", "2026-09-15T12:00", 100, "Магазин", "Продукты")))
        }

    private suspend fun terminal(api: MockAssistantApi, id: String) =
        withTimeout(10_000) { api.streamAnswer(id).toList().last() }

    @Test
    fun `fifth terminal off-topic blocks new ask without consuming daily questions or hiding history`() = runBlocking<Unit> {
        val backend = backend()
        try {
            val api = MockAssistantApi(backend)
            val first = api.ask(IdempotencyKey("off-0"), request)
            assertEquals(AnswerErrorCode.OFF_TOPIC, assertIs<AnswerEvent.Error>(terminal(api, first.answerId)).event.code)
            repeat(4) { api.ask(IdempotencyKey("off-${it + 1}"), request) }
            val blocked = kotlin.test.assertFailsWith<AppError.RateLimited> { api.ask(IdempotencyKey("rejected"), financial) }
            assertEquals(clock.at.plusDays(7), blocked.retryAt)
            assertEquals(7L * 24 * 60 * 60, blocked.retryAfterSeconds)
            assertEquals(5, api.getLimit().remaining)
            assertEquals(10, api.listMessages().messages.size)
            repeat(3) { terminal(api, first.answerId) }
            assertEquals(first, api.ask(IdempotencyKey("off-0"), request))
            assertEquals(10, api.listMessages().messages.size)
            assertFalse(backend.idempotency.containsKey("assistant.ask:rejected"))
            clock.at = clock.at.plusDays(7)
            val accepted = api.ask(IdempotencyKey("rejected"), financial)
            assertIs<AnswerEvent.Done>(terminal(api, accepted.answerId))
        } finally {
            backend.scope.cancel()
        }
    }

    @Test
    fun `blocked retry does not replace the failed answer or pin its key`() = runBlocking<Unit> {
        val backend = backend().apply { config = config.copy(assistantModelErrors = 1) }
        try {
            val api = MockAssistantApi(backend)
            val failed = api.ask(IdempotencyKey("model"), financial)
            assertEquals(AnswerErrorCode.MODEL_ERROR, assertIs<AnswerEvent.Error>(terminal(api, failed.answerId)).event.code)
            repeat(5) { api.ask(IdempotencyKey("off-$it"), request) }
            kotlin.test.assertFailsWith<AppError.RateLimited> { api.retry(IdempotencyKey("retry"), failed.answerId) }
            assertFalse(backend.assistant.answers.getValue(failed.answerId).replaced)
            assertTrue(api.listMessages().messages.any { it.id == failed.answerId })
            assertFalse(backend.idempotency.containsKey("assistant.retry:retry"))
            clock.at = clock.at.plusDays(7)
            val retry = api.retry(IdempotencyKey("retry"), failed.answerId)
            assertIs<AnswerEvent.Done>(terminal(api, retry.answerId))
            assertTrue(backend.assistant.answers.getValue(failed.answerId).replaced)
            assertEquals(retry, api.retry(IdempotencyKey("retry"), failed.answerId))
        } finally {
            backend.scope.cancel()
        }
    }

    @Test
    fun `in-flight successes can reset a block while existing streams stay available`() = runBlocking<Unit> {
        val dispatcher = QueuedDispatcher()
        val backend = backend(dispatcher)
        try {
            val api = MockAssistantApi(backend)
            repeat(4) {
                api.ask(IdempotencyKey("off-$it"), request)
                dispatcher.runFirst()
            }
            val success = api.ask(IdempotencyKey("success"), financial)
            api.ask(IdempotencyKey("off-4"), request)
            dispatcher.runLast()
            kotlin.test.assertFailsWith<AppError.RateLimited> { api.ask(IdempotencyKey("blocked"), financial) }
            assertEquals(4, api.getLimit().remaining)
            // The already admitted generation continues despite the admission block.
            dispatcher.runFirst()
            assertTrue(assertIs<AnswerEvent.Done>(terminal(api, success.answerId)).event.charged)
            val next = api.ask(IdempotencyKey("blocked"), request)
            dispatcher.runFirst()
            assertEquals(AnswerErrorCode.OFF_TOPIC, assertIs<AnswerEvent.Error>(terminal(api, next.answerId)).event.code)
        } finally {
            backend.scope.cancel()
        }
    }

    @Test
    fun `excess in-flight failures extend retry time and refund only their admission day`() = runBlocking<Unit> {
        val dispatcher = QueuedDispatcher()
        val backend = backend(dispatcher)
        try {
            val api = MockAssistantApi(backend)
            repeat(5) { api.ask(IdempotencyKey("old-$it"), request) }
            clock.at = clock.at.plusDays(1)
            repeat(2) { api.ask(IdempotencyKey("new-$it"), request) }
            val completedAt = clock.at
            repeat(7) {
                clock.at = completedAt.plusSeconds(it.toLong())
                dispatcher.runFirst()
            }
            val blocked = kotlin.test.assertFailsWith<AppError.RateLimited> { api.ask(IdempotencyKey("blocked"), financial) }
            assertEquals(completedAt.plusDays(7).plusSeconds(2), blocked.retryAt)
            assertEquals(5, api.getLimit().remaining)
            assertEquals(14, api.listMessages().messages.size)
        } finally {
            backend.scope.cancel()
        }
    }

    /** Runs individual admitted generations in a deterministic completion order. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runFirst() = queue.removeFirst().run()
        fun runLast() = queue.removeLast().run()
    }
}
