package ru.finassist.pf.feature.auth.impl.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flags
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.impl.domain.AuthRepository
import ru.finassist.pf.feature.auth.impl.domain.Verification
import ru.finassist.pf.feature.auth.impl.domain.VerificationEvent
import java.time.OffsetDateTime
import javax.inject.Inject

/** Whole login flow in one view model: phone → call → (consent). Shared by the three screens of the auth graph. */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repo: AuthRepository,
    private val flags: FeatureFlags,
    private val tracker: Tracker,
) : ViewModel() {

    sealed interface CallState {
        data object Waiting : CallState
        data object Expired : CallState
        data class Locked(val retryAt: OffsetDateTime?) : CallState
        /** Number confirmed but registration is switched off by the `auth.registration` flag. */
        data object RegistrationClosed : CallState
        data class Error(val error: AppError) : CallState
    }

    data class UiState(
        val phoneDigits: String = "",            // 10 digits after +7
        val phoneError: String? = null,
        val starting: Boolean = false,
        val verification: Verification? = null,
        val callState: CallState = CallState.Waiting,
        val registrationToken: String? = null,
        val consentVersion: String? = null,
        val consentUrl: String? = null,
        val consentChecked: Boolean = false,
        val consentError: String? = null,
        val registering: Boolean = false,
        val registerError: AppError? = null,
    ) {
        val phoneE164: String get() = "+7$phoneDigits"
        val phoneFormatted: String get() = formatPhone(phoneDigits)
        val canContinue: Boolean get() = phoneDigits.length == 10 && !starting
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** One-shot navigation requests for the auth graph. */
    sealed interface Nav { data object ToCall : Nav; data object ToConsent : Nav; data object ToPhone : Nav }
    private val _nav = MutableStateFlow<Nav?>(null)
    val nav: StateFlow<Nav?> = _nav
    fun navConsumed() { _nav.value = null }

    private var startKey: String? = null
    private var registerKey: String? = null
    private var streamJob: Job? = null

    fun onPhoneChanged(raw: String) {
        val digits = raw.filter { it.isDigit() }.let { if (it.startsWith("7") || it.startsWith("8")) it.drop(1) else it }.take(10)
        _state.update { it.copy(phoneDigits = digits, phoneError = null) }
        startKey = null  // edited input is a new logical action → new idempotency key
    }

    fun onContinue() {
        val s = _state.value
        if (s.phoneDigits.length < 10) { _state.update { it.copy(phoneError = "В номере не хватает цифр") }; return }
        val key = startKey ?: repo.newKey().also { startKey = it }
        _state.update { it.copy(starting = true, phoneError = null) }
        viewModelScope.launch {
            try {
                val v = repo.startVerification(s.phoneE164, key)
                tracker.track("auth.verification.started")
                _state.update { it.copy(starting = false, verification = v, callState = CallState.Waiting) }
                _nav.value = Nav.ToCall
                observe(v)
            } catch (e: AppError) {
                _state.update { it.copy(starting = false) }
                when (e) {
                    is AppError.InvalidPhone -> _state.update { it.copy(phoneError = "Проверьте номер: нужен российский номер из 10 цифр") }
                    is AppError.RateLimited -> { _state.update { it.copy(callState = CallState.Locked(e.retryAt)) }; _nav.value = Nav.ToCall }
                    is AppError.Offline -> _state.update { it.copy(phoneError = "Нет сети. Проверьте интернет и повторите") }
                    else -> _state.update { it.copy(phoneError = "Не получилось начать вход. Повторите позже") }
                }
            }
        }
    }

    /** Re-open the status stream (also used when the app returns from background). */
    fun observe(v: Verification = _state.value.verification ?: return) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            repo.observeVerification(v, _state.value.phoneE164).collect { event ->
                when (event) {
                    VerificationEvent.Pending -> _state.update { it.copy(callState = CallState.Waiting) }
                    VerificationEvent.Expired -> _state.update { it.copy(callState = CallState.Expired) }
                    is VerificationEvent.Failed -> _state.update { it.copy(callState = CallState.Error(event.error)) }
                    is VerificationEvent.LoggedIn -> tracker.track("auth.login.completed")
                    is VerificationEvent.NewUser -> {
                        if (!flags.isEnabled(Flags.authRegistration)) {
                            _state.update { it.copy(callState = CallState.RegistrationClosed) }
                        } else {
                            registerKey = null
                            _state.update { it.copy(registrationToken = event.registrationToken) }
                            loadConsent()
                            _nav.value = Nav.ToConsent
                        }
                    }
                }
            }
        }
    }

    fun onCallAgain() { startKey = null; onContinue() }

    fun onChangePhone() {
        streamJob?.cancel()
        _state.update { it.copy(verification = null, callState = CallState.Waiting) }
        _nav.value = Nav.ToPhone
    }

    private fun loadConsent() = viewModelScope.launch {
        runCatching { repo.consentDocument() }.onSuccess { d -> _state.update { it.copy(consentVersion = d.version, consentUrl = d.url) } }
    }

    fun onConsentChecked(checked: Boolean) = _state.update { it.copy(consentChecked = checked, consentError = null) }

    fun onCreateAccount() {
        val s = _state.value
        if (!s.consentChecked) { _state.update { it.copy(consentError = "Без согласия мы не сможем создать аккаунт и хранить ваши данные") }; return }
        val token = s.registrationToken ?: return
        val version = s.consentVersion ?: run { loadConsent(); return }
        val key = registerKey ?: repo.newKey().also { registerKey = it }
        _state.update { it.copy(registering = true, registerError = null) }
        viewModelScope.launch {
            try {
                repo.register(token, version, s.phoneE164, key)
                tracker.track("auth.registration.completed")
            } catch (e: AppError) {
                _state.update { it.copy(registering = false, registerError = e) }
                when (e) {
                    is AppError.ConsentOutdated -> { registerKey = null; loadConsent() }
                    is AppError.TokenExpired, is AppError.Unauthorized -> { _state.update { it.copy(callState = CallState.Expired, registrationToken = null) }; _nav.value = Nav.ToCall }
                    else -> Unit
                }
            }
        }
    }

    override fun onCleared() { streamJob?.cancel() }

    companion object {
        /** «+7 916 123-45-67» as typed. */
        fun formatPhone(digits: String): String {
            val sb = StringBuilder("+7")
            digits.forEachIndexed { i, ch ->
                when (i) { 0 -> sb.append(' '); 3 -> sb.append(' '); 6, 8 -> sb.append('-') }
                sb.append(ch)
            }
            return sb.toString()
        }
    }
}
