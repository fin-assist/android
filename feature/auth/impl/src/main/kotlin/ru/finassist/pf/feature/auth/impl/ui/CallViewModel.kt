package ru.finassist.pf.feature.auth.impl.ui

import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.common.error.ErrorContext
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.time.ElapsedClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.SseReopenState
import ru.finassist.pf.core.api.reopeningStream
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.VerificationStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.impl.data.SessionRepositoryImpl
import ru.finassist.pf.feature.auth.impl.domain.CallDeadline
import ru.finassist.pf.feature.auth.impl.domain.CallLockText
import ru.finassist.pf.feature.auth.impl.domain.PhoneFormat
import ru.finassist.pf.feature.auth.impl.domain.SignInFlow
import java.time.Duration
import java.time.ZoneId
import javax.inject.Inject

/**
 * [CHECKING]: the device deadline has passed while waiting; the number is hidden until the stream confirms the status
 * (`expired` → [EXPIRED], `pending` → back to [WAITING] without a time, api.md 1.1 «Клиент, номер подтверждения»).
 */
enum class CallPhase { WAITING, CHECKING, EXPIRED, LOCKED, OFFLINE, ERROR }

data class CallUiState(
    val phoneDisplay: String,
    val callbackNumber: String,
    val phase: CallPhase = CallPhase.WAITING,
    /** «ЧЧ:ММ» of the device deadline in the device zone; null when it is unknown or has passed. */
    val waitUntil: String? = null,
    /** Text of the [CallPhase.LOCKED] state (`CallLockText`). */
    val lockText: String? = null,
    val busy: Boolean = false,
    val errorPresentation: ErrorPresentation? = null,
    val requestRetryable: Boolean? = null,
    val retryReady: Boolean = true,
    val replayUnavailable: Boolean = false,
    val newIntentRequired: Boolean = false,
)

sealed interface CallEvent {
    /** Existing user: tokens saved, the app switches to the main graph by itself. */
    data object SignedIn : CallEvent
    data object NewUser : CallEvent
    data object RegistrationClosed : CallEvent
    data object ConsentRequired : CallEvent
}

/**
 * Waits for the callback: opens the SSE stream, re-opens it on resume (api.md 1.2), and handles the terminal
 * statuses. The dial intent is fired by the screen; the server completes the request when the call arrives.
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val flow: SignInFlow,
    private val session: SessionRepositoryImpl,
    private val flags: FeatureFlags,
    private val tracker: Tracker,
    private val clock: Clock,
    private val elapsedClock: ElapsedClock,
) : ViewModel() {
    private val _state = MutableStateFlow(
        CallUiState(
            phoneDisplay = PhoneFormat.display(PhoneFormat.digits(flow.phone)),
            callbackNumber = flow.verification?.callbackNumber ?: "",
            waitUntil = waitUntilText(),
        ),
    )
    private val retryGate = RequestRetryGate(elapsedClock)
    private val verificationGate = flow.phoneStartGate(elapsedClock)
    private var pendingRequestKey: IdempotencyKey? = null
    init {
        viewModelScope.launch { retryGate.readiness.collect { _ -> updateRetryReady() } }
        viewModelScope.launch { verificationGate.readiness.collect { _ -> updateRetryReady() } }
    }

    private fun updateRetryReady() = _state.update { it.copy(retryReady = if (pendingRequestKey == null) retryGate.isReady() else verificationGate.isReady(), newIntentRequired = verificationGate.newIntentRequired) }
    val state: StateFlow<CallUiState> = _state
    private val _events = MutableStateFlow<CallEvent?>(null)
    val events: StateFlow<CallEvent?> = _events
    private var streamJob: Job? = null
    private var timerJob: Job? = null
    /** The screen is resumed: [watch] was called and [stopWatching] was not. */
    private var watching = false
    /** The device deadline of the current request has been reached; the timer does not fire again for it. */
    private var deadlinePassed = false
    /**
     * Re-open state of each request's stream (by token) for the life of the screen: opening it again (resume, the
     * deadline re-check) after it brought an event or dropped is a re-open, and a 429 on it is waited for, not shown as
     * LOCKED (api.md «SSE», [SseReopenState]).
     */
    private val reopenStates = HashMap<String, SseReopenState>()

    init {
        armTimer()
    }

    /** Called on every resume: the stream is cheap and its first event is the full state. */
    fun watch() {
        if (pendingRequestKey != null || !retryGate.isReady()) return
        val token = flow.verification?.verificationToken ?: return
        watching = true
        // Re-armed on every resume: the remaining time is read from the clock again (the device clock may have moved).
        armTimer()
        if (streamJob?.isActive == true) return
        streamJob = viewModelScope.launch {
            try {
                // A drop of an open stream is re-opened with pauses and does not show «нет сети»; a stream that cannot be
                // opened at all still does (reopenFirstFailure = false), as does a 429 on the first opening of the
                // request on this screen (LOCKED). A 429 when the screen opens it again is waited for.
                reopeningStream(
                    isTerminal = { it.status != VerificationStatus.PENDING },
                    reopenFirstFailure = false,
                    state = reopenStates.getOrPut(token) { SseReopenState() },
                    elapsedClock = elapsedClock,
                    recoverOnceAfter = { ServiceGate.recoveryDeadline(it, elapsedClock) },
                ) { authApi.streamPhoneVerification(token) }.collect { event ->
                    when (event.status) {
                        VerificationStatus.PENDING -> _state.update { it.copy(phase = CallPhase.WAITING) }
                        VerificationStatus.VERIFIED -> onVerified(event.isNewUser == true, event.accessToken, event.refreshToken, event.registrationToken)
                        VerificationStatus.EXPIRED, VerificationStatus.UNKNOWN -> _state.update { it.copy(phase = CallPhase.EXPIRED) }
                    }
                }
            } catch (e: AppError) {
                // This known correction is constrained by the stream's one-shot permit and deadline.
                // A second generic latch here would prevent that authorized recovery from collecting at all.
                if (e !is AppError.Maintenance || e.response?.context != ErrorContext.REQUEST || e.requestRetryable != false) retryGate.record(e)
                _state.update { it.copy(errorPresentation = e.presentation.takeUnless { e is AppError.Offline }, requestRetryable = e.requestRetryable) }
                when (e) {
                    is AppError.Offline -> _state.update { it.copy(phase = CallPhase.OFFLINE) }
                    is AppError.Unauthorized -> _state.update { it.copy(phase = CallPhase.EXPIRED) }
                    is AppError.RateLimited -> _state.update { it.copy(phase = CallPhase.LOCKED, lockText = lockText(e)) }
                    else -> _state.update { it.copy(phase = CallPhase.ERROR) }
                }
            }
        }
    }

    fun stopWatching() {
        watching = false
        streamJob?.cancel()
        streamJob = null
    }

    /**
     * Timer to the device deadline ([SignInFlow.callDeadline]): at the deadline the number leaves the screen and the
     * stream is re-opened to confirm the status (it may have been closed). No deadline — no timer: `expired` of the
     * stream ends the wait.
     *
     * The timer fires [TIMER_GRACE] after the deadline: `expires_in` is rounded down, so the device deadline usually
     * comes up to a second before the server's. Fired exactly at it, the re-opened stream would answer `pending` and the
     * number would come back for a moment before `expired`. «до ЧЧ:ММ» keeps the deadline itself (seconds dropped).
     */
    private fun armTimer() {
        val deadline = flow.callServerDeadline
        if (deadline == null) {
            timerJob?.cancel()
            return
        }
        if (deadlinePassed) return
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val left = deadline.remainingMillis(elapsedClock) ?: run {
                _state.update { it.copy(waitUntil = null) }
                return@launch
            }
            if (left > 0) delay(left + TIMER_GRACE.toMillis())
            onDeadline()
        }
    }

    private fun onDeadline() {
        deadlinePassed = true
        _state.update { it.copy(waitUntil = null, phase = if (it.phase == CallPhase.WAITING) CallPhase.CHECKING else it.phase) }
        if (_state.value.phase != CallPhase.CHECKING) return
        streamJob?.cancel()
        streamJob = null
        // In the background the stream is not open; the next resume opens it and confirms the status.
        if (watching) watch()
    }

    private fun waitUntilText(): String? =
        flow.callDeadline?.let { CallDeadline.clockText(it, deviceZone()) }

    /** The wait is shown by the device's clock and zone, not the profile zone of [Clock] (api.md 1.1). */
    private fun deviceZone(): ZoneId = ZoneId.systemDefault()

    fun onDialOpened() = tracker.track(Events.AUTH_CALL_STARTED)

    private suspend fun onVerified(isNew: Boolean, access: String?, refresh: String?, registrationToken: String?) {
        // The call has been counted: the deadline of the request no longer matters (the result lives 15 minutes).
        timerJob?.cancel()
        deadlinePassed = true
        if (isNew) {
            flow.registrationToken = registrationToken
            _events.value = if (flags.isEnabled(Flag.AUTH_REGISTRATION)) CallEvent.NewUser else CallEvent.RegistrationClosed
            return
        }
        if (access == null || refresh == null) {
            _state.update { it.copy(phase = CallPhase.EXPIRED) }
            return
        }
        try {
            session.start(userId = null, tokens = TokenPair(access, refresh), registered = false)
        } catch (e: AppError) {
            _state.update { it.copy(phase = if (e is AppError.Offline) CallPhase.OFFLINE else CallPhase.EXPIRED) }
            return
        }
        _events.value = CallEvent.SignedIn
    }

    /** «Позвонить ещё раз» after expiry: a new request with a new key (api.md: new action → new key). */
    fun retry() {
        if (pendingRequestKey != null) requestAgain() else watch()
    }

    fun requestAgain() {
        if (!verificationGate.isReady() || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, errorPresentation = null, requestRetryable = null) }
            try {
                stopWatching()
                val request = flow.phoneRequest ?: run { _events.value = CallEvent.ConsentRequired; return@launch }
                val key = if (verificationGate.newIntentRequired) {
                    verificationGate.clear()
                    flow.newPhoneRequest(elapsedClock).also { pendingRequestKey = it }
                } else pendingRequestKey ?: flow.newPhoneRequest(elapsedClock).also { pendingRequestKey = it }
                // The old expired verification must not cause Phone to replace this pending key after back.
                flow.clearVerification()
                if (key.replayWindow?.isOpen(elapsedClock) != true) {
                    _state.update { it.copy(phase = CallPhase.ERROR, replayUnavailable = true) }
                    return@launch
                }
                val verification = authApi.startPhoneVerification(key, request)
                pendingRequestKey = null
                retryGate.clear()
                verificationGate.clear()
                flow.verificationReceived(verification, clock.now().toInstant())
                deadlinePassed = false
                _state.update {
                    it.copy(phase = CallPhase.WAITING, callbackNumber = verification.callbackNumber, waitUntil = waitUntilText())
                }
                armTimer()
                watch()
            } catch (e: AppError) {
                verificationGate.record(e)
                if (e is AppError.Api && (e.code == ErrorCodes.CONSENT_OUTDATED || e.code == ErrorCodes.CONSENT_REQUIRED)) {
                    flow.consentRefreshRequired = true
                    _events.value = CallEvent.ConsentRequired
                }
                updateRetryReady()
                _state.update { it.copy(errorPresentation = e.presentation.takeUnless { e is AppError.Offline }, requestRetryable = e.requestRetryable) }
                when (e) {
                    is AppError.RateLimited -> _state.update { it.copy(phase = CallPhase.LOCKED, lockText = lockText(e)) }
                    is AppError.Offline -> _state.update { it.copy(phase = CallPhase.OFFLINE) }
                    else -> _state.update { it.copy(phase = CallPhase.ERROR) }
                }
            } finally {
                _state.update { it.copy(busy = false) }
                updateRetryReady()
            }
        }
    }

    private fun lockText(e: AppError.RateLimited): String {
        val left = e.response?.timing?.deadline(e.retryAfterSeconds, e.retryAt?.toInstant())?.remainingMillis(elapsedClock)
        return CallLockText.withRemaining(e.retryAt, left, clock.zone)
    }

    fun consumeEvent() { _events.value = null }

    private companion object {
        /** Margin over the rounding of `expires_in` (whole seconds, rounded down) — see [armTimer]. */
        val TIMER_GRACE: Duration = Duration.ofSeconds(1)
    }
}
