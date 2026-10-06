package ru.finassist.pf.feature.auth.impl.ui

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
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.feature.auth.impl.data.SessionRepositoryImpl
import ru.finassist.pf.feature.auth.impl.domain.PhoneFormat
import ru.finassist.pf.feature.auth.impl.domain.SignInFlow
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
)

@HiltViewModel
class ConsentViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val flow: SignInFlow,
    private val session: SessionRepositoryImpl,
) : ViewModel() {
    private val _state = MutableStateFlow(ConsentUiState(phoneDisplay = PhoneFormat.display(PhoneFormat.digits(flow.phone))))
    val state: StateFlow<ConsentUiState> = _state
    private val _registered = MutableStateFlow(false)
    val registered: StateFlow<Boolean> = _registered
    private var registerKey: IdempotencyKey = IdempotencyKey.random()

    init {
        viewModelScope.launch {
            runCatching { authApi.getConsentDocument(ConsentType.PERSONAL_DATA) }
                .onSuccess { doc -> _state.update { it.copy(document = doc) } }
        }
    }

    fun setAccepted(value: Boolean) = _state.update { it.copy(accepted = value, error = null) }

    fun submit() {
        val s = _state.value
        if (!s.accepted) {
            _state.update { it.copy(error = "Без согласия мы не сможем создать аккаунт и хранить ваши данные") }
            return
        }
        val token = flow.registrationToken ?: run { _state.update { it.copy(expired = true) }; return }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, formError = null) }
            try {
                val version = s.document?.version ?: authApi.getConsentDocument(ConsentType.PERSONAL_DATA).version
                val response = authApi.register(
                    registerKey,
                    RegisterRequest(registrationToken = token, pdConsentAccepted = true, pdConsentVersion = version, timezone = TimeZone.getDefault().id),
                )
                session.start(response.userId, TokenPair(response.accessToken, response.refreshToken), registered = true)
                flow.reset()
                _registered.value = true
            } catch (e: AppError) {
                when {
                    e is AppError.Api && e.code == ErrorCodes.CONSENT_OUTDATED -> {
                        // Document moved on while the screen was open: reload it and ask again with a new key.
                        registerKey = IdempotencyKey.random()
                        runCatching { authApi.getConsentDocument(ConsentType.PERSONAL_DATA) }.onSuccess { doc -> _state.update { it.copy(document = doc) } }
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
