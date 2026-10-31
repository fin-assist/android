package ru.finassist.pf.feature.assistant.impl.ui

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AskResponse
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.Message
import ru.finassist.pf.core.api.model.MessageRole
import ru.finassist.pf.core.api.model.MessagesList
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.tracking.NoopTracker
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatRefreshTest {
    private val scheduler = TestCoroutineScheduler()
    private val now = OffsetDateTime.parse("2026-10-08T12:00:00+03:00")
    private val clock = object : Clock {
        override val zone = Clock.MOSCOW
        override fun now() = this@ChatRefreshTest.now
    }
    private inner class Api : AssistantApi {
        var reads = 0
        var limitReads = 0
        var asks = 0
        var retries = 0
        var history = MessagesList(listOf(Message("q1", MessageRole.USER, text = "Existing question", createdAt = now)), hasOlder = false)
        var historyReply: (suspend () -> MessagesList)? = null
        var quota = AssistantLimit(4, 5, now.plusDays(1))
        var limitFailure: AppError? = null
        var limitReply: (suspend () -> AssistantLimit)? = null
        var answerStream: (() -> Flow<AnswerEvent>)? = null
        override suspend fun listMessages(limit: Int?, beforeId: String?): MessagesList {
            reads++
            return historyReply?.invoke() ?: history
        }
        override suspend fun getLimit(): AssistantLimit {
            limitReads++
            limitReply?.let { return it() }
            limitFailure?.let { throw it }
            return quota
        }
        override suspend fun ask(key: IdempotencyKey, request: AskRequest): AskResponse {
            asks++
            error("refresh must not ask")
        }
        override suspend fun retry(key: IdempotencyKey, answerId: String): RetryResponse {
            retries++
            error("refresh must not retry")
        }
        override fun streamAnswer(answerId: String): Flow<AnswerEvent> = answerStream?.invoke() ?: error("no generating answer")
    }
    private val api = Api()
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    @After fun tearDown() = Dispatchers.resetMain()
    private fun vm() = ChatViewModel(SavedStateHandle(), api, NoopTracker, clock,
        ElapsedClock { scheduler.currentTime }, ConsentProfileFake())

    @Test fun `pull refresh deduplicates reads preserves draft and updates history and quota without posts`() {
        val vm = vm()
        vm.setDraft("Unsent draft")
        val pending = CompletableDeferred<MessagesList>()
        api.historyReply = { pending.await() }
        api.quota = api.quota.copy(remaining = 2)
        vm.refresh()
        vm.refresh()
        vm.load()
        assertEquals(2, api.reads)
        assertTrue(vm.state.value.refreshing)
        assertEquals("Unsent draft", vm.state.value.draft)
        assertEquals("q1", vm.state.value.items.single().id)
        pending.complete(MessagesList(emptyList(), hasOlder = false))
        assertFalse(vm.state.value.refreshing)
        assertEquals("Unsent draft", vm.state.value.draft)
        assertEquals(2, vm.state.value.limit?.remaining)
        assertEquals(2, api.limitReads)
        assertEquals(0, api.asks)
        assertEquals(0, api.retries)
    }

    @Test fun `failed history refresh retains conversation and editable draft but still refreshes quota`() {
        val vm = vm()
        vm.setDraft("Keep me")
        val shown = vm.state.value.items
        api.historyReply = { throw AppError.Offline() }
        api.quota = api.quota.copy(remaining = 1)
        vm.refresh()
        assertFalse(vm.state.value.refreshing)
        assertFalse(vm.state.value.offline, "read failure must not replace the draft with a send retry")
        assertEquals("Keep me", vm.state.value.draft)
        assertEquals(shown, vm.state.value.items)
        assertTrue(vm.state.value.loadErrorPresentation?.connectionFailure == true)
        assertEquals(1, vm.state.value.limit?.remaining)
        assertEquals(0, api.asks)
        assertEquals(0, api.retries)
    }

    @Test fun `refresh waits retry after and keeps the exact prior question history`() {
        val vm = vm()
        api.historyReply = { throw AppError.RateLimited(null, 2) }
        vm.refresh()
        vm.refresh()
        assertEquals(2, api.reads)
        assertFalse(vm.state.value.loadRetryReady)
        scheduler.advanceTimeBy(2000)
        scheduler.runCurrent()
        api.historyReply = null
        vm.refresh()
        assertEquals(3, api.reads)
        assertEquals("q1", vm.state.value.items.single().id)
        assertEquals(0, api.asks)
    }

    @Test fun `quota refresh failure preserves previous quota and exposes its own connection message`() {
        val vm = vm()
        val quota = vm.state.value.limit
        api.limitFailure = AppError.Offline()
        vm.refresh()
        assertEquals(quota, vm.state.value.limit)
        assertTrue(vm.state.value.limitErrorPresentation?.connectionFailure == true)
        assertFalse(vm.state.value.refreshing)
    }
    @Test fun `old refresh quota completion cannot release a newer stream reconciliation read`() {
        val generating = Message("a1", MessageRole.ASSISTANT, status = AnswerStatus.GENERATING, createdAt = now)
        api.history = api.history.copy(messages = api.history.messages + generating)
        val failStream = CompletableDeferred<Unit>()
        api.answerStream = { flow { failStream.await(); throw AppError.Api("NOT_FOUND", 404) } }
        val vm = vm()
        val oldQuota = CompletableDeferred<AssistantLimit>()
        api.limitReply = { oldQuota.await() }
        vm.refresh()
        assertTrue(vm.state.value.refreshing)
        val newerHistory = CompletableDeferred<MessagesList>()
        api.historyReply = { newerHistory.await() }
        failStream.complete(Unit)
        assertEquals(3, api.reads)
        oldQuota.complete(api.quota)
        assertTrue(vm.state.value.refreshing)
        vm.refresh()
        assertEquals(3, api.reads, "the newer pending history still owns the refresh guard")
        newerHistory.complete(MessagesList(listOf(generating.copy(status = AnswerStatus.COMPLETE)), hasOlder = false))
        assertFalse(vm.state.value.refreshing)
        assertEquals(0, api.asks)
        assertEquals(0, api.retries)
    }

}
