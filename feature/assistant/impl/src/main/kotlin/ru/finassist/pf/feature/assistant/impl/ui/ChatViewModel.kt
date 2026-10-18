package ru.finassist.pf.feature.assistant.impl.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.reopeningStream
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.Chip
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.Message
import ru.finassist.pf.core.api.model.MessageRole
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.api.model.answerRetryable
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.assistant.api.AssistantRoutes
import java.time.OffsetDateTime
import javax.inject.Inject

/** One line of the thread. */
sealed interface ChatItem {
    val id: String
    val createdAt: OffsetDateTime

    data class Question(override val id: String, override val createdAt: OffsetDateTime, val text: String) : ChatItem

    data class Answer(
        override val id: String,
        override val createdAt: OffsetDateTime,
        val status: AnswerStatus,
        val blocks: List<Block> = emptyList(),
        val chips: List<Chip> = emptyList(),
        val source: AnswerSource? = null,
        /**
         * Of a failed answer: the server's text shown instead of the answer; null — the client's generic text (a server
         * older than FIN-159, or an unknown status shown as failed). Never built from the error code (api.md 7.2, 7.3).
         */
        val errorText: String? = null,
        /** Of a failed answer: «Повторить» is offered ([answerRetryable]). */
        val retryable: Boolean = false,
        val currency: String = Money.RUB,
    ) : ChatItem {
        /** What a failed answer shows: the server's text, or the generic one without it. */
        val failureText: String get() = errorText ?: GENERIC_ANSWER_ERROR
    }
}

enum class ChatEvent { OPEN_CONSENT }

data class ChatUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val items: List<ChatItem> = emptyList(),
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val draft: String = "",
    val sending: Boolean = false,
    val limit: AssistantLimit? = null,
    val offline: Boolean = false,
    /** Last send/retry failed for a reason other than the network (shown with «Повторить»). */
    val sendFailed: Boolean = false,
    val event: ChatEvent? = null,
) {
    val generating: Boolean get() = items.any { it is ChatItem.Answer && it.status == AnswerStatus.GENERATING }
    val limitExhausted: Boolean get() = limit?.let { it.remaining <= 0 } == true
}

/**
 * The single continuous dialog (api.md 7). A question is created by `ask` with an idempotency key kept
 * until a final response; the answer is generated on the server and read as an SSE stream, which is
 * simply re-opened after a drop or on resume (the first event is a full snapshot).
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val api: AssistantApi,
    private val tracker: Tracker,
    private val clock: Clock,
) : ViewModel() {
    private val transferMode: TransferMode =
        TransferMode.entries.firstOrNull { it.code == savedState.toRoute<AssistantRoutes.Chat>().transferMode } ?: TransferMode.WITH

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state
    private val streams = HashMap<String, Job>()
    private val retryKeys = HashMap<String, IdempotencyKey>()
    /** Failed answers whose «Повторить» is in flight: a second tap must not send the same key again (FIN-75). */
    private val retrying = HashSet<String>()

    /**
     * Text + key of the question being sent, until a final response. «Задать этот вопрос» is one action per
     * send, not per text: a retry after a dropped connection, consent or process death (kept in [savedState])
     * reuses the key, while the same text asked again later gets a new key and a new answer.
     */
    private var pending: Pair<String, IdempotencyKey>?
        get() {
            val text = savedState.get<String>(KEY_PENDING_TEXT) ?: return null
            val key = savedState.get<String>(KEY_PENDING_KEY) ?: return null
            return text to IdempotencyKey(key)
        }
        set(value) {
            savedState[KEY_PENDING_TEXT] = value?.first
            savedState[KEY_PENDING_KEY] = value?.second?.value
        }

    init {
        tracker.track(Events.ASSISTANT_OPENED)
        // Restored after process death: the unsent question is back in the composer, «Отправить» repeats it.
        pending?.let { (text, _) -> _state.update { it.copy(draft = text) } }
        load()
    }

    fun load() = reload(skipStream = null)

    /**
     * Reads the latest page of the thread; on failure a shown thread stays as it is. [skipStream] is an answer
     * whose stream has just failed for good: it is not re-opened from here even if the history still says it is
     * generating, or a server that keeps answering the stream with 404 would loop (the next resume tries again).
     */
    private fun reload(skipStream: String?) {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty(), loadFailed = false) }
            try {
                val list = api.listMessages(limit = PAGE)
                _state.update { it.copy(loading = false, items = list.messages.mapNotNull(::toItem).distinctById(), hasOlder = list.hasOlder, offline = false) }
                refreshLimit()
                watchGenerating(skip = skipStream)
            } catch (e: AppError) {
                _state.update { it.copy(loading = false, loadFailed = it.items.isEmpty(), offline = e is AppError.Offline) }
            }
        }
    }

    fun loadOlder() {
        val s = _state.value
        val first = s.items.firstOrNull() ?: return
        if (!s.hasOlder || s.loadingOlder) return
        viewModelScope.launch {
            _state.update { it.copy(loadingOlder = true) }
            try {
                val list = api.listMessages(limit = PAGE, beforeId = first.id)
                _state.update { st -> st.copy(loadingOlder = false, items = (list.messages.mapNotNull(::toItem) + st.items).distinctById(), hasOlder = list.hasOlder) }
            } catch (e: AppError) {
                _state.update { it.copy(loadingOlder = false) }
            }
        }
    }

    private suspend fun refreshLimit() {
        runCatching { api.getLimit() }.onSuccess { l -> _state.update { it.copy(limit = l) } }
    }

    /** Called on resume: re-open streams of answers still generating. */
    fun resumeStreams() = watchGenerating(skip = null)

    private fun watchGenerating(skip: String?) {
        _state.value.items.filterIsInstance<ChatItem.Answer>()
            .filter { it.status == AnswerStatus.GENERATING && it.id != skip }
            .forEach { watch(it.id) }
    }

    fun pauseStreams() {
        streams.values.forEach { it.cancel() }
        streams.clear()
    }

    fun setDraft(text: String) = _state.update { it.copy(draft = text.take(MAX_LENGTH)) }

    /** Sends the draft (or [voiceText] right away — voice input is sent immediately, api.md / mvp-scope). */
    fun send(voiceText: String? = null) {
        val text = (voiceText ?: _state.value.draft).trim()
        if (text.isEmpty() || _state.value.sending || _state.value.generating) return
        val key = pending?.takeIf { it.first == text }?.second ?: IdempotencyKey.random()
        pending = text to key
        // Set before the coroutine starts: a double tap must not launch a second send.
        _state.update { it.copy(sending = true, draft = text, sendFailed = false) }
        viewModelScope.launch {
            try {
                val r = whileInProgress { api.ask(key, AskRequest(text = text, transferMode = transferMode)) }
                pending = null
                tracker.track(Events.ASSISTANT_QUESTION_SENT)
                val question = ChatItem.Question(r.question.id, r.question.createdAt, r.question.text ?: text)
                val answer = ChatItem.Answer(r.answerId, r.question.createdAt, AnswerStatus.GENERATING)
                _state.update {
                    it.copy(
                        sending = false, draft = "", offline = false,
                        // A replayed 201 carries ids the reloaded history may already hold: keep those.
                        items = (it.items + question + answer).distinctById(),
                        limit = it.limit?.copy(remaining = r.remainingLimit),
                    )
                }
                watch(r.answerId)
            } catch (e: AppError) {
                _state.update { it.copy(sending = false) }
                when {
                    e is AppError.Api && e.code == ErrorCodes.CONSENT_REQUIRED -> _state.update { it.copy(event = ChatEvent.OPEN_CONSENT) }
                    e is AppError.Api && e.code == ErrorCodes.LIMIT_EXCEEDED -> {
                        // Not stored by the server (api.md 7.1): tomorrow the same key would pass, but it is a new day.
                        pending = null
                        _state.update { it.copy(limit = it.limit?.copy(remaining = 0) ?: AssistantLimit(0, 5, e.resetsAt ?: clock.now())) }
                    }
                    e is AppError.Offline -> _state.update { it.copy(offline = true) }
                    else -> _state.update { it.copy(sendFailed = true) }
                }
            }
        }
    }

    /**
     * 409 REQUEST_IN_PROGRESS: the first request with this key is still running — wait `Retry-After` and repeat
     * [request] (with the same key). Still in progress after the retries — the last 409 is thrown.
     */
    private suspend fun <T> whileInProgress(request: suspend () -> T): T {
        repeat(IN_PROGRESS_RETRIES) {
            try {
                return request()
            } catch (e: AppError.InProgress) {
                delay((e.retryAfterSeconds ?: 2).coerceIn(1, 30) * 1000L)
            }
        }
        return request()
    }

    /**
     * «Повторить» under the offline / failure notice: re-sends the waiting question with its key, re-opens
     * streams and reloads the thread if it never loaded.
     */
    fun retryAfterFailure() {
        _state.update { it.copy(offline = false, sendFailed = false) }
        if (_state.value.items.isEmpty() && !_state.value.loading) load()
        resumeStreams()
        pending?.first?.let { send(it) }
    }

    /** Consent granted on the consent screen: send the waiting question with the same key. */
    fun onConsentGranted() {
        tracker.track(Events.ASSISTANT_CONSENT_GRANTED)
        pending?.first?.let { send(it) }
    }

    fun consumeEvent() = _state.update { it.copy(event = null) }

    /** «Повторить» under a failed answer the server marked retryable: a new answer replaces it (api.md 7.4). */
    fun retry(answerId: String) {
        // A double tap: the first request is still on its way, a second one with its key would only get 409.
        if (!retrying.add(answerId)) return
        // One logical action per failed answer: the same key until a final response (a lost 201 is replayed).
        val key = retryKeys.getOrPut(answerId) { IdempotencyKey.random() }
        viewModelScope.launch {
            try {
                val r = whileInProgress { api.retry(key, answerId) }
                retryKeys.remove(answerId)
                _state.update { s ->
                    s.copy(
                        items = s.items.map { item ->
                            if (item.id == answerId && item is ChatItem.Answer) ChatItem.Answer(r.answerId, item.createdAt, AnswerStatus.GENERATING) else item
                        }.distinctById(),
                        limit = s.limit?.copy(remaining = r.remainingLimit),
                    )
                }
                watch(r.answerId)
            } catch (e: AppError) {
                when {
                    e is AppError.Api && e.code == ErrorCodes.LIMIT_EXCEEDED -> _state.update { it.copy(limit = it.limit?.copy(remaining = 0)) }
                    e is AppError.Api && e.code == ErrorCodes.CONSENT_REQUIRED -> _state.update { it.copy(event = ChatEvent.OPEN_CONSENT) }
                    // Already replaced (e.g. the first 201 was lost after all): the history has the truth.
                    e is AppError.Api && e.code == ErrorCodes.RETRY_NOT_ALLOWED -> {
                        retryKeys.remove(answerId)
                        load()
                    }
                    e is AppError.Offline -> _state.update { it.copy(offline = true) }
                    else -> _state.update { it.copy(sendFailed = true) }
                }
            } finally {
                retrying.remove(answerId)
            }
        }
    }

    private fun watch(answerId: String) {
        if (streams[answerId]?.isActive == true) return
        streams[answerId] = viewModelScope.launch {
            // The answer keeps generating on the server; a dropped stream (network, 5xx, closed without `done` / `error`)
            // is re-opened by [reopeningStream] — the first event is a full snapshot — until a terminal event or the
            // screen stops.
            var reload = false
            try {
                try {
                    reopeningStream(isTerminal = ::isTerminal) { api.streamAnswer(answerId) }
                        .collect { event -> applyEvent(answerId, event) }
                    _state.update { it.copy(offline = false) }
                } catch (e: AppError) {
                    // Not a drop (404 and the like): the stream will not recover by itself.
                    reload = true
                }
            } finally {
                if (streams[answerId] === coroutineContext[Job]) streams.remove(answerId)
            }
            // The answer would stay «generating» and block the composer until the screen is reopened (FIN-75): the
            // history holds its real status.
            if (reload) reload(skipStream = answerId)
        }
    }

    private fun isTerminal(event: AnswerEvent): Boolean = when (event) {
        is AnswerEvent.Done, is AnswerEvent.Error -> true
        is AnswerEvent.Snapshot -> event.event.status.effective != AnswerStatus.GENERATING
        else -> false
    }

    private fun applyEvent(answerId: String, event: AnswerEvent) = _state.update { s ->
        val items = s.items.map { item ->
            if (item !is ChatItem.Answer || item.id != answerId) return@map item
            when (event) {
                is AnswerEvent.Snapshot -> item.copy(status = event.event.status.effective, blocks = event.event.blocks, chips = event.event.chips, source = event.event.source, currency = event.event.currency)
                is AnswerEvent.Chunk -> item.copy(blocks = appendChunk(item.blocks, event.event.index, event.event.text))
                is AnswerEvent.BlockAdded -> item.copy(blocks = item.blocks.take(event.event.index) + event.event.block)
                is AnswerEvent.Source -> item.copy(source = event.event)
                is AnswerEvent.ChipAdded -> item.copy(chips = item.chips + event.event)
                is AnswerEvent.Done -> item.copy(status = AnswerStatus.COMPLETE)
                // Shown blocks are removed on error (api.md 7.2).
                is AnswerEvent.Error -> item.copy(
                    status = AnswerStatus.FAILED, blocks = emptyList(), chips = emptyList(),
                    errorText = event.event.text.presentText(), retryable = answerRetryable(event.event.code, event.event.retryable),
                )
                is AnswerEvent.Unknown -> item
            }
        }
        val limit = when (event) {
            is AnswerEvent.Done -> s.limit?.copy(remaining = event.event.remainingLimit)
            is AnswerEvent.Error -> s.limit?.copy(remaining = event.event.remainingLimit)
            else -> s.limit
        }
        if (event is AnswerEvent.Done) tracker.track(Events.ASSISTANT_ANSWER_DONE, mapOf("charged" to event.event.charged.toString()))
        if (event is AnswerEvent.Error) tracker.track(Events.ASSISTANT_ANSWER_FAILED, mapOf("code" to event.event.code.code))
        s.copy(items = items, limit = limit)
    }

    private fun toItem(m: Message): ChatItem? = when (m.role) {
        MessageRole.USER -> ChatItem.Question(m.id, m.createdAt, m.text.orEmpty())
        MessageRole.ASSISTANT -> {
            // Error fields belong to `failed` only: an unknown (or missing) status shown as failed has no code, so it gets
            // the generic text and no «Повторить» (api.md 7.3).
            val failed = m.status == AnswerStatus.FAILED
            ChatItem.Answer(
                id = m.id,
                createdAt = m.createdAt,
                status = m.status?.effective ?: AnswerStatus.FAILED,
                blocks = m.blocks.orEmpty(),
                chips = m.chips.orEmpty(),
                source = m.source,
                errorText = m.errorText.takeIf { failed }.presentText(),
                retryable = failed && answerRetryable(m.errorCode, m.retryable),
                currency = m.currency,
            )
        }
        MessageRole.UNKNOWN -> null
    }

    /** A blank server text is no text: the generic one is shown instead of an empty notice. */
    private fun String?.presentText(): String? = this?.takeIf { it.isNotBlank() }

    /** `LazyColumn` keys by id: a duplicate crashes the list, so the first occurrence wins. */
    private fun List<ChatItem>.distinctById(): List<ChatItem> = distinctBy { it.id }

    companion object {
        private const val PAGE = 20
        private const val IN_PROGRESS_RETRIES = 3
        private const val MAX_LENGTH = 2000
        private const val KEY_PENDING_TEXT = "chat.pending.text"
        private const val KEY_PENDING_KEY = "chat.pending.key"

        /**
         * `chunk` rule: index equal to the number of blocks starts a new text block; a smaller one appends to
         * that block's text.
         */
        fun appendChunk(blocks: List<Block>, index: Int, text: String): List<Block> = when {
            index >= blocks.size -> blocks + Block.text(text)
            else -> blocks.mapIndexed { i, b -> if (i == index) Block.text(b.text.orEmpty() + text) else b }
        }
    }
}
