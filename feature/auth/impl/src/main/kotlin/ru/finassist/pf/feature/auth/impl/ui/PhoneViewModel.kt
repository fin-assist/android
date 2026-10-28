package ru.finassist.pf.feature.auth.impl.ui

import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.feature.auth.impl.domain.PhoneStartGate
import ru.finassist.pf.core.api.model.StartPhoneVerificationRequest
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.time.ElapsedClock
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
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.impl.domain.PhoneFormat
import ru.finassist.pf.feature.auth.impl.domain.SignInFlow
import javax.inject.Inject

data class PhoneUiState(
    /** Digits only (no `+7`) with the cursor; the mask is drawn by [PhoneMaskTransformation]. */
    val input: TextFieldValue = TextFieldValue(""),
    val ageConfirmed: Boolean = false,
    val pdConsentAccepted: Boolean = false,
    val document: ConsentDocument? = null,
    val documentBusy: Boolean = false,
    val documentError: String? = null,
    val documentRefreshRequired: Boolean = false,
    val documentRefreshReady: Boolean = true,
    val newIntentRequired: Boolean = false,
    val error: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val errorPresentation: ErrorPresentation? = null,
    val requestRetryable: Boolean? = null,
    val retryReady: Boolean = true,
    val replayUnavailable: Boolean = false,
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
    private val clock: Clock,
    private val elapsedClock: ElapsedClock,
) : ViewModel() {
    private val _state = MutableStateFlow(
        PhoneFormat.digits(flow.phone).let { PhoneUiState(input = TextFieldValue(it, TextRange(it.length))) },
    )
    private val retryGate = PhoneStartGate(elapsedClock)
    private val documentGate = RequestRetryGate(elapsedClock)
    private var rejectedVersion: String? = null
    private var inputGeneration = 0L
    init {
        viewModelScope.launch { retryGate.readiness.collect { updateReadiness() } }
        viewModelScope.launch { documentGate.readiness.collect { updateReadiness() } }
        refreshDocument()
    }
    private fun updateReadiness() = _state.update { it.copy(
        retryReady = retryGate.isReady() && rejectedVersion == null,
        newIntentRequired = retryGate.newIntentRequired,
        documentRefreshReady = documentGate.isReady() && !it.documentBusy && !it.busy,
        documentRefreshRequired = rejectedVersion != null || it.document == null,
    ) }

    /** Called on return from the call screen when a new Start rejected the pinned document version. */
    fun onResume() {
        if (flow.consentRefreshRequired) {
            flow.consentRefreshRequired = false
            rejectedVersion = _state.value.document?.version
            _state.update { it.copy(pdConsentAccepted = false) }
            refreshDocument()
        }
    }

    fun setAgeConfirmed(value: Boolean) {
        if (!_state.value.busy) _state.update { it.copy(ageConfirmed = value, error = null) }
    }
    fun setConsentAccepted(value: Boolean) {
        if (!_state.value.busy && _state.value.document != null && rejectedVersion == null) {
            _state.update { it.copy(pdConsentAccepted = value, error = null) }
        }
    }
    fun refreshDocument() {
        if (_state.value.documentBusy || _state.value.busy || !documentGate.isReady()) return
        _state.update { it.copy(documentBusy = true, documentError = null) }
        updateReadiness()
        viewModelScope.launch {
            try {
                val document = authApi.getConsentDocument(ConsentType.PERSONAL_DATA)
                val changed = _state.value.document?.version != document.version
                val corrected = rejectedVersion != null && rejectedVersion != document.version
                if (corrected) {
                    rejectedVersion = null
                    retryGate.clear()
                    _state.update { it.copy(error = null, errorPresentation = null, requestRetryable = null) }
                }
                _state.update { it.copy(document = document, pdConsentAccepted = if (changed) false else it.pdConsentAccepted,
                    documentError = if (rejectedVersion != null) "Новая версия согласия пока недоступна. Обновите документ позже" else null) }
                documentGate.clear()
            } catch (e: AppError) {
                documentGate.record(e)
                _state.update { it.copy(documentError = "Не получилось загрузить документ согласия") }
            } finally {
                _state.update { it.copy(documentBusy = false) }; updateReadiness()
            }
        }
    }
    val state: StateFlow<PhoneUiState> = _state
    private val _events = MutableStateFlow<PhoneEvent?>(null)
    val events: StateFlow<PhoneEvent?> = _events

    fun setNotice(text: String?) = _state.update { it.copy(notice = text) }

    fun onInput(value: TextFieldValue) {
        if (!_state.value.ageConfirmed) return
        val e = PhoneFormat.edit(value.text, value.selection.start, value.selection.end)
        if (e.digits != _state.value.digits) {
            inputGeneration++ // invalidate the previous number before clearing its restrictions
            retryGate.clear()
        }
        _state.update {
            // Selection-only changes keep the error; any text change clears it.
            it.copy(input = TextFieldValue(e.digits, TextRange(e.start, e.end)), error = if (e.digits == it.digits) it.error else null, errorPresentation = if (e.digits == it.digits) it.errorPresentation else null, requestRetryable = if (e.digits == it.digits) it.requestRetryable else null, replayUnavailable = if (e.digits == it.digits) it.replayUnavailable else false)
        }
    }

    fun submit() {
        if (_state.value.busy || !retryGate.isReady() || rejectedVersion != null) return
        val state = _state.value
        if (!state.ageConfirmed) {
            _state.update { it.copy(error = "Подтвердите, что вам исполнилось 14 лет") }; return
        }
        if (!state.pdConsentAccepted || state.document == null) {
            _state.update { it.copy(error = "Дайте согласие на обработку персональных данных до отправки номера") }; return
        }
        val digits = _state.value.digits
        if (!PhoneFormat.isComplete(digits)) {
            _state.update { it.copy(error = "В номере не хватает цифр") }
            return
        }
        val phone = PhoneFormat.toE164(digits)
        val request = StartPhoneVerificationRequest(phone, true, state.document.version, true)
        val generation = inputGeneration
        _state.update { it.copy(busy = true, error = null, errorPresentation = null) }
        viewModelScope.launch {
            try {
                if (generation != inputGeneration) return@launch
                // A new number is a new action → new idempotency key; a retry of the same number reuses it.
                if (flow.phone != phone || flow.phoneRequestKey == null || (flow.phoneRequest != null && flow.phoneRequest != request) || retryGate.newIntentRequired || flow.callServerDeadline?.remainingMillis(elapsedClock) == 0L) {
                    flow.phone = phone
                    flow.newPhoneRequest(elapsedClock)
                    flow.phoneRequest = request
                    flow.consentDocument = state.document
                    retryGate.clear()
                    flow.clearVerification()
                }
                val key = checkNotNull(flow.currentPhoneKey())
                if (key.replayWindow?.isOpen(elapsedClock) != true) {
                    _state.update { it.copy(replayUnavailable = true, error = "Не получилось отправить номер. Измените номер или начните вход заново") }
                    return@launch
                }
                // A key without a body is an unknown legacy action; never assign it a newly accepted consent.
                if (flow.phoneRequest == null) {
                    _state.update { it.copy(replayUnavailable = true, error = "Начните вход заново") }; return@launch
                }
                val verification = authApi.startPhoneVerification(key, request)
                if (generation != inputGeneration) return@launch
                // Device time of arrival: the wait is counted by the device clock, not by `expires_at` (api.md 1.1).
                flow.verificationReceived(verification, clock.now().toInstant())
                tracker.track(Events.AUTH_PHONE_SUBMITTED)
                _events.value = PhoneEvent.GoToCall
            } catch (e: AppError) {
                if (generation != inputGeneration) return@launch
                retryGate.record(e)
                if (e is AppError.Api && e.code == ErrorCodes.CONSENT_OUTDATED) {
                    rejectedVersion = request.pdConsentVersion
                    _state.update { it.copy(pdConsentAccepted = false, documentError = "Текст согласия обновился — прочитайте его и подтвердите заново") }
                    _state.update { it.copy(busy = false) }
                    documentGate.clear()
                    refreshDocument()
                }
                updateReadiness()
                _state.update { it.copy(error = errorText(e), errorPresentation = e.presentation.takeUnless { e is AppError.Offline }, requestRetryable = e.requestRetryable) }
            } finally {
                _state.update { it.copy(busy = false) }; updateReadiness()
            }
        }
    }

    fun consumeEvent() { _events.value = null }

    private fun errorText(e: AppError): String = when (e) {
        is AppError.Offline -> "Нет сети — проверьте интернет и повторите"
        is AppError.RateLimited -> "Слишком много попыток" + (e.retryAt?.let { ". Повторите в ${"%02d:%02d".format(it.hour, it.minute)}" } ?: "")
        // The server checks the format too (api.md 1.1): a violation at `phone`, whatever its code, is the field's error.
        is AppError.Api -> if (e.code == ErrorCodes.VALIDATION_ERROR && e.details.any { it.field == "phone" }) {
            "Нужен российский номер: +7 и 10 цифр"
        } else {
            "Не получилось отправить номер. Попробуйте ещё раз"
        }
        else -> "Не получилось отправить номер. Попробуйте ещё раз"
    }

}
