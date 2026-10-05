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
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.network.codes.AnswerErrorCode
import ru.finassist.pf.core.network.codes.AnswerStatus
import ru.finassist.pf.core.network.dto.BlockDto
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flags
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.assistant.api.AssistantRoutes
import ru.finassist.pf.feature.assistant.impl.data.AnswerEvent
import ru.finassist.pf.feature.assistant.impl.data.AssistantRepository
import ru.finassist.pf.feature.assistant.impl.domain.ChatMessage
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: AssistantRepository,
    private val flags: FeatureFlags,
    private val tracker: Tracker,
) : ViewModel() {

    val transferMode: String = savedState.toRoute<AssistantRoutes.Chat>().transferMode

    data class UiState(
        val messages: List<ChatMessage> = emptyList(),
        val loadingHistory: Boolean = true,
        val historyError: AppError? = null,
        val hasOlder: Boolean = false,
        val loadingOlder: Boolean = false,
        val input: String = "",
        /** Answer being streamed right now. */
        val generatingId: String? = null,
        val sending: Boolean = false,
        val offline: Boolean = false,
        val remaining: Int? = null,
        val dailyMax: Int = 5,
        val resetsAt: String? = null,
        /** Consent missing: the entry navigates to the consent screen once. */
        val needsConsent: Boolean = false,
        val sendError: String? = null,
        val enabled: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Key of the question being sent — kept across retries of the same action (api.md «Идемпотентность»). */
    private var askKey: String? = null
    private var pendingText: String? = null
    private var streamJob: Job? = null

    init {
        refreshFlags()
        loadHistory()
        loadLimit()
        checkConsent()
        viewModelScope.launch {
            repo.consentGranted.collect {
                _state.update { it.copy(needsConsent = false) }
                pendingText?.let { text -> pendingText = null; send(text) }
            }
        }
    }

    fun refreshFlags() = _state.update { it.copy(enabled = flags.isEnabled(Flags.assistant)) }

    private fun checkConsent() = viewModelScope.launch {
        runCatching { repo.profile() }.onSuccess { p ->
            val ok = p.assistantConsent.granted && (p.assistantConsent.version == null || p.assistantConsent.version == p.assistantConsent.currentVersion)
            _state.update { it.copy(needsConsent = !ok) }
        }
    }

    fun loadLimit() = viewModelScope.launch {
        runCatching { repo.limit() }.onSuccess { l -> _state.update { it.copy(remaining = l.remaining, dailyMax = l.dailyMax, resetsAt = l.resetsAt) } }
    }

    fun loadHistory() = viewModelScope.launch {
        _state.update { it.copy(loadingHistory = it.messages.isEmpty(), historyError = null) }
        try {
            val h = repo.history()
            val msgs = h.messages.mapNotNull(ChatMessage::from)
            _state.update { it.copy(messages = msgs, loadingHistory = false, hasOlder = h.hasOlder, offline = false) }
            // An answer still generating when the screen opens (app was killed mid-answer) — re-attach.
            msgs.filterIsInstance<ChatMessage.Assistant>().lastOrNull { it.status == AnswerStatus.Generating }?.let { observe(it.id) }
        } catch (e: AppError) {
            _state.update { it.copy(loadingHistory = false, historyError = e, offline = e is AppError.Offline) }
        }
    }

    fun loadOlder() = viewModelScope.launch {
        val s = _state.value
        if (s.loadingOlder || !s.hasOlder) return@launch
        val first = s.messages.firstOrNull()?.id ?: return@launch
        _state.update { it.copy(loadingOlder = true) }
        try {
            val h = repo.history(beforeId = first)
            _state.update { it.copy(messages = h.messages.mapNotNull(ChatMessage::from) + it.messages, hasOlder = h.hasOlder, loadingOlder = false) }
        } catch (e: AppError) { _state.update { it.copy(loadingOlder = false) } }
    }

    fun onInput(text: String) = _state.update { it.copy(input = text, sendError = null) }

    fun send(text: String = _state.value.input.trim()) {
        if (text.isEmpty() || _state.value.sending || _state.value.generatingId != null) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true, sendError = null) }
            val key = askKey ?: UUID.randomUUID().toString().also { askKey = it }
            try {
                val r = repo.ask(key, text, transferMode)
                askKey = null
                tracker.track("assistant.question.sent", mapOf("voice" to "false"))
                val question = ChatMessage.from(r.question) ?: ChatMessage.User(r.question.id, text, OffsetDateTime.now())
                val answer = ChatMessage.Assistant(r.answerId, AnswerStatus.Generating, emptyList(), emptyList(), null, null, null, OffsetDateTime.now())
                _state.update { it.copy(messages = it.messages + question + answer, input = "", sending = false, remaining = r.remainingLimit, offline = false) }
                observe(r.answerId)
            } catch (e: AppError) {
                when (e) {
                    is AppError.ConsentRequired -> { pendingText = text; _state.update { it.copy(sending = false, needsConsent = true) } }
                    is AppError.LimitExceeded -> { askKey = null; _state.update { it.copy(sending = false, remaining = 0, resetsAt = e.resetsAt?.toString() ?: it.resetsAt) } }
                    is AppError.Offline -> _state.update { it.copy(sending = false, offline = true) }
                    is AppError.IdempotencyConflict -> { askKey = null; send(text) }
                    is AppError.Validation -> { askKey = null; _state.update { it.copy(sending = false, sendError = "Вопрос должен быть от 1 до 2000 символов") } }
                    else -> { askKey = null; _state.update { it.copy(sending = false, sendError = "Не получилось отправить вопрос. Повторите позже") } }
                }
            }
        }
    }

    /** «Повторить» after MODEL_ERROR: a new answer replaces the failed one, the question is not duplicated. */
    fun retry(answerId: String) = viewModelScope.launch {
        if (_state.value.generatingId != null) return@launch
        try {
            val r = repo.retry(answerId, UUID.randomUUID().toString())
            _state.update { s ->
                s.copy(
                    remaining = r.remainingLimit,
                    messages = s.messages.map { m -> if (m.id == answerId) ChatMessage.Assistant(r.answerId, AnswerStatus.Generating, emptyList(), emptyList(), null, null, null, OffsetDateTime.now()) else m },
                )
            }
            tracker.track("assistant.answer.retried")
            observe(r.answerId)
        } catch (e: AppError) {
            when (e) {
                is AppError.ConsentRequired -> _state.update { it.copy(needsConsent = true) }
                is AppError.LimitExceeded -> _state.update { it.copy(remaining = 0, resetsAt = e.resetsAt?.toString() ?: it.resetsAt) }
                is AppError.Offline -> _state.update { it.copy(offline = true) }
                is AppError.RetryNotAllowed, is AppError.NotFound -> loadHistory()
                else -> _state.update { it.copy(sendError = "Не получилось повторить. Попробуйте позже") }
            }
        }
    }

    /** Re-open the stream on return from background (the entry calls it on resume). */
    fun resumeStream() { _state.value.generatingId?.let { observe(it) } }

    private fun observe(answerId: String) {
        streamJob?.cancel()
        _state.update { it.copy(generatingId = answerId) }
        streamJob = viewModelScope.launch {
            try {
                repo.answer(answerId).collect { ev ->
                    when (ev) {
                        is AnswerEvent.Snapshot -> update(answerId) { it.copy(status = AnswerStatus.fromWire(ev.snapshot.status), blocks = ev.snapshot.blocks, chips = ev.snapshot.chips, source = ev.snapshot.source) }
                        is AnswerEvent.Chunk -> update(answerId) { m ->
                            val blocks = m.blocks.toMutableList()
                            val i = ev.chunk.index
                            if (i < blocks.size && blocks[i].type == "text") blocks[i] = blocks[i].copy(text = blocks[i].text.orEmpty() + ev.chunk.text)
                            else blocks += BlockDto(type = "text", text = ev.chunk.text)
                            m.copy(blocks = blocks)
                        }
                        is AnswerEvent.Block -> update(answerId) { m ->
                            val blocks = m.blocks.toMutableList()
                            if (ev.block.index < blocks.size) blocks[ev.block.index] = ev.block.block else blocks += ev.block.block
                            m.copy(blocks = blocks)
                        }
                        is AnswerEvent.Source -> update(answerId) { it.copy(source = ev.source) }
                        is AnswerEvent.Chip -> update(answerId) { it.copy(chips = it.chips + ev.chip) }
                        is AnswerEvent.Done -> {
                            update(answerId) { it.copy(status = AnswerStatus.Complete, charged = ev.done.charged) }
                            _state.update { it.copy(generatingId = null, remaining = ev.done.remainingLimit) }
                            tracker.track("assistant.answer.done", mapOf("charged" to ev.done.charged.toString()))
                        }
                        is AnswerEvent.Failed -> {
                            if (ev.code == "NOT_FOUND") { _state.update { it.copy(generatingId = null) }; loadHistory(); return@collect }
                            update(answerId) { it.copy(status = AnswerStatus.Failed, blocks = emptyList(), chips = emptyList(), errorCode = AnswerErrorCode.fromWire(ev.code)) }
                            _state.update { it.copy(generatingId = null, remaining = ev.remainingLimit ?: it.remaining) }
                            tracker.track("assistant.answer.failed", mapOf("code" to ev.code))
                        }
                    }
                }
            } catch (e: AppError) {
                // Non-retryable transport failure: show the answer as failed with a retry; the server refunds on its side.
                update(answerId) { it.copy(status = AnswerStatus.Failed, blocks = emptyList(), errorCode = AnswerErrorCode.ModelError) }
                _state.update { it.copy(generatingId = null) }
            }
        }
    }

    private fun update(answerId: String, f: (ChatMessage.Assistant) -> ChatMessage.Assistant) = _state.update { s ->
        s.copy(messages = s.messages.map { m -> if (m is ChatMessage.Assistant && m.id == answerId) f(m) else m })
    }

    fun onConsentHandled() = _state.update { it.copy(needsConsent = false) }
}
