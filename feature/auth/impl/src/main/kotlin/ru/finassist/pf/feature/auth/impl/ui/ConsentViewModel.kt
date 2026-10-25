package ru.finassist.pf.feature.auth.impl.ui

import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
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
    val accepted: Boolean = false,
    val error: String? = null,
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
    private val _state = MutableStateFlow(ConsentUiState(phoneDisplay = PhoneFormat.display(PhoneFormat.digits(flow.phone))))
    private val documentGate = RequestRetryGate(elapsedClock)
    private var documentFailure: AppError? = null
    private var rejectedRegistrationVersion: String? = null
    private var documentGeneration = 0L
    private val retryGate = RequestRetryGate(elapsedClock)
    init {
        viewModelScope.launch { retryGate.readiness.collect { updateReadiness() } }
        viewModelScope.launch { documentGate.readiness.collect { updateReadiness() } }
    }
    val state: StateFlow<ConsentUiState> = _state
    private val _registered = MutableStateFlow(false)
    val registered: StateFlow<Boolean> = _registered

    init {
        viewModelScope.launch { try { fetchDocument() } catch (_: AppError) { /* The current document failure is retained below. */ } }
    }

    private fun updateReadiness() = _state.update {
        it.copy(retryReady = retryGate.isReady() && documentGate.isReady() && rejectedRegistrationVersion == null)
    }

    private suspend fun fetchDocument(): ConsentDocument {
        if (!documentGate.isReady()) throw checkNotNull(documentFailure)
        val generation = ++documentGeneration
        return try {
            authApi.getConsentDocument(ConsentType.PERSONAL_DATA).also { document ->
                if (generation == documentGeneration) {
                    documentFailure = null
                    val changed = _state.value.document?.version?.let { it != document.version } == true
                    val corrected = rejectedRegistrationVersion?.let { it != document.version } == true
                    _state.update { it.copy(document = document, accepted = if (changed) false else it.accepted,
                        requestRetryable = if (corrected) null else it.requestRetryable) }
                    if (corrected) {
                        rejectedRegistrationVersion = null
                        retryGate.clear() // only a different fetched version corrects the rejected registration body
                    }
                    updateReadiness()
                }
            }
        } catch (error: AppError) {
            if (generation == documentGeneration) {
                documentFailure = error
                documentGate.record(error)
                _state.update { it.copy(errorPresentation = error.presentation.takeUnless { error is AppError.Offline },
                    formError = "Не получилось загрузить документ согласия") }
            }
            throw error
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun setAccepted(value: Boolean) = _state.update { it.copy(accepted = value, error = null) }

    fun submit() {
        val s = _state.value
        if (s.busy || !retryGate.isReady() || !documentGate.isReady() || rejectedRegistrationVersion != null) return
        if (!s.accepted) {
            _state.update { it.copy(error = "Без согласия мы не сможем создать аккаунт и хранить ваши данные") }
            return
        }
        val token = flow.registrationToken ?: run { _state.update { it.copy(expired = true) }; return }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, formError = null, errorPresentation = null) }
            var attemptedVersion: String? = null
            try {
                val version = s.document?.version ?: fetchDocument().version
                // The registration token is one-time: a repeat after a lost 201 (even after process death) must
                // carry the same key, or the user would have to call again (api.md 1.3).
                val action = "auth.register:" + sha256("$token|$version")
                val key = keys.keyFor(action, elapsedClock)
                if (key.replayWindow?.isOpen(elapsedClock) != true) {
                    _state.update { it.copy(replayUnavailable = true, formError = "Не получилось восстановить создание аккаунта. Начните вход заново") }
                    return@launch
                }
                attemptedVersion = version
                val response = authApi.register(
                    key,
                    RegisterRequest(registrationToken = token, pdConsentAccepted = true, pdConsentVersion = version, timezone = TimeZone.getDefault().id),
                )
                keys.complete(action)
                session.start(response.userId, TokenPair(response.accessToken, response.refreshToken), registered = true)
                _registered.value = true
            } catch (e: AppError) {
                retryGate.record(e)
                _state.update { it.copy(errorPresentation = e.presentation.takeUnless { e is AppError.Offline }, requestRetryable = e.requestRetryable) }
                when {
                    attemptedVersion != null && e is AppError.Api && e.response?.context != ErrorContext.RESULT_EVENT && e.code == ErrorCodes.CONSENT_OUTDATED -> {
                        // Preserve the denied body/key until a successful GET actually changes its document version.
                        // Only that corrected body and a fresh affirmative tick may select a different action key.
                        rejectedRegistrationVersion = attemptedVersion
                        updateReadiness()
                        documentGate.clear()
                        runCatching { fetchDocument() }
                        _state.update { it.copy(accepted = false) }
                        _state.update { it.copy(formError = "Текст согласия обновился — прочитайте его ещё раз и подтвердите") }
                    }
                    e is AppError.Unauthorized || (e is AppError.Api && e.httpStatus == 401) -> _state.update { it.copy(expired = true) }
                    e is AppError.Offline -> _state.update { it.copy(formError = "Нет сети — проверьте интернет и повторите") }
                    else -> _state.update { it.copy(formError = "Не получилось создать аккаунт. Попробуйте ещё раз") }
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
