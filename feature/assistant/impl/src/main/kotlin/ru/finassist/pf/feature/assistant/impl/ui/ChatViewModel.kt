package ru.finassist.pf.feature.assistant.impl.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.Chip
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.Message
import ru.finassist.pf.core.api.model.MessageRole
import ru.finassist.pf.core.api.model.TransferMode
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
        val errorCode: AnswerErrorCode? = null,
    ) : ChatItem
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
    savedState: SavedStateHandle,
    private val api: AssistantApi,
    private val tracker: Tracker,
) : ViewModel() {
    private val transferMode: TransferMode =
        TransferMode.entries.firstOrNull { it.code == savedState.toRoute<AssistantRoutes.Chat>().transferMode } ?: TransferMode.WITH

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state
    private val streams = HashMap<String, Job>()

    /** Text + key of the question being sent; reused on retry after a network failure or consent. */
    private var pending: Pair<String, IdempotencyKey>? = null

    init {
        tracker.track(Events.ASSISTANT_OPENED)
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty(), loadFailed = false) }
            try {
                val list = api.listMessages(limit = PAGE)
                _state.update { it.copy(loading = false, items = list.messages.mapNotNull(::toItem), hasOlder = list.hasOlder, offline = false) }
                refreshLimit()
                resumeStreams()
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
                _state.update { st -> st.copy(loadingOlder = false, items = list.messages.mapNotNull(::toItem) + st.items, hasOlder = list.hasOlder) }
            } catch (e: AppError) {
                _state.update { it.copy(loadingOlder = false) }
            }
        }
    }

    private suspend fun refreshLimit() {
        runCatching { api.getLimit() }.onSuccess { l -> _state.update { it.copy(limit = l) } }
    }

    /** Called on resume: re-open streams of answers still generating. */
    fun resumeStreams() {
        _state.value.items.filterIsInstance<ChatItem.Answer>().filter { it.status == AnswerStatus.GENERATING }.forEach { watch(it.id) }
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
        viewModelScope.launch {
            _state.update { it.copy(sending = true, draft = text) }
            try {
                val r = api.ask(key, AskRequest(text = text, transferMode = transferMode))
                pending = null
                tracker.track(Events.ASSISTANT_QUESTION_SENT)
                val question = ChatItem.Question(r.question.id, r.question.createdAt, r.question.text ?: text)
                val answer = ChatItem.Answer(r.answerId, r.question.createdAt, AnswerStatus.GENERATING)
                _state.update {
                    it.copy(
                        sending = false, draft = "", offline = false,
                        items = it.items + question + answer,
                        limit = it.limit?.copy(remaining = r.remainingLimit),
                    )
                }
                watch(r.answerId)
            } catch (e: AppError) {
                _state.update { it.copy(sending = false) }
                when {
                    e is AppError.Api && e.code == ErrorCodes.CONSENT_REQUIRED -> _state.update { it.copy(event = ChatEvent.OPEN_CONSENT) }
                    e is AppError.Api && e.code == ErrorCodes.LIMIT_EXCEEDED -> {
                        pending = null
                        _state.update { it.copy(limit = it.limit?.copy(remaining = 0) ?: AssistantLimit(0, 5, e.resetsAt ?: OffsetDateTime.now())) }
                    }
                    e is AppError.Offline -> _state.update { it.copy(offline = true) }
                    else -> Unit
                }
            }
        }
    }

    /** Consent granted on the consent screen: send the waiting question with the same key. */
    fun onConsentGranted() {
        tracker.track(Events.ASSISTANT_CONSENT_GRANTED)
        pending?.first?.let { send(it) }
    }

    fun consumeEvent() = _state.update { it.copy(event = null) }

    /** «Повторить» after MODEL_ERROR: a new answer replaces the failed one (api.md 7.4). */
    fun retry(answerId: String) {
        viewModelScope.launch {
            try {
                val r = api.retry(IdempotencyKey.random(), answerId)
                _state.update { s ->
                    s.copy(
                        items = s.items.map { item ->
                            if (item.id == answerId && item is ChatItem.Answer) ChatItem.Answer(r.answerId, item.createdAt, AnswerStatus.GENERATING) else item
                        },
                        limit = s.limit?.copy(remaining = r.remainingLimit),
                    )
                }
                watch(r.answerId)
            } catch (e: AppError) {
                when {
                    e is AppError.Api && e.code == ErrorCodes.LIMIT_EXCEEDED -> _state.update { it.copy(limit = it.limit?.copy(remaining = 0)) }
                    e is AppError.Api && e.code == ErrorCodes.CONSENT_REQUIRED -> _state.update { it.copy(event = ChatEvent.OPEN_CONSENT) }
                    e is AppError.Offline -> _state.update { it.copy(offline = true) }
                    else -> Unit
                }
            }
        }
    }

    private fun watch(answerId: String) {
        if (streams[answerId]?.isActive == true) return
        streams[answerId] = viewModelScope.launch {
            try {
                api.streamAnswer(answerId).collect { event -> applyEvent(answerId, event) }
                _state.update { it.copy(offline = false) }
            } catch (e: AppError) {
                // Dropped connection: the answer keeps generating on the server; re-opened on resume or retry tap.
                if (e is AppError.Offline) _state.update { it.copy(offline = true) }
            } finally {
                if (streams[answerId] === coroutineContext[Job]) streams.remove(answerId)
            }
        }
    }

    private fun applyEvent(answerId: String, event: AnswerEvent) = _state.update { s ->
        val items = s.items.map { item ->
            if (item !is ChatItem.Answer || item.id != answerId) return@map item
            when (event) {
                is AnswerEvent.Snapshot -> item.copy(status = event.event.status.effective, blocks = event.event.blocks, chips = event.event.chips, source = event.event.source)
                is AnswerEvent.Chunk -> item.copy(blocks = appendChunk(item.blocks, event.event.index, event.event.text))
                is AnswerEvent.BlockAdded -> item.copy(blocks = item.blocks.take(event.event.index) + event.event.block)
                is AnswerEvent.Source -> item.copy(source = event.event)
                is AnswerEvent.ChipAdded -> item.copy(chips = item.chips + event.event)
                is AnswerEvent.Done -> item.copy(status = AnswerStatus.COMPLETE)
                // Shown blocks are removed on error (api.md 7.2).
                is AnswerEvent.Error -> item.copy(status = AnswerStatus.FAILED, blocks = emptyList(), chips = emptyList(), errorCode = event.event.code.effective)
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
        MessageRole.ASSISTANT -> ChatItem.Answer(
            id = m.id,
            createdAt = m.createdAt,
            status = m.status?.effective ?: AnswerStatus.FAILED,
            blocks = m.blocks.orEmpty(),
            chips = m.chips.orEmpty(),
            source = m.source,
            errorCode = m.errorCode?.effective,
        )
        MessageRole.UNKNOWN -> null
    }

    companion object {
        private const val PAGE = 20
        private const val MAX_LENGTH = 2000

        /**
         * `chunk` rule: index equal to the number of blocks starts a new text block; a smaller one appends to
         * that block's text.
         */
        fun appendChunk(blocks: List<Block>, index: Int, text: String): List<Block> = when {
            index >= blocks.size -> blocks + Block(type = BlockType.TEXT, text = text)
            else -> blocks.mapIndexed { i, b -> if (i == index) b.copy(text = b.text.orEmpty() + text) else b }
        }
    }
}
