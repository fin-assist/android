package ru.finassist.pf.feature.assistant.impl.ui

import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorResponseInfo
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.ResponseTiming
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
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
    private val profileApi = ConsentProfileFake()

    private fun question(id: String, text: String = "Вопрос $id") =
        Message(id = id, role = MessageRole.USER, text = text, createdAt = now)

    private fun answer(
        id: String,
        status: AnswerStatus = AnswerStatus.COMPLETE,
        errorCode: AnswerErrorCode? = null,
        errorText: String? = null,
        retryable: Boolean? = null,
    ) = Message(
        id = id, role = MessageRole.ASSISTANT, status = status, errorCode = errorCode, errorText = errorText, retryable = retryable,
        blocks = listOf(text("Ответ $id")), createdAt = now,
    )

    private fun text(t: String) = Block.text(t)

    private fun askResponse(answerId: String = "a1", remaining: Int = 3) =
        AskResponse(question = question("q-$answerId", "Сколько я трачу на такси?"), answerId = answerId, remainingLimit = remaining)

    private class Recorded(val key: IdempotencyKey, val request: AskRequest)

    private class FakeApi : AssistantApi {
        /** Pages by `beforeId` (null — the latest page). */
        val pages = HashMap<String?, MessagesList>().apply { put(null, MessagesList(emptyList(), hasOlder = false)) }
        var listFailure: AppError? = null
        var listGate: CompletableDeferred<Unit>? = null
        val listCalls = mutableListOf<String?>()

        /** Each `ask` takes the next outcome: an [AskResponse] or an [AppError] to throw. */
        val askOutcomes = ArrayDeque<Any>()
        val asks = mutableListOf<Recorded>()
        var askGate: CompletableDeferred<Unit>? = null

        val retryOutcomes = ArrayDeque<Any>()
        val retries = mutableListOf<Pair<IdempotencyKey, String>>()
        /** When set, `retry` waits for it before answering: the request is still on its way. */
        var retryGate: CompletableDeferred<Unit>? = null

        /** Each opening of an answer's stream takes the next script; none left — a stream that stays open. */
        val streams = HashMap<String, ArrayDeque<Flow<AnswerEvent>>>()
        val streamOpens = mutableListOf<String>()

        var limitResult: AssistantLimit? = null
        var limitReply: (suspend () -> AssistantLimit)? = null

        override suspend fun listMessages(limit: Int?, beforeId: String?): MessagesList {
            listCalls += beforeId
            val failure = listFailure
            val response = if (failure == null) pages.getValue(beforeId) else null
            listGate?.await()
            failure?.let { throw it }
            return checkNotNull(response)
        }

        override suspend fun ask(key: IdempotencyKey, request: AskRequest): AskResponse {
            asks += Recorded(key, request)
            askGate?.await()
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

        override suspend fun getLimit(): AssistantLimit = limitReply?.invoke() ?: limitResult ?: throw AppError.Offline()

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

    private fun newVm(savedState: SavedStateHandle = SavedStateHandle()) = ChatViewModel(savedState, api, tracker, clock, ElapsedClock { dispatcher.scheduler.currentTime }, profileApi)

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
                // Unknown status shares safe text fallback, but does not supply a known code or new-attempt policy.
                answer("a2", status = AnswerStatus.UNKNOWN, errorCode = AnswerErrorCode.MODEL_ERROR, errorText = "x", retryable = true),
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
        assertEquals("x", failed.failureText)
        assertNull(failed.errorCode)
        assertFalse(failed.retryable)
        assertEquals(limit, s.limit)
        assertEquals(Events.ASSISTANT_OPENED, tracker.names().first())
    }

    @Test fun `unknown history status preserves canonical warning without granting code actions or a new attempt`() {
        api.pages[null] = MessagesList(listOf(answer("a1", AnswerStatus.UNKNOWN, AnswerErrorCode.MODEL_ERROR,
            errorText = "Прежний текст", retryable = true).copy(
                errorUserMessage = UserMessage.from("Ответ пока недоступен"), errorSeverity = ErrorSeverity.WARNING,
                errorRawCode = ErrorCodes.CONSENT_REQUIRED,
            )), hasOlder = false)
        val vm = newVm()
        val shown = vm.answerItem("a1")
        assertEquals(AnswerStatus.FAILED, shown.status)
        assertEquals("Ответ пока недоступен", shown.failureText)
        assertEquals(ErrorSeverity.WARNING, shown.errorPresentation.severity)
        assertNull(shown.errorCode)
        assertFalse(shown.retryable)
        assertNull(vm.state.value.event)
        assertTrue(api.retries.isEmpty())
    }

    @Test fun `missing history status preserves canonical presentation and never infers legacy retry`() {
        api.pages[null] = MessagesList(listOf(Message("a1", MessageRole.ASSISTANT, status = null,
            errorCode = AnswerErrorCode.MODEL_ERROR, errorText = "Прежний текст", createdAt = now,
            errorUserMessage = UserMessage.from("Проверьте ответ позднее"), errorSeverity = ErrorSeverity.INFO,
        )), hasOlder = false)
        val shown = newVm().answerItem("a1")
        assertEquals(AnswerStatus.FAILED, shown.status)
        assertEquals("Проверьте ответ позднее", shown.failureText)
        assertEquals(ErrorSeverity.INFO, shown.errorPresentation.severity)
        assertNull(shown.errorCode)
        assertFalse(shown.retryable, "MODEL_ERROR on an unrecognized status must not infer a new generation")
    }

    @Test fun `unknown and missing history statuses respect invalid versus absent canonical text`() {
        val inputs = listOf(AnswerStatus.UNKNOWN, null).flatMapIndexed { index, status ->
            listOf(
                Message("invalid-$index", MessageRole.ASSISTANT, status = status, createdAt = now,
                    errorText = "Legacy must not replace malformed canonical text", errorUserMessage = UserMessage.Invalid,
                    errorSeverity = ErrorSeverity.WARNING, errorCode = AnswerErrorCode.MODEL_ERROR, retryable = true),
                Message("legacy-$index", MessageRole.ASSISTANT, status = status, createdAt = now,
                    errorText = "Прежнее пользовательское сообщение", errorCode = AnswerErrorCode.MODEL_ERROR, retryable = true),
                Message("empty-$index", MessageRole.ASSISTANT, status = status, createdAt = now,
                    errorText = "   ", errorCode = AnswerErrorCode.MODEL_ERROR, retryable = true),
            )
        }
        api.pages[null] = MessagesList(inputs, hasOlder = false)
        val vm = newVm()
        repeat(2) { index ->
            val invalid = vm.answerItem("invalid-$index")
            assertEquals(GENERIC_ANSWER_ERROR, invalid.failureText)
            assertEquals(UserMessage.Invalid, invalid.errorPresentation.userMessage)
            assertEquals(ErrorSeverity.WARNING, invalid.errorPresentation.severity)
            assertEquals("Прежнее пользовательское сообщение", vm.answerItem("legacy-$index").failureText)
            assertEquals(GENERIC_ANSWER_ERROR, vm.answerItem("empty-$index").failureText)
        }
        vm.state.value.items.filterIsInstance<ChatItem.Answer>().forEach {
            assertEquals(AnswerStatus.FAILED, it.status)
            assertNull(it.errorCode)
            assertFalse(it.retryable)
        }
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

    @Test fun `the answer keeps the currency of its amounts, from the history and from the stream (FIN-82)`() {
        api.pages[null] = MessagesList(
            listOf(question("q1"), answer("a1").copy(currency = "USD"), question("q2"), answer("a2", status = AnswerStatus.GENERATING)),
            hasOlder = false,
        )
        api.script("a2", events(AnswerEvent.Snapshot(AnswerSnapshotEvent(AnswerStatus.COMPLETE, listOf(text("Готово")), emptyList(), currency = "EUR"))))
        val vm = newVm()
        assertEquals("USD", vm.answerItem("a1").currency)
        assertEquals("EUR", vm.answerItem("a2").currency)
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
        // A server older than FIN-159: no text — the generic one; no retryable — no retry for CANNOT_ANSWER.
        assertNull(a.errorText)
        assertFalse(a.retryable)
        assertTrue(a.blocks.isEmpty())
        assertTrue(a.chips.isEmpty())
        assertEquals(3, vm.state.value.limit!!.remaining)
        assertEquals(mapOf("code" to "CANNOT_ANSWER"), tracker.events.single { it.first == Events.ASSISTANT_ANSWER_FAILED }.second)
    }

    /** The answer item after the stream ends with [error]. */
    private fun failedBy(error: AnswerErrorEvent): ChatItem.Answer {
        api.askOutcomes += askResponse("a1")
        api.script("a1", events(AnswerEvent.Error(error)))
        val vm = newVm()
        sendQuestion(vm)
        return vm.answerItem("a1").also { assertEquals(AnswerStatus.FAILED, it.status) }
    }

    @Test fun `an error shows the server text and offers a retry only when retryable (FIN-159)`() {
        val offTopic = failedBy(AnswerErrorEvent(AnswerErrorCode.OFF_TOPIC, "off_topic", remainingLimit = 5, text = "Вопрос не по теме", retryable = false))
        assertEquals("Вопрос не по теме" to false, offTopic.errorText to offTopic.retryable)
        assertEquals("Вопрос не по теме", offTopic.failureText)
        // The flag decides, not the code: a retryable CANNOT_ANSWER gets «Повторить», a non-retryable MODEL_ERROR does not.
        val cannot = failedBy(AnswerErrorEvent(AnswerErrorCode.CANNOT_ANSWER, "m", remainingLimit = 5, text = "Т", retryable = true))
        assertTrue(cannot.retryable)
        val model = failedBy(AnswerErrorEvent(AnswerErrorCode.MODEL_ERROR, "m", remainingLimit = 5, text = "Т", retryable = false))
        assertFalse(model.retryable)
    }

    @Test fun `an unknown error code follows its text and retryable, not MODEL_ERROR (FIN-159)`() {
        val noRetry = failedBy(AnswerErrorEvent(AnswerErrorCode.UNKNOWN, "?", remainingLimit = 3, text = "Новая причина", retryable = false))
        assertEquals("Новая причина", noRetry.errorText)
        assertFalse(noRetry.retryable)
        val retry = failedBy(AnswerErrorEvent(AnswerErrorCode.UNKNOWN, "?", remainingLimit = 3, text = "Новая причина", retryable = true))
        assertTrue(retry.retryable)
        // Without retryable (an older server) an unknown code is no longer retried like MODEL_ERROR.
        assertFalse(failedBy(AnswerErrorEvent(AnswerErrorCode.UNKNOWN, "?", remainingLimit = 3)).retryable)
    }

    @Test fun `an error without text and retryable gets the generic text, a retry only for MODEL_ERROR (FIN-159)`() {
        for (code in AnswerErrorCode.entries) {
            val item = failedBy(AnswerErrorEvent(code, "m", remainingLimit = 5))
            assertNull(item.errorText, "$code")
            assertEquals("Не получилось ответить", item.failureText, "$code")
            assertEquals(code == AnswerErrorCode.MODEL_ERROR, item.retryable, "$code")
        }
    }

    @Test fun `a blank server text is no text, in the stream and in the history`() {
        for (blank in listOf("", "   ")) {
            val item = failedBy(AnswerErrorEvent(AnswerErrorCode.OFF_TOPIC, "m", remainingLimit = 5, text = blank, retryable = false))
            assertNull(item.errorText)
            assertEquals("Не получилось ответить", item.failureText)
        }
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a1", AnswerStatus.FAILED, AnswerErrorCode.OFF_TOPIC, errorText = " ", retryable = false)), hasOlder = false)
        val history = newVm().answerItem("a1")
        assertNull(history.errorText)
        assertEquals("Не получилось ответить", history.failureText)
    }

    @Test fun `a failed answer in the history follows error_text and retryable, falling back to the code (FIN-159)`() {
        api.pages[null] = MessagesList(
            listOf(
                question("q1"), answer("a1", AnswerStatus.FAILED, AnswerErrorCode.OFF_TOPIC, errorText = "Вопрос не по теме", retryable = false),
                question("q2"), answer("a2", AnswerStatus.FAILED, AnswerErrorCode.UNKNOWN, errorText = "Новая причина", retryable = true),
                question("q3"), answer("a3", AnswerStatus.FAILED, AnswerErrorCode.MODEL_ERROR),
                question("q4"), answer("a4", AnswerStatus.FAILED, AnswerErrorCode.CANNOT_ANSWER),
                question("q5"), answer("a5", AnswerStatus.FAILED, AnswerErrorCode.UNKNOWN),
                // Error fields of a message that is not failed are not shown.
                question("q6"), answer("a6", AnswerStatus.COMPLETE, errorText = "x", retryable = true),
            ),
            hasOlder = false,
        )
        val vm = newVm()
        val shown = listOf("a1", "a2", "a3", "a4", "a5", "a6").map { vm.answerItem(it).let { a -> a.errorText to a.retryable } }
        assertEquals(
            listOf("Вопрос не по теме" to false, "Новая причина" to true, null to true, null to false, null to false, null to false),
            shown,
        )
    }

    // ---- send failures ----

    @Test fun `denied consent can be reopened and grant preserves draft until explicit send`() {
        api.askOutcomes += AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403,
            response = ErrorResponseInfo(ErrorCodes.CONSENT_REQUIRED, 403, requestRetryable = false))
        api.askOutcomes += askResponse("a1")
        val vm = newVm()
        sendQuestion(vm)
        assertEquals(ChatEvent.OPEN_CONSENT, vm.state.value.event)
        assertFalse(vm.state.value.sending)
        assertEquals("Сколько я трачу на такси?", vm.state.value.draft)
        vm.consumeEvent()
        assertNull(vm.state.value.event)
        assertTrue(vm.state.value.consentRequired)
        vm.send()
        assertEquals(1, api.asks.size)
        vm.openConsent()
        assertEquals(ChatEvent.OPEN_CONSENT, vm.state.value.event)
        vm.consumeEvent()

        vm.onConsentGranted()
        assertEquals(1, api.asks.size, "consent must not send a question automatically")
        assertEquals("Сколько я трачу на такси?", vm.state.value.draft)
        assertFalse(vm.state.value.consentRequired)
        assertFalse(vm.state.value.askBlocked)
        vm.send()
        assertEquals(2, api.asks.size)
        assertEquals(api.asks[0].key, api.asks[1].key)
        assertEquals(listOf("q-a1", "a1"), vm.state.value.items.map { it.id })
        assertTrue(Events.ASSISTANT_CONSENT_GRANTED in tracker.names())
    }

    @Test fun `editing denied draft retains consent action and cannot bypass it`() {
        api.askOutcomes += AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403,
            response = ErrorResponseInfo(ErrorCodes.CONSENT_REQUIRED, 403, requestRetryable = false))
        val vm = newVm()
        sendQuestion(vm)
        vm.consumeEvent()
        vm.setDraft("Новый вопрос")
        vm.send()
        assertEquals(1, api.asks.size)
        assertTrue(vm.state.value.consentRequired)
        vm.openConsent()
        assertEquals(ChatEvent.OPEN_CONSENT, vm.state.value.event)
        vm.onConsentGranted()
        assertFalse(vm.state.value.consentRequired)
        assertEquals("Новый вопрос", vm.state.value.draft)
        assertEquals(1, api.asks.size)
    }

    @Test fun `opening consent without a denied operation does nothing`() {
        val vm = newVm()
        vm.openConsent()
        assertNull(vm.state.value.event)
        assertTrue(api.asks.isEmpty())
    }

    @Test fun `returning to a saved chat tab refreshes history without losing draft or sending`() {
        val vm = newVm()
        vm.onResumed()
        vm.setDraft("Следующий вопрос")
        vm.pauseStreams()
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a1", AnswerStatus.COMPLETE)), hasOlder = false)
        vm.onResumed()
        assertEquals(listOf("q1", "a1"), vm.state.value.items.map { it.id })
        assertEquals("Следующий вопрос", vm.state.value.draft)
        assertTrue(api.asks.isEmpty(), "tab changes must only read history, never send a question")
    }

    @Test fun `returning after older pages were loaded retains them and their pagination boundary`() {
        api.pages[null] = MessagesList(listOf(question("q2"), answer("a2")), hasOlder = true)
        api.pages["q2"] = MessagesList(listOf(question("q1"), answer("a1")), hasOlder = false)
        val vm = newVm()
        vm.onResumed()
        vm.loadOlder()
        assertEquals(listOf("q1", "a1", "q2", "a2"), vm.state.value.items.map { it.id })
        vm.pauseStreams()
        vm.onResumed()
        assertEquals(listOf("q1", "a1", "q2", "a2"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.hasOlder)
    }

    @Test fun `resume during Ask never starts a history read that can erase its accepted answer`() {
        val vm = newVm()
        vm.onResumed()
        api.askOutcomes += askResponse("a1")
        api.askGate = CompletableDeferred()
        sendQuestion(vm)
        val reads = api.listCalls.size
        vm.onResumed()
        assertEquals(reads, api.listCalls.size)
        api.askGate!!.complete(Unit)
        assertEquals(listOf("q-a1", "a1"), vm.state.value.items.map { it.id })
    }

    @Test fun `a delayed history snapshot cannot erase Ask accepted after that read began`() {
        val vm = newVm()
        api.listGate = CompletableDeferred()
        vm.load()
        api.askOutcomes += askResponse("a1")
        sendQuestion(vm)
        api.listGate!!.complete(Unit)
        assertEquals(listOf("q-a1", "a1"), vm.state.value.items.map { it.id })
    }

    @Test fun `a delayed resume snapshot cannot resurrect the failed answer replaced by RetryAnswer`() {
        val vm = vmWithFailedAnswer()
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a1", AnswerStatus.FAILED,
            AnswerErrorCode.MODEL_ERROR, retryable = true), question("q2"), answer("a3")), hasOlder = false)
        vm.load()
        vm.onResumed()
        api.listGate = CompletableDeferred()
        vm.onResumed()
        api.retryOutcomes += RetryResponse("a2", remainingLimit = 2)
        vm.retry("a1")
        assertEquals(listOf("q1", "a2", "q2", "a3"), vm.state.value.items.map { it.id })
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a2", AnswerStatus.GENERATING),
            question("q2"), answer("a3")), hasOlder = false)
        val reads = api.listCalls.size
        api.listGate!!.complete(Unit)
        assertEquals(listOf("q1", "a2", "q2", "a3"), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.items.none { it.id == "a1" }, "the stale retryable failure must stay removed")
        assertEquals(AnswerStatus.GENERATING, vm.answerItem("a2").status)
        assertEquals(1, api.retries.size)
        assertEquals(reads + 1, api.listCalls.size, "the invalidated snapshot must be refreshed")
    }

    @Test fun `an older init read that started before the failed answer was shown cannot restore its replacement`() {
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a1", AnswerStatus.FAILED,
            AnswerErrorCode.MODEL_ERROR, retryable = true)), hasOlder = false)
        val initialRead = CompletableDeferred<Unit>()
        api.listGate = initialRead
        val vm = newVm()
        api.listGate = null
        vm.load()
        api.retryOutcomes += RetryResponse("a2", remainingLimit = 2)
        vm.retry("a1")
        initialRead.complete(Unit)
        assertEquals(listOf("q1", "a2"), vm.state.value.items.map { it.id })
        assertEquals(AnswerStatus.GENERATING, vm.answerItem("a2").status)
    }

    @Test fun `a non overlapping latest page resets the pagination boundary instead of creating a hidden gap`() {
        api.pages[null] = MessagesList(listOf(question("q2"), answer("a2")), hasOlder = true)
        api.pages["q2"] = MessagesList(listOf(question("q1"), answer("a1")), hasOlder = false)
        val vm = newVm()
        vm.onResumed()
        vm.loadOlder()
        assertFalse(vm.state.value.hasOlder)
        api.pages[null] = MessagesList(listOf(question("q8"), answer("a8")), hasOlder = true)
        api.pages["q8"] = MessagesList(listOf(question("q7"), answer("a7")), hasOlder = true)
        vm.onResumed()
        assertEquals(listOf("q8", "a8"), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.hasOlder)
        vm.loadOlder()
        assertEquals("q8", api.listCalls.last())
        assertEquals(listOf("q7", "a7", "q8", "a8"), vm.state.value.items.map { it.id })
    }

    @Test fun `an older page pending across a fresh pagination boundary cannot prepend a disconnected prefix`() {
        api.pages[null] = MessagesList(listOf(question("q2"), answer("a2")), hasOlder = true)
        api.pages["q2"] = MessagesList(listOf(question("q1"), answer("a1")), hasOlder = false)
        val vm = newVm()
        val olderRead = CompletableDeferred<Unit>()
        api.listGate = olderRead
        vm.loadOlder()
        api.listGate = null
        api.pages[null] = MessagesList(listOf(question("q8"), answer("a8")), hasOlder = true)
        vm.load()
        olderRead.complete(Unit)
        assertEquals(listOf("q8", "a8"), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.hasOlder)
        assertFalse(vm.state.value.loadingOlder)
    }

    @Test fun `an older page remains valid across an overlapping resume refresh`() {
        api.pages[null] = MessagesList(listOf(question("q2"), answer("a2")), hasOlder = true)
        api.pages["q2"] = MessagesList(listOf(question("q1"), answer("a1")), hasOlder = false)
        val vm = newVm()
        vm.onResumed()
        val olderRead = CompletableDeferred<Unit>()
        api.listGate = olderRead
        vm.loadOlder()
        api.listGate = null
        vm.onResumed()
        olderRead.complete(Unit)
        assertEquals(listOf("q1", "a1", "q2", "a2"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.hasOlder)
    }

    @Test fun `pagination completing during a non overlapping latest read is never treated as an accepted Ask`() {
        api.pages[null] = MessagesList(listOf(question("q2"), answer("a2")), hasOlder = true)
        api.pages["q2"] = MessagesList(listOf(question("q1"), answer("a1")), hasOlder = false)
        val vm = newVm()
        api.pages[null] = MessagesList(listOf(question("q8"), answer("a8")), hasOlder = true)
        val latestRead = CompletableDeferred<Unit>()
        api.listGate = latestRead
        vm.load()
        api.listGate = null
        vm.loadOlder()
        assertEquals(listOf("q1", "a1", "q2", "a2"), vm.state.value.items.map { it.id })
        latestRead.complete(Unit)
        assertEquals(listOf("q8", "a8"), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.hasOlder)
    }

    private fun failOlderReadAcrossChangedBoundary(error: AppError): ChatViewModel {
        api.pages[null] = MessagesList(listOf(question("q2"), answer("a2")), hasOlder = true)
        val vm = newVm()
        val olderRead = CompletableDeferred<Unit>()
        api.listGate = olderRead
        api.listFailure = error
        vm.loadOlder()
        api.listGate = null
        api.listFailure = null
        api.pages[null] = MessagesList(listOf(question("q8"), answer("a8")), hasOlder = true)
        api.pages["q8"] = MessagesList(listOf(question("q7"), answer("a7")), hasOlder = false)
        vm.load()
        olderRead.complete(Unit)
        assertNull(vm.state.value.olderErrorPresentation, "the obsolete page error is not presented")
        return vm
    }

    @Test fun `a stale older page failure retains its Retry After restriction across a boundary change`() {
        val vm = failOlderReadAcrossChangedBoundary(AppError.RateLimited(null, 3))
        val reads = api.listCalls.size
        vm.loadOlder()
        assertEquals(reads, api.listCalls.size)
        advance(2_999)
        vm.loadOlder()
        assertEquals(reads, api.listCalls.size)
        advance(1)
        vm.loadOlder()
        assertEquals(reads + 1, api.listCalls.size)
        assertEquals("q8", api.listCalls.last())
    }

    @Test fun `a stale older refusal belongs to its old cursor but its captured wait still applies`() {
        val vm = failOlderReadAcrossChangedBoundary(AppError.RateLimited(null, 3,
            ErrorResponseInfo("RATE_LIMITED", 429, requestRetryable = false, retryAfterSeconds = 3,
                timing = ResponseTiming(receivedElapsedMillis = 0))))
        val reads = api.listCalls.size
        assertNull(vm.state.value.olderRetryable)
        advance(2999)
        vm.loadOlder()
        assertEquals(reads, api.listCalls.size)
        advance(1)
        vm.loadOlder()
        assertEquals("q8", api.listCalls.last())
        assertEquals(reads + 1, api.listCalls.size)
    }

    @Test fun `a shown older refusal clears only when the actual cursor changes and keeps its deadline`() {
        api.pages[null] = MessagesList(listOf(question("q2"), answer("a2")), hasOlder = true)
        val vm = newVm()
        api.listFailure = AppError.RateLimited(null, 3, ErrorResponseInfo("RATE_LIMITED", 429,
            requestRetryable = false, retryAfterSeconds = 3, timing = ResponseTiming(receivedElapsedMillis = 0)))
        vm.loadOlder()
        assertEquals(false, vm.state.value.olderRetryable)
        api.listFailure = null
        // This refresh overlaps: the same before_id retains its original refusal and presentation.
        api.pages[null] = MessagesList(listOf(answer("a2")), hasOlder = true)
        vm.load()
        assertEquals("q2", vm.state.value.items.first().id)
        assertEquals(false, vm.state.value.olderRetryable)
        advance(1000)
        api.pages[null] = MessagesList(listOf(question("q8"), answer("a8")), hasOlder = true)
        api.pages["q8"] = MessagesList(listOf(question("q7")), hasOlder = false)
        vm.load()
        assertNull(vm.state.value.olderRetryable)
        assertNull(vm.state.value.olderErrorPresentation)
        val reads = api.listCalls.size
        advance(1999); vm.loadOlder()
        assertEquals(reads, api.listCalls.size)
        advance(1); vm.loadOlder()
        assertEquals(reads + 1, api.listCalls.size)
        assertEquals("q8", api.listCalls.last())
    }

    @Test fun `replacing the first answer also retires its original pagination refusal`() {
        api.pages[null] = MessagesList(listOf(answer("a1", AnswerStatus.FAILED,
            AnswerErrorCode.MODEL_ERROR, retryable = true)), hasOlder = true)
        val vm = newVm()
        api.listFailure = AppError.Api(ErrorCodes.VALIDATION_ERROR, 400,
            response = ErrorResponseInfo(ErrorCodes.VALIDATION_ERROR, 400, requestRetryable = false))
        vm.loadOlder()
        assertEquals(false, vm.state.value.olderRetryable)
        api.listFailure = null
        api.retryOutcomes += RetryResponse("a2", remainingLimit = 2)
        api.pages["a2"] = MessagesList(listOf(question("q1")), hasOlder = false)
        vm.retry("a1")
        assertEquals("a2", vm.state.value.items.first().id)
        assertNull(vm.state.value.olderErrorPresentation)
        assertNull(vm.state.value.olderRetryable)
        vm.loadOlder()
        assertEquals("a2", api.listCalls.last())
        assertEquals("q1", vm.state.value.items.first().id)
    }

    @Test fun `a stale offline latest read cannot clear the newer rate limit in either completion order`() {
        for (oldFinishesFirst in listOf(false, true)) {
            api.listFailure = null
            api.listGate = null
            val vm = newVm()
            vm.onResumed()
            val oldRead = CompletableDeferred<Unit>()
            val newRead = CompletableDeferred<Unit>()
            api.listGate = oldRead
            api.listFailure = AppError.Offline()
            vm.load()
            api.listGate = newRead
            api.listFailure = AppError.RateLimited(null, 3)
            vm.onResumed()
            if (oldFinishesFirst) { oldRead.complete(Unit); newRead.complete(Unit) }
            else { newRead.complete(Unit); oldRead.complete(Unit) }
            assertFalse(vm.state.value.loadRetryReady)
            api.listGate = null
            api.listFailure = null
            val reads = api.listCalls.size
            advance(1000); vm.onResumed()
            assertEquals(reads, api.listCalls.size)
            advance(1999); vm.onResumed()
            assertEquals(reads, api.listCalls.size)
            advance(1); vm.onResumed()
            assertEquals(reads + 1, api.listCalls.size)
        }
    }

    @Test fun `a stale explicit refusal cannot block a newer successful resume in either completion order`() {
        for (oldFinishesFirst in listOf(false, true)) {
            api.listFailure = null
            api.listGate = null
            api.pages[null] = MessagesList(emptyList(), hasOlder = false)
            val vm = newVm()
            vm.onResumed()
            val oldRead = CompletableDeferred<Unit>()
            val newRead = CompletableDeferred<Unit>()
            api.listGate = oldRead
            api.listFailure = AppError.Api(ErrorCodes.VALIDATION_ERROR, 400,
                response = ErrorResponseInfo(ErrorCodes.VALIDATION_ERROR, 400, requestRetryable = false))
            vm.load()
            api.listGate = newRead
            api.listFailure = null
            api.pages[null] = MessagesList(listOf(question("q8")), hasOlder = false)
            vm.onResumed()
            if (oldFinishesFirst) { oldRead.complete(Unit); newRead.complete(Unit) }
            else { newRead.complete(Unit); oldRead.complete(Unit) }
            assertTrue(vm.state.value.loadRetryReady)
            assertNull(vm.state.value.loadErrorPresentation)
            assertEquals(listOf("q8"), vm.state.value.items.map { it.id })
            api.listGate = null
            val reads = api.listCalls.size
            api.pages[null] = MessagesList(listOf(question("q9")), hasOlder = false)
            vm.onResumed()
            assertEquals(reads + 1, api.listCalls.size)
            assertEquals(listOf("q9"), vm.state.value.items.map { it.id })
        }
    }

    @Test fun `a replacement during failed stream recovery rereads the authoritative history and keeps its skip id`() {
        val streamFailed = CompletableDeferred<Unit>()
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a1", AnswerStatus.FAILED,
            AnswerErrorCode.MODEL_ERROR, retryable = true), question("q2"), answer("x", AnswerStatus.GENERATING)), hasOlder = false)
        api.script("x", flow { streamFailed.await(); throw AppError.Api(ErrorCodes.NOT_FOUND, 404) })
        val vm = newVm()
        val recoveryRead = CompletableDeferred<Unit>()
        api.listGate = recoveryRead
        streamFailed.complete(Unit)
        api.retryOutcomes += RetryResponse("a2", remainingLimit = 2)
        vm.retry("a1")
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a2", AnswerStatus.COMPLETE),
            question("q2"), answer("x", AnswerStatus.FAILED)), hasOlder = false)
        val reads = api.listCalls.size
        recoveryRead.complete(Unit)
        assertEquals(reads + 1, api.listCalls.size)
        assertEquals(AnswerStatus.FAILED, vm.answerItem("x").status)
        assertEquals(AnswerStatus.COMPLETE, vm.answerItem("a2").status)
        assertFalse(vm.state.value.generating, "a discarded recovery read must not strand the composer")
        assertEquals(1, api.streamOpens.count { it == "x" })
    }

    @Test fun `consent accepted through Profile unlocks retained draft without automatically sending`() {
        val vm = newVm()
        vm.onResumed()
        api.askOutcomes += AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403,
            response = ErrorResponseInfo(ErrorCodes.CONSENT_REQUIRED, 403, requestRetryable = false))
        sendQuestion(vm)
        vm.consumeEvent()
        profileApi.profile = profileApi.profile.copy(consents = listOf(
            ru.finassist.pf.core.api.model.Consent(ru.finassist.pf.core.api.model.ConsentType.ASSISTANT, true, "1", "1")))
        vm.onResumed()
        assertFalse(vm.state.value.consentRequired)
        assertFalse(vm.state.value.askBlocked)
        assertTrue(vm.state.value.askRetryReady)
        assertEquals(1, api.asks.size)
        assertEquals("Сколько я трачу на такси?", vm.state.value.draft)
        api.askOutcomes += askResponse("a1")
        vm.send()
        assertEquals(2, api.asks.size)
        assertEquals(api.asks.first().key, api.asks.last().key)
    }

    @Test fun `missing outdated or unreadable Profile consent never clears a retained refusal`() {
        val vm = newVm()
        vm.onResumed()
        api.askOutcomes += AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403,
            response = ErrorResponseInfo(ErrorCodes.CONSENT_REQUIRED, 403, requestRetryable = false))
        sendQuestion(vm)
        vm.consumeEvent()
        vm.onResumed()
        assertTrue(vm.state.value.consentRequired)
        profileApi.profile = profileApi.profile.copy(consents = listOf(
            ru.finassist.pf.core.api.model.Consent(ru.finassist.pf.core.api.model.ConsentType.ASSISTANT, true, "1", "2")))
        vm.onResumed()
        assertTrue(vm.state.value.consentRequired)
        profileApi.failure = AppError.Offline()
        vm.onResumed()
        assertTrue(vm.state.value.consentRequired)
        vm.send()
        assertEquals(1, api.asks.size)
    }

    @Test fun `Profile correction enables explicit RetryAnswer without starting it on resume`() {
        val vm = vmWithFailedAnswer()
        vm.onResumed()
        api.retryOutcomes += AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403,
            response = ErrorResponseInfo(ErrorCodes.CONSENT_REQUIRED, 403, requestRetryable = false))
        vm.retry("a1")
        vm.consumeEvent()
        profileApi.profile = profileApi.profile.copy(consents = listOf(
            ru.finassist.pf.core.api.model.Consent(ru.finassist.pf.core.api.model.ConsentType.ASSISTANT, true, "1", "1")))
        vm.onResumed()
        assertFalse(vm.state.value.consentRequired)
        assertNull(vm.state.value.blockedRetryAnswerId)
        assertEquals(1, api.retries.size)
        api.retryOutcomes += RetryResponse("a2", 2)
        vm.retry("a1")
        assertEquals(2, api.retries.size)
        assertEquals(api.retries.first().first, api.retries.last().first)
        assertTrue(api.asks.isEmpty())
    }

    @Test fun `restored Ask after consent requires explicit new intent before generating a fresh key`() {
        val text = "Сколько я трачу на такси?"
        val saved = SavedStateHandle(mapOf("chat.pending.text" to text, "chat.pending.key" to "old-unknown-key"))
        val vm = newVm(saved)
        vm.onConsentGranted()
        assertTrue(api.asks.isEmpty())
        assertEquals("old-unknown-key", saved.get<String>("chat.pending.key"))
        assertEquals(text, vm.state.value.draft)
        assertEquals(ChatRecoveryTarget.ASK, vm.state.value.recovery?.target)
        vm.retryAfterFailure()
        assertTrue(api.asks.isEmpty(), "ordinary retry cannot replace unknown origin proof")
        api.askOutcomes += askResponse("a1")
        vm.startNewRequest()
        assertEquals(1, api.asks.size)
        assertTrue(api.asks.single().key.value != "old-unknown-key")
        assertEquals(text, api.asks.single().request.text)
        assertNull(vm.state.value.recovery)
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

    @Test fun `a restored question retains unknown-origin key and draft without replaying POST`() {
        api.askOutcomes += askResponse("a1")
        val saved = SavedStateHandle(mapOf("chat.pending.text" to "Сколько я трачу на такси?", "chat.pending.key" to "k-1"))
        val vm = newVm(saved)
        assertEquals("Сколько я трачу на такси?", vm.state.value.draft)
        vm.send()
        assertTrue(api.asks.isEmpty())
        assertEquals("k-1", saved.get<String>("chat.pending.key"))
        assertEquals("Сколько я трачу на такси?", vm.state.value.draft)
        assertTrue(vm.state.value.askBlocked)
    }

    @Test fun `an expired pending Ask cannot be replayed or silently receive a new key`() {
        api.askOutcomes += AppError.Offline()
        val saved = SavedStateHandle()
        val vm = newVm(saved)
        sendQuestion(vm)
        val oldKey = api.asks.single().key
        advance(24L * 60 * 60 * 1000)
        vm.retryAfterFailure()
        assertEquals(1, api.asks.size)
        assertEquals(oldKey.value, saved.get<String>("chat.pending.key"))
        assertTrue(vm.state.value.askBlocked)
        assertTrue(vm.state.value.draft.isNotEmpty())
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

    @Test fun `an expired pending generation retry only reads history and never renews its original UUID`() {
        api.retryOutcomes += AppError.Offline() // a lost response may hide an accepted replacement
        api.retryOutcomes += RetryResponse("a3", remainingLimit = 1) // must remain unused
        val vm = vmWithFailedAnswer()
        vm.retry("a1")
        val originalKey = api.retries.single().first
        val readsBeforeRecovery = api.listCalls.size
        val quotaBeforeRecovery = vm.state.value.limit
        vm.setDraft("Черновик следующего вопроса")
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a2")), hasOlder = false)
        advance(24L * 60 * 60 * 1000)

        vm.retry("a1")
        assertEquals(listOf(originalKey.value), api.retries.map { it.first.value }, "no second POST or fresh wire UUID")
        assertEquals(readsBeforeRecovery + 1, api.listCalls.size, "recover the accepted replacement through a read")
        assertEquals(listOf("q1", "a2"), vm.state.value.items.map { it.id })
        assertEquals(AnswerStatus.COMPLETE, vm.answerItem("a2").status)
        assertEquals("Черновик следующего вопроса", vm.state.value.draft)
        assertEquals(quotaBeforeRecovery, vm.state.value.limit)
        assertEquals("a1", vm.state.value.blockedRetryAnswerId)

        vm.retry("a1")
        assertEquals(listOf(originalKey.value), api.retries.map { it.first.value }, "another tap cannot allocate a fresh attempt")
        assertEquals(readsBeforeRecovery + 2, api.listCalls.size)
        assertEquals(1, api.retryOutcomes.size, "the next generation response was not consumed")
        vm.startNewRequest()
        assertEquals(1, api.retries.size, "history already recovered the replacement, so no new attempt is offered")
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

    @Test fun `corrected consent resumes the denied RetryAnswer with its same key even after explicit false`() {
        api.retryOutcomes += AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403,
            response = ErrorResponseInfo(ErrorCodes.CONSENT_REQUIRED, 403, requestRetryable = false))
        api.retryOutcomes += RetryResponse("a2", remainingLimit = 2)
        val vm = vmWithFailedAnswer()
        vm.retry("a1")
        val originalKey = api.retries.single().first
        assertEquals(ChatEvent.OPEN_CONSENT, vm.state.value.event)
        vm.consumeEvent()
        vm.onConsentGranted()
        assertEquals(2, api.retries.size)
        assertEquals(originalKey, api.retries.last().first)
        assertEquals("a1", api.retries.last().second)
        assertTrue(api.asks.isEmpty(), "corrective consent must not send Ask instead of RetryAnswer")
        assertEquals(listOf("q1", "a2"), vm.state.value.items.map { it.id })
    }

    @Test fun `expired denied RetryAnswer needs fresh history permission and an explicit new attempt`() {
        api.retryOutcomes += AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403,
            response = ErrorResponseInfo(ErrorCodes.CONSENT_REQUIRED, 403, requestRetryable = false))
        api.retryOutcomes += RetryResponse("a2", remainingLimit = 2)
        val vm = vmWithFailedAnswer()
        vm.retry("a1")
        val oldKey = api.retries.single().first
        advance(24L * 60 * 60 * 1000)
        vm.onConsentGranted()
        assertEquals(1, api.retries.size)
        assertEquals(ChatRecoveryTarget.RETRY_ANSWER, vm.state.value.recovery?.target)
        assertTrue(vm.state.value.recovery?.historyChecked == true)
        vm.startNewRequest()
        assertEquals(2, api.retries.size)
        assertTrue(api.retries.last().first != oldKey)
        assertEquals("a1", api.retries.last().second)
        assertTrue(api.asks.isEmpty())
    }

    @Test fun `a fresh history refusal of new generation removes the expired recovery action`() {
        api.retryOutcomes += AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403,
            response = ErrorResponseInfo(ErrorCodes.CONSENT_REQUIRED, 403, requestRetryable = false))
        val vm = vmWithFailedAnswer()
        vm.retry("a1")
        advance(24L * 60 * 60 * 1000)
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a1", AnswerStatus.FAILED,
            AnswerErrorCode.MODEL_ERROR, retryable = false)), hasOlder = false)
        vm.onConsentGranted()
        assertEquals(1, api.retries.size)
        assertNull(vm.state.value.recovery)
        assertFalse(vm.answerItem("a1").retryable)
        vm.startNewRequest()
        assertEquals(1, api.retries.size)
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
        // Pauses random in [d/2; d], d = 1, 2, 4 s: the n-th re-open falls in [2^n − 1.5 s; 2^n − 1 s] for any jitter.
        assertEquals(1, api.streamOpens.size)
        advance(1_000)
        assertEquals(2, api.streamOpens.size)
        advance(2_000)
        assertEquals(3, api.streamOpens.size)
        advance(4_000)
        assertEquals(4, api.streamOpens.size)
        assertEquals(AnswerStatus.COMPLETE, vm.answerItem("a1").status)
        assertFalse(vm.state.value.offline)
        advance(60_000)
        assertEquals(4, api.streamOpens.size)
    }

    @Test fun `a drop after an event keeps what was shown and re-opens without an error`() {
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
        advance(2_000)
        assertEquals(3, api.streamOpens.size)
        assertEquals(listOf(text("Начало")), vm.answerItem("a1").blocks)
        assertFalse(vm.state.value.offline)
        assertFalse(vm.state.value.sendFailed)
    }

    @Test fun `coming back to the chat during a 429 pause waits again, without a reload or an error, one stream at a time`() {
        var active = 0
        var maxActive = 0
        fun tracked(f: Flow<AnswerEvent>) = f.onStart { active++; maxActive = maxOf(maxActive, active) }.onCompletion { active-- }
        val limitedAt = mutableListOf<Long>()
        val limited = { tracked(flow { limitedAt += dispatcher.scheduler.currentTime; throw AppError.RateLimited(null, retryAfterSeconds = 60) }) }
        api.askOutcomes += askResponse("a1")
        api.script(
            "a1",
            // Closed after the snapshot: a drop.
            tracked(events(AnswerEvent.Snapshot(AnswerSnapshotEvent(AnswerStatus.GENERATING, listOf(text("Нач")), emptyList())))),
            limited(),
            // The next opening after the retained wait gets a fresh 429 with its own wait.
            limited(),
            tracked(events(AnswerEvent.Snapshot(AnswerSnapshotEvent(AnswerStatus.COMPLETE, listOf(text("Ответ")), emptyList())))),
        )
        val vm = newVm()
        sendQuestion(vm)
        advance(1_000)
        assertEquals(2, api.streamOpens.size, "the drop was re-opened and got 429")
        val lists = api.listCalls.size
        advance(4_000) // consume part of the server wait before leaving the screen
        vm.pauseStreams()
        vm.resumeStreams()
        assertEquals(2, api.streamOpens.size, "resume must not bypass the retained Retry-After")
        assertEquals(lists, api.listCalls.size, "a 429 on opening it again is not a reason to reload")
        assertEquals(AnswerStatus.GENERATING, vm.answerItem("a1").status)
        assertFalse(vm.state.value.sendFailed)
        assertFalse(vm.state.value.offline)
        val firstRefusal = limitedAt.single()
        advance(firstRefusal + 59_999 - dispatcher.scheduler.currentTime)
        assertEquals(2, api.streamOpens.size, "not before the original Retry-After")
        advance(firstRefusal + 62_001 - dispatcher.scheduler.currentTime)
        assertEquals(3, api.streamOpens.size, "the original wait is not restarted on resume")
        val secondRefusal = limitedAt.last()
        advance(secondRefusal + 59_999 - dispatcher.scheduler.currentTime)
        assertEquals(3, api.streamOpens.size, "a fresh 429 gets its own Retry-After")
        advance(secondRefusal + 62_001 - dispatcher.scheduler.currentTime)
        assertEquals(4, api.streamOpens.size)
        assertEquals(AnswerStatus.COMPLETE, vm.answerItem("a1").status)
        assertEquals(1, maxActive, "never two streams at once")
    }

    @Test fun `a 503 before the snapshot, a 429, a return to the chat and a 429 again still end with the answer`() {
        var active = 0
        var maxActive = 0
        fun tracked(f: Flow<AnswerEvent>) = f.onStart { active++; maxActive = maxOf(maxActive, active) }.onCompletion { active-- }
        val limitedAt = mutableListOf<Long>()
        val limited = { tracked(flow { limitedAt += dispatcher.scheduler.currentTime; throw AppError.RateLimited(null, retryAfterSeconds = 60) }) }
        api.askOutcomes += askResponse("a1")
        api.script(
            "a1",
            // No event at all: the stream drops before its snapshot.
            tracked(flow { throw AppError.Server(503) }),
            limited(),
            limited(),
            tracked(events(AnswerEvent.Snapshot(AnswerSnapshotEvent(AnswerStatus.COMPLETE, listOf(text("Ответ")), emptyList())))),
        )
        val vm = newVm()
        sendQuestion(vm)
        advance(1_000)
        assertEquals(2, api.streamOpens.size, "the 503 was re-opened and got 429")
        val lists = api.listCalls.size
        advance(4_000) // consume part of the server wait before leaving the screen
        vm.pauseStreams()
        vm.resumeStreams()
        assertEquals(2, api.streamOpens.size, "resume must not bypass the retained Retry-After")
        assertEquals(lists, api.listCalls.size, "the 429 after coming back is waited for, not a reason to reload")
        assertFalse(vm.state.value.sendFailed)
        val firstRefusal = limitedAt.single()
        advance(firstRefusal + 59_999 - dispatcher.scheduler.currentTime)
        assertEquals(2, api.streamOpens.size, "not before the original Retry-After")
        advance(firstRefusal + 62_001 - dispatcher.scheduler.currentTime)
        assertEquals(3, api.streamOpens.size, "the original wait is not restarted on resume")
        val secondRefusal = limitedAt.last()
        advance(secondRefusal + 59_999 - dispatcher.scheduler.currentTime)
        assertEquals(3, api.streamOpens.size, "a fresh 429 gets its own Retry-After")
        advance(secondRefusal + 62_001 - dispatcher.scheduler.currentTime)
        assertEquals(4, api.streamOpens.size)
        assertEquals(AnswerStatus.COMPLETE, vm.answerItem("a1").status)
        assertEquals(listOf(text("Ответ")), vm.answerItem("a1").blocks)
        assertEquals(1, maxActive, "never two streams at once")
    }

    @Test fun `false maintenance permits one delayed stream recovery without resending Ask`() {
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a1", AnswerStatus.GENERATING)), hasOlder = false)
        api.script("a1", flow { throw AppError.Maintenance(null, 15,
            ErrorResponseInfo("MAINTENANCE", 503, requestRetryable = false,
                timing = ru.finassist.pf.core.common.time.ResponseTiming(receivedElapsedMillis = 0))) },
            events(done(3)))
        val vm = newVm()
        assertEquals(1, api.streamOpens.size)
        vm.resumeStreams()
        advance(14_000)
        assertEquals(1, api.streamOpens.size)
        vm.pauseStreams()
        vm.resumeStreams()
        advance(999)
        assertEquals(1, api.streamOpens.size)
        advance(1)
        assertEquals(2, api.streamOpens.size)
        assertEquals(AnswerStatus.COMPLETE, vm.answerItem("a1").status)
        assertTrue(api.asks.isEmpty())
        assertTrue(api.retries.isEmpty())
    }

    @Test fun `a second false stream refusal before an event cannot start another maintenance recovery loop`() {
        fun refusal() = flow<AnswerEvent> { throw AppError.Maintenance(null, 15,
            ErrorResponseInfo("MAINTENANCE", 503, requestRetryable = false,
                timing = ru.finassist.pf.core.common.time.ResponseTiming(receivedElapsedMillis = dispatcher.scheduler.currentTime))) }
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a1", AnswerStatus.GENERATING)), hasOlder = false)
        api.script("a1", refusal(), refusal(), events(done(3)))
        val vm = newVm()
        vm.resumeStreams()
        advance(15_000)
        assertEquals(2, api.streamOpens.size)
        vm.resumeStreams()
        advance(60_000)
        vm.resumeStreams()
        assertEquals(2, api.streamOpens.size, "a fresh refusal cannot arm another permit without a successful event")
        assertTrue(api.asks.isEmpty())
        assertTrue(api.retries.isEmpty())
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
    @Test fun `unknown request message preserves draft history quota and forbids direct retry`() {
        val presentation = ErrorPresentation(UserMessage.from("Подождите: действие временно недоступно"), ErrorSeverity.WARNING)
        api.askOutcomes += AppError.Api("FUTURE_FAILURE", 429, response = ErrorResponseInfo(
            code = "FUTURE_FAILURE", httpStatus = 429, presentation = presentation, requestRetryable = false))
        val vm = newVm()
        val draft = "Сколько осталось на покупки?"
        sendQuestion(vm, draft)
        assertEquals(draft, vm.state.value.draft)
        assertEquals(limit, vm.state.value.limit)
        assertTrue(vm.state.value.items.isEmpty())
        assertEquals(presentation, vm.state.value.errorPresentation)
        vm.retryAfterFailure()
        vm.setDraft(draft + " ") // whitespace keeps the same normalized request and its denial
        vm.send()
        advance(60_000)
        assertEquals(1, api.asks.size)
        api.askOutcomes += askResponse()
        vm.setDraft("Другой вопрос о расходах")
        vm.send()
        assertEquals(2, api.asks.size)
        assertTrue(api.asks.first().key != api.asks.last().key)
    }

    @Test fun `manual Ask retry waits and retains exactly the original key and draft`() {
        api.askOutcomes += AppError.RateLimited(null, 3, ErrorResponseInfo("RATE_LIMITED", 429,
            requestRetryable = true, timing = ru.finassist.pf.core.common.time.ResponseTiming(receivedElapsedMillis = 0)))
        api.askOutcomes += askResponse()
        val vm = newVm()
        sendQuestion(vm, "Сколько осталось на покупки?")
        assertFalse(vm.state.value.askRetryReady)
        vm.retryAfterFailure()
        assertEquals(1, api.asks.size)
        advance(2999)
        vm.send()
        assertEquals(1, api.asks.size)
        advance(1)
        assertTrue(vm.state.value.askRetryReady)
        vm.retryAfterFailure()
        assertEquals(2, api.asks.size)
        assertEquals(api.asks.first().key, api.asks.last().key)
        assertEquals(api.asks.first().request, api.asks.last().request)
    }

    @Test fun `terminal unknown code never activates REST consent action and keeps new attempt permission`() {
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a1", AnswerStatus.GENERATING)), hasOlder = false)
        val event = AnswerErrorEvent(AnswerErrorCode.UNKNOWN, "SQL secret", 5, text = "legacy text", retryable = true,
            userMessage = UserMessage.from("Безопасный ответ новой ошибки"), severity = ErrorSeverity.WARNING, rawCode = ErrorCodes.CONSENT_REQUIRED)
        api.script("a1", flowOf(AnswerEvent.Error(event)))
        val vm = newVm()
        assertNull(vm.state.value.event)
        assertEquals("Безопасный ответ новой ошибки", vm.answerItem("a1").failureText)
        assertTrue(vm.answerItem("a1").retryable)
        assertEquals(ErrorCodes.CONSENT_REQUIRED, vm.answerItem("a1").errorCode)
        assertEquals(5, vm.state.value.limit?.remaining)
    }

    @Test fun `invalid canonical answer text never revives legacy diagnostic text`() {
        api.pages[null] = MessagesList(listOf(question("q1"), answer("a1", AnswerStatus.GENERATING)), hasOlder = false)
        api.script("a1", flowOf(AnswerEvent.Error(AnswerErrorEvent(AnswerErrorCode.MODEL_ERROR, "SQL secret", 5,
            text = "legacy text must not be used", retryable = false, userMessage = UserMessage.Invalid))))
        val vm = newVm()
        assertEquals(GENERIC_ANSWER_ERROR, vm.answerItem("a1").failureText)
        assertFalse(vm.answerItem("a1").retryable)
    }


    @Test fun `another voice question changes the refused Ask context before its gate is checked`() {
        api.askOutcomes += voiceAskRefusal(false)
        api.askOutcomes += askResponse()
        val vm = newVm()
        sendQuestion(vm, "Сколько потрачено на такси?")
        val original = api.asks.single().key
        vm.send(voiceText = "Сколько потрачено на продукты?")
        assertEquals(2, api.asks.size)
        assertEquals("Сколько потрачено на продукты?", api.asks.last().request.text)
        assertTrue(original != api.asks.last().key)
    }

    @Test fun `the same trimmed voice question preserves false denial and its pending key`() {
        api.askOutcomes += voiceAskRefusal(false)
        val saved = SavedStateHandle()
        val vm = newVm(saved)
        val question = "Сколько потрачено на такси?"
        sendQuestion(vm, question)
        val original = api.asks.single().key
        val pendingBefore = saved.get<String>("chat.pending.key")
        vm.send(voiceText = "  $question ")
        advance(60_000)
        assertEquals(1, api.asks.size)
        assertEquals("  $question ", vm.state.value.draft)
        assertEquals(false, vm.state.value.requestRetryable)
        assertFalse(vm.state.value.askRetryReady)
        assertEquals(pendingBefore, saved.get<String>("chat.pending.key"))
        assertEquals(original.value, pendingBefore)
    }

    @Test fun `a new voice question does not inherit the previous question wait`() {
        api.askOutcomes += voiceAskRefusal(true)
        api.askOutcomes += askResponse()
        val vm = newVm()
        sendQuestion(vm, "Сколько потрачено на такси?")
        assertFalse(vm.state.value.askRetryReady)
        val original = api.asks.single().key
        vm.send(voiceText = "Сколько потрачено на продукты?")
        assertEquals(2, api.asks.size, "a different request is not a resend of the rate-limited request")
        assertTrue(original != api.asks.last().key)
    }

    @Test fun `same voice preserves the original wait deadline and replays its exact key`() {
        api.askOutcomes += voiceAskRefusal(true)
        api.askOutcomes += askResponse()
        val vm = newVm()
        val question = "Сколько потрачено на такси?"
        sendQuestion(vm, question)
        val original = api.asks.single().key
        advance(2_000)
        vm.send(voiceText = " $question  ")
        assertEquals(1, api.asks.size)
        advance(999)
        vm.send(voiceText = question)
        assertEquals(1, api.asks.size)
        advance(1)
        vm.send(voiceText = " $question ")
        assertEquals(2, api.asks.size)
        assertEquals(original, api.asks.last().key)
        assertEquals(question, api.asks.last().request.text)
    }

    @Test fun `a voice result while an Ask is busy stays in the draft after the old Ask fails`() {
        val pending = CompletableDeferred<Unit>()
        api.askGate = pending
        api.askOutcomes += voiceAskRefusal(false)
        val vm = newVm()
        sendQuestion(vm, "Сколько потрачено на такси?")
        vm.send(voiceText = "Сколько потрачено на продукты?")
        assertEquals(1, api.asks.size)
        assertEquals("Сколько потрачено на продукты?", vm.state.value.draft)
        pending.complete(Unit)
        assertFalse(vm.state.value.sending)
        assertEquals("Сколько потрачено на продукты?", vm.state.value.draft)
        assertNull(vm.state.value.requestRetryable)
        assertFalse(vm.state.value.askBlocked)
        assertTrue(vm.state.value.askRetryReady)
        api.askGate = null
        api.askOutcomes += askResponse()
        vm.send()
        assertEquals(2, api.asks.size)
        assertTrue(api.asks.first().key != api.asks.last().key)
    }

    @Test fun `a voice result while an Ask is busy survives old success with the usual length bound`() {
        val pending = CompletableDeferred<Unit>()
        api.askGate = pending
        api.askOutcomes += askResponse()
        val vm = newVm()
        sendQuestion(vm, "Сколько потрачено на такси?")
        val recognized = "б".repeat(2_100)
        vm.send(voiceText = recognized)
        assertEquals("б".repeat(2_000), vm.state.value.draft)
        assertEquals(1, api.asks.size)
        pending.complete(Unit)
        assertEquals("б".repeat(2_000), vm.state.value.draft)
        assertEquals(listOf("q-a1", "a1"), vm.state.value.items.map { it.id }, "the accepted original Ask still belongs in history")
        assertEquals(3, vm.state.value.limit?.remaining)
        assertFalse(vm.state.value.sending)
    }

    private fun voiceAskRefusal(retryable: Boolean) = AppError.RateLimited(null, 3,
        ErrorResponseInfo("RATE_LIMITED", 429, requestRetryable = retryable,
            timing = ru.finassist.pf.core.common.time.ResponseTiming(receivedElapsedMillis = 0)))

    @Test fun `a quota GET begun before Ask cannot restore its spent remaining count`() {
        val vm = newVm()
        val oldQuota = CompletableDeferred<AssistantLimit>()
        api.limitReply = { oldQuota.await() }
        vm.refresh()
        api.askOutcomes += askResponse(remaining = 0)
        sendQuestion(vm)
        assertEquals(0, vm.state.value.limit?.remaining)
        oldQuota.complete(limit)
        assertEquals(0, vm.state.value.limit?.remaining)
        assertTrue(vm.state.value.limitExhausted)
    }

    @Test fun `a quota GET begun before Retry cannot restore its spent remaining count`() {
        api.pages[null] = MessagesList(listOf(answer("old", AnswerStatus.FAILED, retryable = true)), hasOlder = false)
        val vm = newVm()
        val oldQuota = CompletableDeferred<AssistantLimit>()
        api.limitReply = { oldQuota.await() }
        vm.refresh()
        api.retryOutcomes += RetryResponse("new", remainingLimit = 0)
        vm.retry("old")
        oldQuota.complete(limit)
        assertEquals(0, vm.state.value.limit?.remaining)
        assertTrue(vm.state.value.limitExhausted)
    }

    @Test fun `a quota GET begun before terminal SSE cannot overwrite its remaining count`() {
        api.pages[null] = MessagesList(listOf(answer("a1", AnswerStatus.GENERATING)), hasOlder = false)
        val events = kotlinx.coroutines.flow.MutableSharedFlow<AnswerEvent>(extraBufferCapacity = 1)
        api.script("a1", events)
        val vm = newVm()
        val oldQuota = CompletableDeferred<AssistantLimit>()
        api.limitReply = { oldQuota.await() }
        vm.refresh()
        events.tryEmit(done(remaining = 0))
        assertEquals(0, vm.state.value.limit?.remaining)
        oldQuota.complete(limit)
        assertEquals(0, vm.state.value.limit?.remaining)
        assertTrue(vm.state.value.limitExhausted)
    }

}
