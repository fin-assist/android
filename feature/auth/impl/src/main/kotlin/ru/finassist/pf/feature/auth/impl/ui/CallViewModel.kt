package ru.finassist.pf.feature.auth.impl.ui

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
enum class CallPhase { WAITING, CHECKING, EXPIRED, LOCKED, OFFLINE }

data class CallUiState(
    val phoneDisplay: String,
    val callbackNumber: String,
    val phase: CallPhase = CallPhase.WAITING,
    /** «ЧЧ:ММ» of the device deadline in the device zone; null when it is unknown or has passed. */
    val waitUntil: String? = null,
    /** Text of the [CallPhase.LOCKED] state (`CallLockText`). */
    val lockText: String? = null,
    val busy: Boolean = false,
)

sealed interface CallEvent {
    /** Existing user: tokens saved, the app switches to the main graph by itself. */
    data object SignedIn : CallEvent
    data object NewUser : CallEvent
    data object RegistrationClosed : CallEvent
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
) : ViewModel() {
    private val _state = MutableStateFlow(
        CallUiState(
            phoneDisplay = PhoneFormat.display(PhoneFormat.digits(flow.phone)),
            callbackNumber = flow.verification?.callbackNumber ?: "",
            waitUntil = waitUntilText(),
        ),
    )
    val state: StateFlow<CallUiState> = _state
    private val _events = MutableStateFlow<CallEvent?>(null)
    val events: StateFlow<CallEvent?> = _events
    private var streamJob: Job? = null
    private var timerJob: Job? = null
    /** The screen is resumed: [watch] was called and [stopWatching] was not. */
    private var watching = false
    /** The device deadline of the current request has been reached; the timer does not fire again for it. */
    private var deadlinePassed = false

    init {
        armTimer()
    }

    /** Called on every resume: the stream is cheap and its first event is the full state. */
    fun watch() {
        val token = flow.verification?.verificationToken ?: return
        watching = true
        // Re-armed on every resume: the remaining time is read from the clock again (the device clock may have moved).
        armTimer()
        if (streamJob?.isActive == true) return
        streamJob = viewModelScope.launch {
            try {
                authApi.streamPhoneVerification(token).collect { event ->
                    when (event.status) {
                        VerificationStatus.PENDING -> _state.update { it.copy(phase = CallPhase.WAITING) }
                        VerificationStatus.VERIFIED -> onVerified(event.isNewUser == true, event.accessToken, event.refreshToken, event.registrationToken)
                        VerificationStatus.EXPIRED, VerificationStatus.UNKNOWN -> _state.update { it.copy(phase = CallPhase.EXPIRED) }
                    }
                }
            } catch (e: AppError) {
                when (e) {
                    is AppError.Offline -> _state.update { it.copy(phase = CallPhase.OFFLINE) }
                    is AppError.Unauthorized -> _state.update { it.copy(phase = CallPhase.EXPIRED) }
                    is AppError.RateLimited -> _state.update { it.copy(phase = CallPhase.LOCKED, lockText = lockText(e)) }
                    else -> _state.update { it.copy(phase = CallPhase.EXPIRED) }
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
     */
    private fun armTimer() {
        val deadline = flow.callDeadline
        if (deadline == null) {
            timerJob?.cancel()
            return
        }
        if (deadlinePassed) return
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val left = Duration.between(clock.now().toInstant(), deadline)
            if (!left.isNegative && !left.isZero) delay(left.toMillis())
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
    fun requestAgain() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                stopWatching()
                flow.phoneRequestKey = IdempotencyKey.random().value
                val verification = authApi.startPhoneVerification(IdempotencyKey(flow.phoneRequestKey!!), flow.phone)
                flow.verificationReceived(verification, clock.now().toInstant())
                deadlinePassed = false
                _state.update {
                    it.copy(phase = CallPhase.WAITING, callbackNumber = verification.callbackNumber, waitUntil = waitUntilText())
                }
                armTimer()
                watch()
            } catch (e: AppError) {
                when (e) {
                    is AppError.RateLimited -> _state.update { it.copy(phase = CallPhase.LOCKED, lockText = lockText(e)) }
                    is AppError.Offline -> _state.update { it.copy(phase = CallPhase.OFFLINE) }
                    else -> _state.update { it.copy(phase = CallPhase.EXPIRED) }
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun lockText(e: AppError.RateLimited) = CallLockText.of(e.retryAt, clock.now(), clock.zone)

    fun consumeEvent() { _events.value = null }
}
