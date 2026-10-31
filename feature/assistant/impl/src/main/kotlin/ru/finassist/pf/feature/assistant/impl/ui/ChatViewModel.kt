package ru.finassist.pf.feature.assistant.impl.ui

import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorContext
import ru.finassist.pf.core.common.time.ElapsedClock
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
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.SseReopenState
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
        val errorPresentation: ErrorPresentation = ErrorPresentation(),
        val errorCode: String? = null,
    ) : ChatItem {
        /** What a failed answer shows: the server's text, or the generic one without it. */
        val failureText: String get() = errorPresentation.textOrFallback(GENERIC_ANSWER_ERROR, errorText)
    }
}

enum class ChatEvent { OPEN_CONSENT }

enum class ChatRecoveryTarget { ASK, RETRY_ANSWER }

/** Local unknown-outcome state: a new logical request requires an explicit user action. */
data class ChatRecovery(val target: ChatRecoveryTarget, val answerId: String? = null, val historyChecked: Boolean = false)

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
    val errorPresentation: ErrorPresentation? = null,
    val limitErrorPresentation: ErrorPresentation? = null,
    val requestRetryable: Boolean? = null,
    val askBlocked: Boolean = false,
    val askRetryReady: Boolean = true,
    val loadRetryReady: Boolean = true,
    val olderRetryReady: Boolean = true,
    val blockedRetryAnswerId: String? = null,
    val retryReadyByAnswer: Map<String, Boolean> = emptyMap(),
    val failedRetryAnswerId: String? = null,
    val loadErrorPresentation: ErrorPresentation? = null,
    val loadRetryable: Boolean? = null,
    val olderErrorPresentation: ErrorPresentation? = null,
    val olderRetryable: Boolean? = null,
    val streamErrorPresentation: ErrorPresentation? = null,
    val streamRetryable: Boolean? = null,
    val recovery: ChatRecovery? = null,
    val consentRequired: Boolean = false,
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
    private val elapsedClock: ElapsedClock,
    private val profileApi: ProfileApi,
) : ViewModel() {
    private val transferMode: TransferMode =
        TransferMode.entries.firstOrNull { it.code == savedState.toRoute<AssistantRoutes.Chat>().transferMode } ?: TransferMode.WITH

    private val limitRetryGate = RequestRetryGate(elapsedClock)
    private val _state = MutableStateFlow(ChatUiState())
    private val askGate = RequestRetryGate(elapsedClock)
    private val loadGate = RequestRetryGate(elapsedClock)
    private val olderGate = RequestRetryGate(elapsedClock)
    private val consentReadGate = RequestRetryGate(elapsedClock)
    private var consentReadJob: Job? = null
    private var consentRevision = 0L
    private val retryGates = mutableMapOf<String, RequestRetryGate>()
    init {
        viewModelScope.launch { askGate.readiness.collect { ready -> _state.update { it.copy(askRetryReady = ready) } } }
        viewModelScope.launch { loadGate.readiness.collect { ready -> _state.update { it.copy(loadRetryReady = ready) } } }
        viewModelScope.launch { olderGate.readiness.collect { ready -> _state.update { it.copy(olderRetryReady = ready) } } }
    }
    val state: StateFlow<ChatUiState> = _state
    /** Re-open state of each answer's stream for the life of the screen ([SseReopenState]). */
    private val reopenStates = HashMap<String, SseReopenState>()

    private val streams = HashMap<String, Job>()
    private var wasPresented = false
    private val retryKeys = HashMap<String, IdempotencyKey>()
    private var historyReadRevision = 0L
    /** A successful RetryAnswer invalidates snapshots begun before its in-place replacement. */
    private var replacementRevision = 0L
    /** Only locally accepted Ask messages enter this registry; GET pagination never does. */
    private var acceptanceRevision = 0L
    private val acceptedMessageRevisions = HashMap<String, Long>()
    /** Failed answers whose «Повторить» is in flight: a second tap must not send the same key again (FIN-75). */
    private val retrying = HashSet<String>()

    /**
     * Text + key of the question being sent, until a final response. «Задать этот вопрос» is one action per
     * send, not per text: a retry after a dropped connection or corrected consent reuses the proven key.
     * Process restoration retains its UUID, not its age proof: read history and ask for an explicit new intent
     * before creating another key. The same text asked after a completed action is a new question.
     */
    private var pendingKey: IdempotencyKey? = null
    private var blockedAskText: String? = null
    private sealed interface PendingConsentRequest {
        data object Ask : PendingConsentRequest
        data class Retry(val answerId: String) : PendingConsentRequest
    }
    private var pendingConsentRequest: PendingConsentRequest? = null

    private var pending: Pair<String, IdempotencyKey>?
        get() {
            val text = savedState.get<String>(KEY_PENDING_TEXT) ?: return null
            val key = savedState.get<String>(KEY_PENDING_KEY) ?: return null
            return text to (pendingKey?.takeIf { it.value == key } ?: IdempotencyKey(key))
        }
        set(value) {
            pendingKey = value?.second
            savedState[KEY_PENDING_TEXT] = value?.first
            savedState[KEY_PENDING_KEY] = value?.second?.value
        }

    init {
        tracker.track(Events.ASSISTANT_OPENED)
        // Restored after process death: the unsent question is back in the composer, «Отправить» repeats it.
        pending?.let { (text, _) -> _state.update { it.copy(draft = text) } }
        load()
    }

    fun load() { if (loadGate.isReady()) reload(skipStream = null) }

    /**
     * Reads the latest page of the thread; on failure a shown thread stays as it is. [skipStream] is an answer
     * whose stream has just failed for good: it is not re-opened from here even if the history still says it is
     * generating, or a server that keeps answering the stream with 404 would loop (the next resume tries again).
     */
    private fun reload(skipStream: String?) {
        if (!loadGate.isReady()) return
        val readRevision = ++historyReadRevision
        viewModelScope.launch {
            _state.update { it.copy(loading = it.items.isEmpty(), loadFailed = false, loadErrorPresentation = null, loadRetryable = null) }
            val mutationRevision = replacementRevision
            val acceptedBeforeRead = acceptanceRevision
            try {
                val beforeRead = _state.value.items
                val list = api.listMessages(limit = PAGE)
                if (readRevision != historyReadRevision) return@launch
                if (mutationRevision != replacementRevision) {
                    _state.update { it.copy(loading = false) }
                    reload(skipStream)
                    return@launch
                }
                val items = list.messages.mapNotNull(::toItem).distinctById()
                _state.update { current ->
                    val priorById = beforeRead.associateBy { it.id }
                    val currentById = current.items.associateBy { it.id }
                    val firstFresh = items.firstOrNull()
                    val overlap = firstFresh?.let { first -> current.items.indexOfFirst { it.id == first.id } } ?: -1
                    // Without a shared id the pages cannot prove a contiguous prefix. Start pagination at
                    // the server's fresh boundary instead of hiding a gap behind a cached hasOlder=false.
                    val older = if (overlap >= 0) current.items.take(overlap)
                    else if (firstFresh != null) emptyList()
                    else current.items
                    // A POST or SSE event accepted after this GET started is newer than its snapshot.
                    val fresh = items.map { item -> currentById[item.id]?.takeIf { it != priorById[item.id] } ?: item }
                    val acceptedDuringRead = current.items.filter {
                        (acceptedMessageRevisions[it.id] ?: 0L) > acceptedBeforeRead &&
                            it.id !in priorById && items.none { freshItem -> freshItem.id == it.id }
                    }
                    val merged = (older + fresh + acceptedDuringRead).distinctById()
                    val recovery = current.recovery?.let { candidate ->
                        if (candidate.target == ChatRecoveryTarget.ASK) candidate
                        else candidate.copy(historyChecked = true).takeIf {
                            items.filterIsInstance<ChatItem.Answer>().any { answer ->
                                answer.id == candidate.answerId && answer.status == AnswerStatus.FAILED && answer.retryable
                            }
                        }
                    }
                    current.copy(loading = false, items = merged, hasOlder = if (older.isNotEmpty()) current.hasOlder else list.hasOlder,
                        offline = false, recovery = recovery)
                }
                refreshLimit()
                watchGenerating(skip = skipStream)
            } catch (e: AppError) {
                loadGate.record(e)
                if (readRevision != historyReadRevision) return@launch
                if (mutationRevision != replacementRevision && loadGate.isReady()) {
                    _state.update { it.copy(loading = false) }
                    reload(skipStream)
                    return@launch
                }
                _state.update { it.copy(loading = false, loadFailed = it.items.isEmpty(), offline = e is AppError.Offline, loadErrorPresentation = e.presentation.takeUnless { e is AppError.Offline }, loadRetryable = e.requestRetryable) }
            }
        }
    }

    fun loadOlder() {
        if (!olderGate.isReady()) return
        val s = _state.value
        val first = s.items.firstOrNull() ?: return
        if (!s.hasOlder || s.loadingOlder) return
        viewModelScope.launch {
            _state.update { it.copy(loadingOlder = true, olderErrorPresentation = null, olderRetryable = null) }
            try {
                val list = api.listMessages(limit = PAGE, beforeId = first.id)
                if (_state.value.items.firstOrNull()?.id != first.id) {
                    _state.update { it.copy(loadingOlder = false) }
                    return@launch
                }
                _state.update { st -> st.copy(loadingOlder = false,
                    items = (list.messages.mapNotNull(::toItem) + st.items).distinctById(), hasOlder = list.hasOlder) }
            } catch (e: AppError) {
                olderGate.record(e)
                if (_state.value.items.firstOrNull()?.id != first.id) {
                    _state.update { it.copy(loadingOlder = false) }
                    return@launch
                }
                _state.update { it.copy(loadingOlder = false, olderErrorPresentation = e.presentation, olderRetryable = e.requestRetryable) }
            }
        }
    }

    private suspend fun refreshLimit() {
        if (!limitRetryGate.isReady()) return
        runCatching { api.getLimit() }.onSuccess { l -> _state.update { it.copy(limit = l, limitErrorPresentation = null) } }
            .onFailure { failure -> if (failure is AppError) {
                limitRetryGate.record(failure)
                if (failure !is AppError.Offline) _state.update { it.copy(limitErrorPresentation = failure.presentation) }
            } }
    }

    /** A saved tab keeps its draft; re-reading on later visits updates history and changed-since markers. */
    fun onResumed() {
        if (wasPresented && !_state.value.sending) load() else wasPresented = true
        refreshConsentIfRequired()
        resumeStreams()
    }

    /** A consent accepted in Profile can correct this retained tab; a failed read never lifts a refusal. */
    private fun refreshConsentIfRequired() {
        if (!_state.value.consentRequired || consentReadJob?.isActive == true || !consentReadGate.isReady()) return
        val revision = consentRevision
        consentReadJob = viewModelScope.launch {
            try {
                val consent = profileApi.getProfile().assistantConsent
                if (consent == null || consent.needsRenewal || revision != consentRevision || !_state.value.consentRequired) return@launch
                when (val operation = pendingConsentRequest) {
                    PendingConsentRequest.Ask -> enableConsentedDraft()
                    is PendingConsentRequest.Retry -> {
                        retryGates[operation.answerId]?.clear()
                        _state.update { it.copy(blockedRetryAnswerId = null, errorPresentation = null, requestRetryable = null) }
                    }
                    null -> Unit
                }
                pendingConsentRequest = null
                _state.update { it.copy(consentRequired = false) }
            } catch (error: AppError) {
                consentReadGate.record(error)
            }
        }
    }

    /** Re-open streams of answers still generating. */
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

    fun setDraft(text: String) {
        if (text.take(MAX_LENGTH) == _state.value.draft) return
        if (text.take(MAX_LENGTH).trim() == _state.value.draft.trim()) {
            _state.update { it.copy(draft = text.take(MAX_LENGTH)) }
            return
        }
        blockedAskText = null
        pending = null
        askGate.clear()
        _state.update { it.copy(draft = text.take(MAX_LENGTH), errorPresentation = null, requestRetryable = null, askBlocked = false, sendFailed = false, recovery = null) }
    }

    /** Sends the draft (or [voiceText] right away — voice input is sent immediately, api.md / mvp-scope). */
    fun send(voiceText: String? = null) {
        voiceText?.let(::setDraft) // voice is the same bounded, normalized user-input context as typing
        val text = _state.value.draft.trim()
        if (!askGate.isReady() || _state.value.consentRequired || text.isEmpty() || _state.value.sending || _state.value.generating || blockedAskText == text) return
        val key = pending?.takeIf { it.first == text }?.second ?: IdempotencyKey.random(elapsedClock)
        pending = text to key
        if (key.replayWindow?.isOpen(elapsedClock) != true) {
            // The restored/expired UUID is retained for recovery, but cannot prove a safe POST replay.
            requireRecovery(ChatRecovery(ChatRecoveryTarget.ASK))
            return
        }
        // Set before the coroutine starts: a double tap must not launch a second send.
        _state.update { it.copy(sending = true, draft = text, sendFailed = false, failedRetryAnswerId = null, errorPresentation = null, requestRetryable = null) }
        viewModelScope.launch {
            try {
                val r = whileInProgress(key) { api.ask(key, AskRequest(text = text, transferMode = transferMode)) }
                pending = null
                tracker.track(Events.ASSISTANT_QUESTION_SENT)
                val question = ChatItem.Question(r.question.id, r.question.createdAt, r.question.text ?: text)
                val answer = ChatItem.Answer(r.answerId, r.question.createdAt, AnswerStatus.GENERATING)
                val accepted = ++acceptanceRevision
                acceptedMessageRevisions[question.id] = accepted
                acceptedMessageRevisions[answer.id] = accepted
                _state.update {
                    it.copy(
                        sending = false, draft = if (it.draft.trim() == text) "" else it.draft, offline = false,
                        // A replayed 201 carries ids the reloaded history may already hold: keep those.
                        items = (it.items + question + answer).distinctById(),
                        limit = it.limit?.copy(remaining = r.remainingLimit),
                    )
                }
                watch(r.answerId)
            } catch (e: AppError) {
                if (_state.value.draft.trim() != text) {
                    _state.update { it.copy(sending = false) } // a late result cannot overwrite a newer voice/typed draft
                    return@launch
                }
                askGate.record(e)
                if (e.requestRetryable == false) blockedAskText = text
                _state.update { it.copy(sending = false, errorPresentation = e.presentation.takeUnless { e is AppError.Offline }, requestRetryable = e.requestRetryable, askBlocked = e.requestRetryable == false) }
                when {
                    e is AppError.Api && e.response?.context != ErrorContext.RESULT_EVENT && e.code == ErrorCodes.CONSENT_REQUIRED -> {
                        consentRevision++
                        pendingConsentRequest = PendingConsentRequest.Ask
                        _state.update { it.copy(event = ChatEvent.OPEN_CONSENT, consentRequired = true, askBlocked = true) }
                    }
                    e is AppError.Api && e.response?.context != ErrorContext.RESULT_EVENT && e.code == ErrorCodes.LIMIT_EXCEEDED -> {
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
    private suspend fun <T> whileInProgress(key: IdempotencyKey, request: suspend () -> T): T {
        repeat(IN_PROGRESS_RETRIES) {
            try {
                return request()
            } catch (e: AppError.InProgress) {
                if (e.requestRetryable == false || key.replayWindow?.isOpen(elapsedClock) != true) throw e
                delay((e.retryAfterSeconds ?: 2).coerceAtLeast(1) * 1000L)
                if (key.replayWindow?.isOpen(elapsedClock) != true) throw e
            }
        }
        return request()
    }

    /**
     * «Повторить» under the offline / failure notice: re-sends the waiting question with its key, re-opens
     * streams and reloads the thread if it never loaded.
     */
    fun retryAfterFailure() {
        if (!askGate.isReady()) return
        _state.update { it.copy(offline = false, sendFailed = false) }
        if (_state.value.items.isEmpty() && !_state.value.loading) load()
        resumeStreams()
        pending?.first?.let { send(it) }
    }

    /** Reopens the corrective screen without sending a denied request again. */
    fun openConsent() {
        if (_state.value.consentRequired) _state.update { it.copy(event = ChatEvent.OPEN_CONSENT) }
    }

    /** Corrected Ask consent enables the draft; sending still requires an explicit user action. */
    fun onConsentGranted() {
        val operation = pendingConsentRequest
        pendingConsentRequest = null
        _state.update { it.copy(consentRequired = false) }
        if (operation is PendingConsentRequest.Retry) {
            val id = operation.answerId
            val key = retryKeys[id]
            tracker.track(Events.ASSISTANT_CONSENT_GRANTED)
            if (key?.replayWindow?.isOpen(elapsedClock) != true) {
                requireRecovery(ChatRecovery(ChatRecoveryTarget.RETRY_ANSWER, id))
                return
            }
            retryGates[id]?.clear()
            _state.update { it.copy(blockedRetryAnswerId = null, errorPresentation = null, requestRetryable = null) }
            retry(id)
            return
        }
        val waiting = pending
        if (operation == PendingConsentRequest.Ask) {
            tracker.track(Events.ASSISTANT_CONSENT_GRANTED)
            enableConsentedDraft()
        }
        if (waiting == null) return
        if (waiting.second.replayWindow?.isOpen(elapsedClock) != true) {
            requireRecovery(ChatRecovery(ChatRecoveryTarget.ASK))
            return
        }
    }

    private fun enableConsentedDraft() {
        blockedAskText = null
        askGate.clear()
        _state.update { it.copy(requestRetryable = null, errorPresentation = null, askBlocked = false) }
    }

    private fun requireRecovery(recovery: ChatRecovery) {
        _state.update { it.copy(recovery = recovery, askBlocked = it.askBlocked || recovery.target == ChatRecoveryTarget.ASK,
            errorPresentation = null, sendFailed = false, offline = false,
            blockedRetryAnswerId = recovery.answerId ?: it.blockedRetryAnswerId) }
        load() // a read may recover an accepted replacement; it never renews an operation UUID
    }

    /** A separate logical operation, created only by the explicit recovery button after the unknown-outcome warning. */
    fun startNewRequest() {
        val recovery = _state.value.recovery ?: return
        if (_state.value.sending || _state.value.generating || _state.value.limitExhausted) return
        when (recovery.target) {
            ChatRecoveryTarget.ASK -> {
                val text = pending?.first ?: return
                pending = null
                pendingConsentRequest = null
                blockedAskText = null
                askGate.clear()
                _state.update { it.copy(recovery = null, askBlocked = false, sendFailed = false, offline = false, errorPresentation = null) }
                send(text)
            }
            ChatRecoveryTarget.RETRY_ANSWER -> {
                val id = recovery.answerId ?: return
                if (!recovery.historyChecked || _state.value.items.filterIsInstance<ChatItem.Answer>().none {
                    it.id == id && it.status == AnswerStatus.FAILED && it.retryable
                }) return
                retryKeys.remove(id) // explicit new intent ends only the old pending retry request
                retryGates[id]?.clear()
                pendingConsentRequest = null
                _state.update { it.copy(recovery = null, blockedRetryAnswerId = null, failedRetryAnswerId = null,
                    sendFailed = false, offline = false, errorPresentation = null) }
                retry(id)
            }
        }
    }

    fun consumeEvent() = _state.update { it.copy(event = null) }

    /** «Повторить» under a failed answer the server marked retryable: a new answer replaces it (api.md 7.4). */
    fun retry(answerId: String) {
        val retryGate = retryGates.getOrPut(answerId) {
            RequestRetryGate(elapsedClock).also { gate ->
                viewModelScope.launch { gate.readiness.collect { ready ->
                    _state.update { it.copy(retryReadyByAnswer = it.retryReadyByAnswer + (answerId to ready)) }
                } }
            }
        }
        if (!retryGate.isReady()) return
        // A double tap: the first request is still on its way, a second one with its key would only get 409.
        if (!retrying.add(answerId)) return
        // One logical action per failed answer: the same key until a final response (a lost 201 is replayed).
        val key = retryKeys.getOrPut(answerId) { IdempotencyKey.random(elapsedClock) }
        if (key.replayWindow?.isOpen(elapsedClock) != true) {
            retrying.remove(answerId)
            requireRecovery(ChatRecovery(ChatRecoveryTarget.RETRY_ANSWER, answerId))
            return
        }
        viewModelScope.launch {
            try {
                val r = whileInProgress(key) { api.retry(key, answerId) }
                retryKeys.remove(answerId)
                replacementRevision++
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
                retryGate.record(e)
                _state.update { it.copy(errorPresentation = e.presentation.takeUnless { e is AppError.Offline }, requestRetryable = e.requestRetryable, blockedRetryAnswerId = answerId.takeIf { e.requestRetryable == false }, failedRetryAnswerId = answerId) }
                when {
                    e is AppError.Api && e.response?.context != ErrorContext.RESULT_EVENT && e.code == ErrorCodes.LIMIT_EXCEEDED -> _state.update { it.copy(limit = it.limit?.copy(remaining = 0)) }
                    e is AppError.Api && e.response?.context != ErrorContext.RESULT_EVENT && e.code == ErrorCodes.CONSENT_REQUIRED -> {
                        consentRevision++
                        pendingConsentRequest = PendingConsentRequest.Retry(answerId)
                        _state.update { it.copy(event = ChatEvent.OPEN_CONSENT, consentRequired = true) }
                    }
                    // Already replaced (e.g. the first 201 was lost after all): the history has the truth.
                    e is AppError.Api && e.response?.context != ErrorContext.RESULT_EVENT && e.code == ErrorCodes.RETRY_NOT_ALLOWED -> {
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
                    // Opening it again after the screen came back is a re-open: a 429 is waited for (api.md «SSE»).
                    reopeningStream(isTerminal = ::isTerminal, state = reopenStates.getOrPut(answerId) { SseReopenState() }, elapsedClock = elapsedClock, recoverOnceAfter = { ServiceGate.recoveryDeadline(it, elapsedClock) }) {
                        api.streamAnswer(answerId)
                    }.collect { event -> applyEvent(answerId, event) }
                    _state.update { it.copy(offline = false, streamErrorPresentation = null, streamRetryable = null) }
                } catch (e: AppError) {
                    // Keep the read/open error separate from a failed answer and its new-attempt permission.
                    _state.update { it.copy(streamErrorPresentation = e.presentation.takeUnless { e is AppError.Offline }, streamRetryable = e.requestRetryable) }
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
                    errorText = event.event.text.presentText(), errorPresentation = event.event.presentation, errorCode = event.event.rawCode, retryable = answerRetryable(event.event.code, event.event.retryable),
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
            // Unknown/missing status is displayed as failed and uses the same canonical/legacy text rules (7.3).
            // Its presentation does not make the unknown status's code or new-attempt permission actionable.
            val status = m.status?.effective ?: AnswerStatus.FAILED
            val shownFailure = status == AnswerStatus.FAILED
            val knownFailure = m.status == AnswerStatus.FAILED
            ChatItem.Answer(
                id = m.id,
                createdAt = m.createdAt,
                status = status,
                blocks = m.blocks.orEmpty(),
                chips = m.chips.orEmpty(),
                source = m.source,
                errorText = m.errorText.takeIf { shownFailure }.presentText(),
                errorPresentation = if (shownFailure) m.errorPresentation else ErrorPresentation(),
                errorCode = m.errorRawCode.takeIf { knownFailure },
                retryable = knownFailure && answerRetryable(m.errorCode, m.retryable),
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
