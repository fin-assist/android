package ru.finassist.pf.feature.assistant.impl.ui

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
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
import ru.finassist.pf.core.api.model.AnswerBlockEvent
import ru.finassist.pf.core.api.model.AnswerChunkEvent
import ru.finassist.pf.core.api.model.AnswerDoneEvent
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.AnswerErrorEvent
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AnswerSnapshotEvent
import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AskResponse
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.Chip
import ru.finassist.pf.core.api.model.ChipScreen
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.Message
import ru.finassist.pf.core.api.model.MessageRole
import ru.finassist.pf.core.api.model.MessagesList
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chat thread: history, sending, the answer stream, retries, the daily limit and the network. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatViewModelBehaviourTest {
    private val now = OffsetDateTime.parse("2026-10-08T12:00:00+03:00")
    private val clock = object : Clock {
        override fun now(): OffsetDateTime = this@ChatViewModelBehaviourTest.now
        override val zone: ZoneId = Clock.MOSCOW
    }
    private val dispatcher = UnconfinedTestDispatcher()
    private val limit = AssistantLimit(remaining = 4, dailyMax = 5, resetsAt = now.plusHours(12))

    private fun question(id: String, text: String = "Вопрос $id") =
        Message(id = id, role = MessageRole.USER, text = text, createdAt = now)

    private fun answer(id: String, status: AnswerStatus = AnswerStatus.COMPLETE, errorCode: AnswerErrorCode? = null) =
        Message(id = id, role = MessageRole.ASSISTANT, status = status, errorCode = errorCode, blocks = listOf(text("Ответ $id")), createdAt = now)

    private fun text(t: String) = Block.text(t)

    private fun askResponse(answerId: String = "a1", remaining: Int = 3) =
        AskResponse(question = question("q-$answerId", "Сколько я трачу на такси?"), answerId = answerId, remainingLimit = remaining)

    private class Recorded(val key: IdempotencyKey, val request: AskRequest)

    private class FakeApi : AssistantApi {
        /** Pages by `beforeId` (null — the latest page). */
        val pages = HashMap<String?, MessagesList>().apply { put(null, MessagesList(emptyList(), hasOlder = false)) }
        var listFailure: AppError? = null
        val listCalls = mutableListOf<String?>()

        /** Each `ask` takes the next outcome: an [AskResponse] or an [AppError] to throw. */
        val askOutcomes = ArrayDeque<Any>()
        val asks = mutableListOf<Recorded>()

        val retryOutcomes = ArrayDeque<Any>()
        val retries = mutableListOf<Pair<IdempotencyKey, String>>()
        /** When set, `retry` waits for it before answering: the request is still on its way. */
        var retryGate: CompletableDeferred<Unit>? = null

        /** Each opening of an answer's stream takes the next script; none left — a stream that stays open. */
        val streams = HashMap<String, ArrayDeque<Flow<AnswerEvent>>>()
        val streamOpens = mutableListOf<String>()

        var limitResult: AssistantLimit? = null

        override suspend fun listMessages(limit: Int?, beforeId: String?): MessagesList {
            listCalls += beforeId
            listFailure?.let { throw it }
            return pages.getValue(beforeId)
        }

        override suspend fun ask(key: IdempotencyKey, request: AskRequest): AskResponse {
            asks += Recorded(key, request)
            return when (val o = askOutcomes.removeFirst()) {
                is AppError -> throw o
                else -> o as AskResponse
            }
        }

        override fun streamAnswer(answerId: String): Flow<AnswerEvent> {
            streamOpens += answerId
            return streams[answerId]?.removeFirstOrNull() ?: flow { awaitCancellation() }
        }

        override suspend fun retry(key: IdempotencyKey, answerId: String): RetryResponse {
            retries += key to answerId
            retryGate?.await()
            return when (val o = retryOutcomes.removeFirst()) {
                is AppError -> throw o
                else -> o as RetryResponse
            }
        }

        override suspend fun getLimit(): AssistantLimit = limitResult ?: throw AppError.Offline()

        fun script(answerId: String, vararg flows: Flow<AnswerEvent>) {
            streams.getOrPut(answerId) { ArrayDeque() }.addAll(flows)
        }
    }

    private class RecordingTracker : Tracker {
        val events = mutableListOf<Pair<Event, Map<String, String>>>()
        override fun track(event: Event, params: Map<String, String>) {
            events += event to params
        }
        override fun setUserId(userId: String?) = Unit
        fun names() = events.map { it.first }
    }

    private val api = FakeApi().apply { limitResult = limit }
    private val tracker = RecordingTracker()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun newVm(savedState: SavedStateHandle = SavedStateHandle()) = ChatViewModel(savedState, api, tracker, clock)

    private fun advance(ms: Long) {
        dispatcher.scheduler.advanceTimeBy(ms)
        dispatcher.scheduler.runCurrent()
    }

    private fun ChatViewModel.answerItem(id: String) = state.value.items.filterIsInstance<ChatItem.Answer>().single { it.id == id }

    private fun events(vararg e: AnswerEvent): Flow<AnswerEvent> = flow { e.forEach { emit(it) } }

    private fun done(remaining: Int, charged: Boolean = true) = AnswerEvent.Done(AnswerDoneEvent(charged, remaining))

    private fun sendQuestion(vm: ChatViewModel, text: String = "Сколько я трачу на такси?") {
        vm.setDraft(text)
        vm.send()
    }

    // ---- history ----

    @Test fun `opening the chat loads the thread and the limit`() {
        api.pages[null] = MessagesList(
            listOf(
                question("q1"),
                answer("a1"),
                // Unknown role is dropped, an unknown status reads as failed, a duplicate id is shown once.
                Message(id = "x", role = MessageRole.UNKNOWN, createdAt = now),
                answer("a2", status = AnswerStatus.UNKNOWN, errorCode = AnswerErrorCode.UNKNOWN),
                answer("a1"),
            ),
            hasOlder = true,
        )
        val vm = newVm()
        val s = vm.state.value
        assertFalse(s.loading)
        assertFalse(s.loadFailed)
        assertTrue(s.hasOlder)
        assertEquals(listOf("q1", "a1", "a2"), s.items.map { it.id })
        assertEquals("Вопрос q1", assertIs<ChatItem.Question>(s.items[0]).text)
        val failed = vm.answerItem("a2")
        assertEquals(AnswerStatus.FAILED, failed.status)
        assertEquals(AnswerErrorCode.MODEL_ERROR, failed.errorCode)
        assertEquals(limit, s.limit)
        assertEquals(Events.ASSISTANT_OPENED, tracker.names().first())
    }

    @Test fun `the thread failing to load offline shows the offline screen`() {
        api.listFailure = AppError.Offline()
        val s = newVm().state.value
        assertFalse(s.loading)
        assertTrue(s.loadFailed)
        assertTrue(s.offline)
    }

    @Test fun `a server failure on the first load is a load error, not offline`() {
        api.listFailure = AppError.Server(503)
        val s = newVm().state.value
        assertTrue(s.loadFailed)
        assertFalse(s.offline)
    }

    @Test fun `a failed reload keeps the shown thread`() {
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a1")), hasOlder = false)
        val vm = newVm()
        api.listFailure = AppError.Offline()
        vm.load()
        assertFalse(vm.state.value.loadFailed)
        assertEquals(listOf("q1", "a1"), vm.state.value.items.map { it.id })
    }

    @Test fun `an unknown limit leaves the counter hidden`() {
        api.limitResult = null
        val s = newVm().state.value
        assertNull(s.limit)
        assertFalse(s.limitExhausted)
    }

    @Test fun `older messages are prepended before the first one`() {
        api.pages[null] = MessagesList(listOf(question("q2"), answer("a2")), hasOlder = true)
        api.pages["q2"] = MessagesList(listOf(question("q1"), answer("a1"), question("q2")), hasOlder = false)
        val vm = newVm()
        vm.loadOlder()
        assertEquals(listOf<String?>(null, "q2"), api.listCalls)
        assertEquals(listOf("q1", "a1", "q2", "a2"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.hasOlder)
        assertFalse(vm.state.value.loadingOlder)

        // Nothing older left: no request.
        vm.loadOlder()
        assertEquals(2, api.listCalls.size)
    }

    @Test fun `a failed page of older messages keeps the thread`() {
        api.pages[null] = MessagesList(listOf(question("q2")), hasOlder = true)
        val vm = newVm()
        api.listFailure = AppError.Offline()
        vm.loadOlder()
        assertFalse(vm.state.value.loadingOlder)
        assertTrue(vm.state.value.hasOlder)
        assertEquals(listOf("q2"), vm.state.value.items.map { it.id })
    }

    @Test fun `an empty thread has nothing older to load`() {
        val vm = newVm()
        vm.loadOlder()
        assertEquals(listOf<String?>(null), api.listCalls)
    }

    @Test fun `an answer still generating in the history is watched on open`() {
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a1", status = AnswerStatus.GENERATING)), hasOlder = false)
        api.script(
            "a1",
            events(AnswerEvent.Snapshot(AnswerSnapshotEvent(AnswerStatus.COMPLETE, listOf(text("Готово")), emptyList()))),
        )
        val vm = newVm()
        assertEquals(listOf("a1"), api.streamOpens)
        val a = vm.answerItem("a1")
        assertEquals(AnswerStatus.COMPLETE, a.status)
        assertEquals(listOf(text("Готово")), a.blocks)
        assertFalse(vm.state.value.generating)
    }

    // ---- composer and sending ----

    @Test fun `the draft is cut to 2000 characters`() {
        val vm = newVm()
        vm.setDraft("я".repeat(2500))
        assertEquals(2000, vm.state.value.draft.length)
    }

    @Test fun `a blank draft is not sent`() {
        val vm = newVm()
        vm.setDraft("   ")
        vm.send()
        assertTrue(api.asks.isEmpty())
    }

    @Test fun `a sent question appears with a generating answer and the draft clears`() {
        api.askOutcomes += askResponse("a1", remaining = 3)
        val vm = newVm(SavedStateHandle(mapOf("transferMode" to "without")))
        sendQuestion(vm, "  Сколько я трачу на такси?  ")

        val sent = api.asks.single()
        assertEquals(AskRequest("Сколько я трачу на такси?", TransferMode.WITHOUT), sent.request)
        val s = vm.state.value
        assertFalse(s.sending)
        assertEquals("", s.draft)
        assertEquals(listOf("q-a1", "a1"), s.items.map { it.id })
        assertEquals(AnswerStatus.GENERATING, vm.answerItem("a1").status)
        assertTrue(s.generating)
        assertEquals(3, s.limit!!.remaining)
        assertEquals(listOf("a1"), api.streamOpens)
        assertTrue(Events.ASSISTANT_QUESTION_SENT in tracker.names())
    }

    @Test fun `an unknown transfer mode in the route is sent as with`() {
        api.askOutcomes += askResponse()
        val vm = newVm(SavedStateHandle(mapOf("transferMode" to "both")))
        sendQuestion(vm)
        assertEquals(TransferMode.WITH, api.asks.single().request.transferMode)
    }

    @Test fun `nothing is sent while an answer is generating`() {
        api.askOutcomes += askResponse("a1")
        val vm = newVm()
        sendQuestion(vm)
        sendQuestion(vm, "Ещё вопрос")
        assertEquals(1, api.asks.size)
        assertEquals("Ещё вопрос", vm.state.value.draft)
    }

    @Test fun `asking the same text again after an answer is a new action with a new key`() {
        api.askOutcomes += askResponse("a1")
        api.askOutcomes += askResponse("a2")
        api.script("a1", events(done(3)))
        val vm = newVm()
        sendQuestion(vm)
        sendQuestion(vm)
        assertEquals(2, api.asks.size)
        assertTrue(api.asks[0].key != api.asks[1].key)
    }

    @Test fun `the answer is assembled from the stream and completes`() {
        api.askOutcomes += askResponse("a1", remaining = 3)
        val source = AnswerSource(calculatedAt = now, transferMode = TransferMode.WITH, changedSince = emptyList())
        val chip = Chip(label = "Операции", screen = ChipScreen.OPERATIONS)
        val rows = Block(type = BlockType.ROWS, title = "Такси")
        api.script(
            "a1",
            events(
                AnswerEvent.Snapshot(AnswerSnapshotEvent(AnswerStatus.GENERATING, emptyList(), emptyList())),
                AnswerEvent.Chunk(AnswerChunkEvent(0, "За месяц ")),
                AnswerEvent.Chunk(AnswerChunkEvent(0, "4 200 ₽.")),
                AnswerEvent.BlockAdded(AnswerBlockEvent(1, rows)),
                AnswerEvent.Chunk(AnswerChunkEvent(2, "Это больше обычного.")),
                AnswerEvent.Source(source),
                AnswerEvent.ChipAdded(chip),
                AnswerEvent.Unknown("thinking"),
                done(remaining = 2, charged = true),
            ),
        )
        val vm = newVm()
        sendQuestion(vm)

        val a = vm.answerItem("a1")
        assertEquals(AnswerStatus.COMPLETE, a.status)
        assertEquals(listOf(text("За месяц 4 200 ₽."), rows, text("Это больше обычного.")), a.blocks)
        assertEquals(source, a.source)
        assertEquals(listOf(chip), a.chips)
        assertEquals(2, vm.state.value.limit!!.remaining)
        assertFalse(vm.state.value.generating)
        assertEquals(mapOf("charged" to "true"), tracker.events.single { it.first == Events.ASSISTANT_ANSWER_DONE }.second)
        // Terminal: the stream is not re-opened.
        advance(60_000)
        assertEquals(listOf("a1"), api.streamOpens)
    }

    @Test fun `a block re-sent at an index replaces the blocks from there`() {
        api.askOutcomes += askResponse("a1")
        api.script(
            "a1",
            events(
                AnswerEvent.Chunk(AnswerChunkEvent(0, "Первый")),
                AnswerEvent.Chunk(AnswerChunkEvent(1, "Второй")),
                AnswerEvent.BlockAdded(AnswerBlockEvent(1, text("Заменён"))),
            ),
        )
        val vm = newVm()
        sendQuestion(vm)
        assertEquals(listOf(text("Первый"), text("Заменён")), vm.answerItem("a1").blocks)
    }

    @Test fun `an answer error removes the shown blocks and updates the limit`() {
        api.askOutcomes += askResponse("a1", remaining = 3)
        api.script(
            "a1",
            events(
                AnswerEvent.Chunk(AnswerChunkEvent(0, "Частичный ответ")),
                AnswerEvent.ChipAdded(Chip(label = "Аналитика", screen = ChipScreen.ANALYTICS)),
                AnswerEvent.Error(AnswerErrorEvent(AnswerErrorCode.CANNOT_ANSWER, "no", remainingLimit = 3)),
            ),
        )
        val vm = newVm()
        sendQuestion(vm)
        val a = vm.answerItem("a1")
        assertEquals(AnswerStatus.FAILED, a.status)
        assertEquals(AnswerErrorCode.CANNOT_ANSWER, a.errorCode)
        assertTrue(a.blocks.isEmpty())
        assertTrue(a.chips.isEmpty())
        assertEquals(3, vm.state.value.limit!!.remaining)
        assertEquals(mapOf("code" to "CANNOT_ANSWER"), tracker.events.single { it.first == Events.ASSISTANT_ANSWER_FAILED }.second)
    }

    @Test fun `an unknown error code in the stream offers a retry like MODEL_ERROR`() {
        api.askOutcomes += askResponse("a1")
        api.script("a1", events(AnswerEvent.Error(AnswerErrorEvent(AnswerErrorCode.UNKNOWN, "?", remainingLimit = 3))))
        val vm = newVm()
        sendQuestion(vm)
        assertEquals(AnswerErrorCode.MODEL_ERROR, vm.answerItem("a1").errorCode)
    }

    // ---- send failures ----

    @Test fun `no consent opens the consent screen and the question is re-sent with its key after it`() {
        api.askOutcomes += AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403)
        api.askOutcomes += askResponse("a1")
        val vm = newVm()
        sendQuestion(vm)
        assertEquals(ChatEvent.OPEN_CONSENT, vm.state.value.event)
        assertFalse(vm.state.value.sending)
        assertEquals("Сколько я трачу на такси?", vm.state.value.draft)
        vm.consumeEvent()
        assertNull(vm.state.value.event)

        vm.onConsentGranted()
        assertEquals(2, api.asks.size)
        assertEquals(api.asks[0].key, api.asks[1].key)
        assertEquals(listOf("q-a1", "a1"), vm.state.value.items.map { it.id })
        assertTrue(Events.ASSISTANT_CONSENT_GRANTED in tracker.names())
    }

    @Test fun `consent granted with nothing waiting sends nothing`() {
        val vm = newVm()
        vm.onConsentGranted()
        assertTrue(api.asks.isEmpty())
    }

    @Test fun `the daily limit hit zeroes the known limit and drops the key`() {
        api.askOutcomes += AppError.Api(ErrorCodes.LIMIT_EXCEEDED, 429)
        api.askOutcomes += askResponse("a1")
        val vm = newVm()
        sendQuestion(vm)
        assertEquals(limit.copy(remaining = 0), vm.state.value.limit)
        assertTrue(vm.state.value.limitExhausted)

        // The same text later is a new action: the server did not store the 429.
        vm.send()
        assertEquals(2, api.asks.size)
        assertTrue(api.asks[0].key != api.asks[1].key)
    }

    @Test fun `the limit hit without a known limit resets at resets_at from the error`() {
        api.limitResult = null
        val resetsAt = OffsetDateTime.parse("2026-10-09T00:00:00+03:00")
        api.askOutcomes += AppError.Api(ErrorCodes.LIMIT_EXCEEDED, 429, resetsAt = resetsAt)
        val vm = newVm()
        sendQuestion(vm)
        assertEquals(AssistantLimit(0, 5, resetsAt), vm.state.value.limit)
    }

    @Test fun `offline keeps the question and the retry re-sends it with the same key`() {
        api.askOutcomes += AppError.Offline()
        api.askOutcomes += askResponse("a1")
        val vm = newVm()
        sendQuestion(vm)
        assertTrue(vm.state.value.offline)
        assertFalse(vm.state.value.sendFailed)
        assertEquals("Сколько я трачу на такси?", vm.state.value.draft)

        vm.retryAfterFailure()
        assertFalse(vm.state.value.offline)
        assertEquals(2, api.asks.size)
        assertEquals(api.asks[0].key, api.asks[1].key)
        assertEquals("", vm.state.value.draft)
    }

    @Test fun `any other failure shows the retry notice`() {
        api.askOutcomes += AppError.Server(500)
        val vm = newVm()
        sendQuestion(vm)
        assertTrue(vm.state.value.sendFailed)
        assertFalse(vm.state.value.offline)
        assertFalse(vm.state.value.sending)
    }

    @Test fun `a request in progress is repeated with the same key after retry_after`() {
        api.askOutcomes += AppError.InProgress(retryAfterSeconds = 3)
        api.askOutcomes += AppError.InProgress(retryAfterSeconds = null)
        api.askOutcomes += askResponse("a1")
        val vm = newVm()
        sendQuestion(vm)
        assertTrue(vm.state.value.sending)
        advance(2_999)
        assertEquals(1, api.asks.size)
        advance(1)
        assertEquals(2, api.asks.size)
        // No Retry-After: two seconds.
        advance(2_000)
        assertEquals(3, api.asks.size)
        assertEquals(1, api.asks.map { it.key }.distinct().size)
        assertFalse(vm.state.value.sending)
        assertEquals(listOf("q-a1", "a1"), vm.state.value.items.map { it.id })
    }

    @Test fun `a question still in progress after the retries ends in the retry notice`() {
        repeat(4) { api.askOutcomes += AppError.InProgress(retryAfterSeconds = 1) }
        val vm = newVm()
        sendQuestion(vm)
        advance(3_000)
        assertEquals(4, api.asks.size)
        assertTrue(vm.state.value.sendFailed)
    }

    @Test fun `a question left unsent before process death is restored and sent with its key`() {
        api.askOutcomes += askResponse("a1")
        val saved = SavedStateHandle(mapOf("chat.pending.text" to "Сколько я трачу на такси?", "chat.pending.key" to "k-1"))
        val vm = newVm(saved)
        assertEquals("Сколько я трачу на такси?", vm.state.value.draft)
        vm.send()
        assertEquals(IdempotencyKey("k-1"), api.asks.single().key)
        // A final response ends the action.
        assertNull(saved.get<String>("chat.pending.key"))
    }

    @Test fun `editing the waiting question makes it a new action`() {
        api.askOutcomes += AppError.Offline()
        api.askOutcomes += askResponse("a1")
        val vm = newVm()
        sendQuestion(vm, "Первый текст")
        sendQuestion(vm, "Другой текст")
        assertTrue(api.asks[0].key != api.asks[1].key)
    }

    @Test fun `retry after a failed first load reloads the thread`() {
        api.listFailure = AppError.Offline()
        val vm = newVm()
        api.listFailure = null
        api.pages[null] = MessagesList(listOf(question("q1")), hasOlder = false)
        vm.retryAfterFailure()
        assertFalse(vm.state.value.loadFailed)
        assertEquals(listOf("q1"), vm.state.value.items.map { it.id })
    }

    // ---- «Повторить» under a failed answer ----

    private fun vmWithFailedAnswer(): ChatViewModel {
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a1", AnswerStatus.FAILED, AnswerErrorCode.MODEL_ERROR)), hasOlder = false)
        return newVm()
    }

    @Test fun `retry replaces the failed answer with a new generating one`() {
        api.retryOutcomes += RetryResponse("a2", remainingLimit = 2)
        val vm = vmWithFailedAnswer()
        vm.retry("a1")
        assertEquals(listOf("q1", "a2"), vm.state.value.items.map { it.id })
        assertEquals(AnswerStatus.GENERATING, vm.answerItem("a2").status)
        assertEquals(2, vm.state.value.limit!!.remaining)
        assertEquals(listOf("a2"), api.streamOpens)
    }

    @Test fun `a failed retry keeps its key for the next tap`() {
        api.retryOutcomes += AppError.Offline()
        api.retryOutcomes += RetryResponse("a2", remainingLimit = 2)
        val vm = vmWithFailedAnswer()
        vm.retry("a1")
        assertTrue(vm.state.value.offline)
        vm.retry("a1")
        assertEquals(api.retries[0].first, api.retries[1].first)
        assertEquals(listOf("q1", "a2"), vm.state.value.items.map { it.id })
    }

    @Test fun `a second tap while the retry is on its way sends nothing`() {
        api.retryGate = CompletableDeferred()
        api.retryOutcomes += RetryResponse("a2", remainingLimit = 2)
        val vm = vmWithFailedAnswer()
        vm.retry("a1")
        vm.retry("a1")
        assertEquals(1, api.retries.size)

        api.retryGate!!.complete(Unit)
        assertEquals(listOf("q1", "a2"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.sendFailed)
    }

    @Test fun `a retry can be tapped again once the first one has failed`() {
        api.retryGate = CompletableDeferred()
        api.retryOutcomes += AppError.Server(500)
        api.retryOutcomes += RetryResponse("a2", remainingLimit = 2)
        val vm = vmWithFailedAnswer()
        vm.retry("a1")
        api.retryGate!!.complete(Unit)
        assertTrue(vm.state.value.sendFailed)
        vm.retry("a1")
        assertEquals(2, api.retries.size)
        assertEquals(listOf("q1", "a2"), vm.state.value.items.map { it.id })
    }

    @Test fun `a retry in progress is repeated with the same key after retry_after, not shown as a failure`() {
        api.retryOutcomes += AppError.InProgress(retryAfterSeconds = 3)
        api.retryOutcomes += RetryResponse("a2", remainingLimit = 2)
        val vm = vmWithFailedAnswer()
        vm.retry("a1")
        assertFalse(vm.state.value.sendFailed)
        // Waiting out Retry-After is still the same tap: another one sends nothing.
        vm.retry("a1")
        advance(2_999)
        assertEquals(1, api.retries.size)
        advance(1)
        assertEquals(2, api.retries.size)
        assertEquals(api.retries[0].first, api.retries[1].first)
        assertFalse(vm.state.value.sendFailed)
        assertEquals(listOf("q1", "a2"), vm.state.value.items.map { it.id })
    }

    @Test fun `a retry still in progress after the retries ends in the retry notice`() {
        repeat(4) { api.retryOutcomes += AppError.InProgress(retryAfterSeconds = 1) }
        val vm = vmWithFailedAnswer()
        vm.retry("a1")
        advance(3_000)
        assertEquals(4, api.retries.size)
        assertTrue(vm.state.value.sendFailed)
        assertEquals(AnswerStatus.FAILED, vm.answerItem("a1").status)
    }

    @Test fun `retry over the limit zeroes the counter`() {
        api.retryOutcomes += AppError.Api(ErrorCodes.LIMIT_EXCEEDED, 429)
        val vm = vmWithFailedAnswer()
        vm.retry("a1")
        assertTrue(vm.state.value.limitExhausted)
        assertEquals(AnswerStatus.FAILED, vm.answerItem("a1").status)
    }

    @Test fun `retry without consent opens the consent screen`() {
        api.retryOutcomes += AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403)
        val vm = vmWithFailedAnswer()
        vm.retry("a1")
        assertEquals(ChatEvent.OPEN_CONSENT, vm.state.value.event)
    }

    @Test fun `retry of an answer already replaced reloads the thread with a new key next time`() {
        api.retryOutcomes += AppError.Api(ErrorCodes.RETRY_NOT_ALLOWED, 409)
        api.retryOutcomes += AppError.Server(500)
        val vm = vmWithFailedAnswer()
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a2")), hasOlder = false)
        vm.retry("a1")
        assertEquals(2, api.listCalls.size)
        assertEquals(listOf("q1", "a2"), vm.state.value.items.map { it.id })

        vm.retry("a1")
        assertTrue(api.retries[0].first != api.retries[1].first)
        assertTrue(vm.state.value.sendFailed)
    }

    // ---- the answer stream ----

    @Test fun `a dropped stream is re-opened with backoff and the snapshot restores the answer`() {
        api.askOutcomes += askResponse("a1")
        api.script(
            "a1",
            flow { throw AppError.Offline() },
            flow { throw AppError.Server(502) },
            // Closed without a terminal event (server restart): still generating.
            emptyFlow(),
            events(AnswerEvent.Snapshot(AnswerSnapshotEvent(AnswerStatus.COMPLETE, listOf(text("Ответ")), emptyList()))),
        )
        val vm = newVm()
        sendQuestion(vm)
        assertEquals(1, api.streamOpens.size)
        advance(1_000)
        assertEquals(2, api.streamOpens.size)
        advance(1_999)
        assertEquals(2, api.streamOpens.size)
        advance(1)
        assertEquals(3, api.streamOpens.size)
        advance(4_000)
        assertEquals(4, api.streamOpens.size)
        assertEquals(AnswerStatus.COMPLETE, vm.answerItem("a1").status)
        assertFalse(vm.state.value.offline)
        advance(60_000)
        assertEquals(4, api.streamOpens.size)
    }

    @Test fun `an event resets the backoff`() {
        api.askOutcomes += askResponse("a1")
        api.script(
            "a1",
            flow { throw AppError.Offline() },
            flow {
                emit(AnswerEvent.Chunk(AnswerChunkEvent(0, "Начало")))
                throw AppError.Offline()
            },
        )
        val vm = newVm()
        sendQuestion(vm)
        advance(1_000)
        assertEquals(2, api.streamOpens.size)
        // Backoff was 2 s after the first drop, back to 1 s after the event.
        advance(1_000)
        assertEquals(3, api.streamOpens.size)
        assertEquals(listOf(text("Начало")), vm.answerItem("a1").blocks)
    }

    @Test fun `a stream error other than the network stops watching and the history gives the answer its status`() {
        api.askOutcomes += askResponse("a1")
        api.askOutcomes += askResponse("a2")
        api.script("a1", flow { throw AppError.Api(ErrorCodes.NOT_FOUND, 404) })
        val vm = newVm()
        api.pages[null] = MessagesList(listOf(question("q-a1"), answer("a1", AnswerStatus.FAILED, AnswerErrorCode.MODEL_ERROR)), hasOlder = false)
        sendQuestion(vm)
        advance(60_000)
        assertEquals(listOf("a1"), api.streamOpens)
        // FIN-75: the history was re-read, the answer is no longer «generating» and the composer is free.
        assertEquals(2, api.listCalls.size)
        assertEquals(AnswerStatus.FAILED, vm.answerItem("a1").status)
        assertFalse(vm.state.value.generating)
        sendQuestion(vm, "Ещё вопрос")
        assertEquals(2, api.asks.size)
    }

    @Test fun `a stream error is not retried in a loop when the history still says generating`() {
        api.askOutcomes += askResponse("a1")
        api.script("a1", flow { throw AppError.Api(ErrorCodes.NOT_FOUND, 404) })
        val vm = newVm()
        api.pages[null] = MessagesList(listOf(question("q-a1"), answer("a1", AnswerStatus.GENERATING)), hasOlder = false)
        sendQuestion(vm)
        advance(60_000)
        assertEquals(listOf("a1"), api.streamOpens)
        assertEquals(2, api.listCalls.size)
        // Re-opened on the next resume.
        vm.resumeStreams()
        assertEquals(listOf("a1", "a1"), api.streamOpens)
    }

    @Test fun `a failed reload after a stream error keeps the thread`() {
        api.askOutcomes += askResponse("a1")
        api.script("a1", flow { throw AppError.Api(ErrorCodes.NOT_FOUND, 404) })
        val vm = newVm()
        api.listFailure = AppError.Server(500)
        sendQuestion(vm)
        assertEquals(2, api.listCalls.size)
        assertEquals(listOf("q-a1", "a1"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.loadFailed)
    }

    @Test fun `streams stop on pause and are re-opened once on resume`() {
        val live = MutableSharedFlow<AnswerEvent>(extraBufferCapacity = 1)
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a1", status = AnswerStatus.GENERATING)), hasOlder = false)
        api.script("a1", flow { awaitCancellation() }, live)
        val vm = newVm()
        // Already watched: a second resume does not open a duplicate stream.
        vm.resumeStreams()
        assertEquals(1, api.streamOpens.size)

        vm.pauseStreams()
        vm.resumeStreams()
        assertEquals(2, api.streamOpens.size)
        live.tryEmit(done(remaining = 3))
        assertEquals(AnswerStatus.COMPLETE, vm.answerItem("a1").status)
    }
}
