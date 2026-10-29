package ru.finassist.pf.feature.auth.impl.ui

import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.error.ErrorPresentation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.storage.IdempotencyKeys
import ru.finassist.pf.feature.auth.impl.data.SessionRepositoryImpl
import ru.finassist.pf.feature.auth.impl.domain.PhoneFormat
import ru.finassist.pf.feature.auth.impl.domain.SignInFlow
import java.security.MessageDigest
import java.util.TimeZone
import javax.inject.Inject

data class ConsentUiState(
    val phoneDisplay: String,
    val formError: String? = null,
    val busy: Boolean = false,
    val document: ConsentDocument? = null,
    /** Registration token expired: the user has to call again. */
    val expired: Boolean = false,
    val errorPresentation: ErrorPresentation? = null,
    val requestRetryable: Boolean? = null,
    val retryReady: Boolean = true,
    val replayUnavailable: Boolean = false,
)

@HiltViewModel
class ConsentViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val flow: SignInFlow,
    private val session: SessionRepositoryImpl,
    private val keys: IdempotencyKeys,
    private val elapsedClock: ElapsedClock,
) : ViewModel() {
    private val _state = MutableStateFlow(ConsentUiState(
        phoneDisplay = PhoneFormat.display(PhoneFormat.digits(flow.phone)),
        document = flow.consentDocument,
        expired = flow.registrationToken == null || flow.phoneRequest == null,
    ))
    private val retryGate = RequestRetryGate(elapsedClock)
    val state: StateFlow<ConsentUiState> = _state
    private val _registered = MutableStateFlow(false)
    val registered: StateFlow<Boolean> = _registered
    init {
        if (_state.value.expired) flow.reset()
        viewModelScope.launch { retryGate.readiness.collect { ready -> _state.update { it.copy(retryReady = ready) } } }
    }

    private fun expire() {
        flow.reset()
        _state.update { it.copy(expired = true) }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun submit() {
        if (_state.value.busy || !retryGate.isReady() || _state.value.expired) return
        val token = flow.registrationToken ?: run { expire(); return }
        val initial = flow.phoneRequest ?: run { expire(); return }
        if (!initial.pdConsentAccepted || !initial.ageConfirmed) {
            expire(); return
        }
        val request = flow.registrationRequest ?: RegisterRequest(
            token, initial.pdConsentAccepted, initial.pdConsentVersion, TimeZone.getDefault().id,
        ).also { flow.registrationRequest = it }
        _state.update { it.copy(busy = true, formError = null, errorPresentation = null) }
        viewModelScope.launch {
            try {
                val action = "auth.register:" + sha256("$token|${initial.pdConsentVersion}")
                val key = keys.keyFor(action, elapsedClock)
                if (key.replayWindow?.isOpen(elapsedClock) != true) {
                    _state.update { it.copy(replayUnavailable = true, formError = "Не получилось восстановить создание аккаунта. Начните вход заново") }
                    return@launch
                }
                val response = authApi.register(key, request)
                keys.complete(action)
                session.start(response.userId, TokenPair(response.accessToken, response.refreshToken), registered = true)
                _registered.value = true
            } catch (e: AppError) {
                retryGate.record(e)
                _state.update { it.copy(errorPresentation = e.presentation.takeUnless { e is AppError.Offline }, requestRetryable = e.requestRetryable) }
                when {
                    e is AppError.Unauthorized || (e is AppError.Api && (e.httpStatus == 401 || e.code == ErrorCodes.CONSENT_OUTDATED || e.code == ErrorCodes.CONSENT_REQUIRED)) ->
                        expire()
                    e is AppError.Offline -> _state.update { it.copy(formError = "Нет сети — проверьте интернет и повторите") }
                    else -> _state.update { it.copy(formError = "Не получилось создать аккаунт. Попробуйте ещё раз") }
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
