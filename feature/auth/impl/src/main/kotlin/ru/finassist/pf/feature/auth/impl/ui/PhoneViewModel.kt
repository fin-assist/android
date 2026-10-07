package ru.finassist.pf.feature.auth.impl.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.impl.domain.PhoneFormat
import ru.finassist.pf.feature.auth.impl.domain.SignInFlow
import javax.inject.Inject

data class PhoneUiState(
    /** Digits only (no `+7`) with the cursor; the mask is drawn by [PhoneMaskTransformation]. */
    val input: TextFieldValue = TextFieldValue(""),
    val error: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
) {
    val digits: String get() = input.text
}

sealed interface PhoneEvent {
    data object GoToCall : PhoneEvent
}

@HiltViewModel
class PhoneViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val flow: SignInFlow,
    private val tracker: Tracker,
) : ViewModel() {
    private val _state = MutableStateFlow(
        PhoneFormat.digits(flow.phone).let { PhoneUiState(input = TextFieldValue(it, TextRange(it.length))) },
    )
    val state: StateFlow<PhoneUiState> = _state
    private val _events = MutableStateFlow<PhoneEvent?>(null)
    val events: StateFlow<PhoneEvent?> = _events

    fun setNotice(text: String?) = _state.update { it.copy(notice = text) }

    fun onInput(value: TextFieldValue) {
        val e = PhoneFormat.edit(value.text, value.selection.start, value.selection.end)
        _state.update {
            // Selection-only changes keep the error; any text change clears it.
            it.copy(input = TextFieldValue(e.digits, TextRange(e.start, e.end)), error = if (e.digits == it.digits) it.error else null)
        }
    }

    fun submit() {
        val digits = _state.value.digits
        if (!PhoneFormat.isComplete(digits)) {
            _state.update { it.copy(error = "В номере не хватает цифр") }
            return
        }
        val phone = PhoneFormat.toE164(digits)
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            // A new number is a new action → new idempotency key; a retry of the same number reuses it.
            if (flow.phone != phone || flow.phoneRequestKey == null) {
                flow.phone = phone
                flow.phoneRequestKey = IdempotencyKey.random().value
                flow.verification = null
            }
            try {
                flow.verification = authApi.startPhoneVerification(IdempotencyKey(flow.phoneRequestKey!!), phone)
                tracker.track(Events.AUTH_PHONE_SUBMITTED)
                _events.value = PhoneEvent.GoToCall
            } catch (e: AppError) {
                _state.update { it.copy(error = errorText(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun consumeEvent() { _events.value = null }

    private fun errorText(e: AppError): String = when (e) {
        is AppError.Offline -> "Нет сети — проверьте интернет и повторите"
        is AppError.RateLimited -> "Слишком много попыток" + (e.retryAt?.let { ". Повторите в ${"%02d:%02d".format(it.hour, it.minute)}" } ?: "")
        is AppError.Api -> if (e.code == ErrorCodes.INVALID_PHONE) "Нужен российский номер: +7 и 10 цифр" else "Не получилось отправить номер. Попробуйте ещё раз"
        else -> "Не получилось отправить номер. Попробуйте ещё раз"
    }

}
