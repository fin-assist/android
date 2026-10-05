package ru.finassist.pf.feature.auth.impl.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.VerificationStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.impl.data.SessionRepositoryImpl
import ru.finassist.pf.feature.auth.impl.domain.PhoneFormat
import ru.finassist.pf.feature.auth.impl.domain.SignInFlow
import javax.inject.Inject

enum class CallPhase { WAITING, EXPIRED, LOCKED, OFFLINE }

data class CallUiState(
    val phoneDisplay: String,
    val callbackNumber: String,
    val phase: CallPhase = CallPhase.WAITING,
    val lockedUntil: String? = null,
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
) : ViewModel() {
    private val _state = MutableStateFlow(
        CallUiState(
            phoneDisplay = PhoneFormat.display(PhoneFormat.digits(flow.phone)),
            callbackNumber = flow.verification?.callbackNumber ?: "",
        ),
    )
    val state: StateFlow<CallUiState> = _state
    private val _events = MutableStateFlow<CallEvent?>(null)
    val events: StateFlow<CallEvent?> = _events
    private var streamJob: Job? = null

    /** Called on every resume: the stream is cheap and its first event is the full state. */
    fun watch() {
        val token = flow.verification?.verificationToken ?: return
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
                    is AppError.RateLimited -> _state.update { it.copy(phase = CallPhase.LOCKED, lockedUntil = e.retryAt?.let { "%02d:%02d".format(it.hour, it.minute) }) }
                    else -> _state.update { it.copy(phase = CallPhase.EXPIRED) }
                }
            }
        }
    }

    fun stopWatching() {
        streamJob?.cancel()
        streamJob = null
    }

    fun onDialOpened() = tracker.track(Events.AUTH_CALL_STARTED)

    private suspend fun onVerified(isNew: Boolean, access: String?, refresh: String?, registrationToken: String?) {
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
        flow.reset()
        _events.value = CallEvent.SignedIn
    }

    /** «Позвонить ещё раз» after expiry: a new request with a new key (api.md: new action → new key). */
    fun requestAgain() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                stopWatching()
                flow.phoneRequestKey = IdempotencyKey.random().value
                flow.verification = authApi.startPhoneVerification(IdempotencyKey(flow.phoneRequestKey!!), flow.phone)
                _state.update { it.copy(phase = CallPhase.WAITING, callbackNumber = flow.verification!!.callbackNumber) }
                watch()
            } catch (e: AppError) {
                when (e) {
                    is AppError.RateLimited -> _state.update { it.copy(phase = CallPhase.LOCKED, lockedUntil = e.retryAt?.let { "%02d:%02d".format(it.hour, it.minute) }) }
                    is AppError.Offline -> _state.update { it.copy(phase = CallPhase.OFFLINE) }
                    else -> _state.update { it.copy(phase = CallPhase.EXPIRED) }
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun consumeEvent() { _events.value = null }
}
