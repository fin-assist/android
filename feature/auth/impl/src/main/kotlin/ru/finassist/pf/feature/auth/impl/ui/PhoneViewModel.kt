package ru.finassist.pf.feature.auth.impl.ui

import ru.finassist.pf.core.common.error.RequestRetryGate
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
import ru.finassist.pf.feature.auth.impl.domain.CallDeadline
import ru.finassist.pf.feature.auth.impl.domain.PhoneFormat
import ru.finassist.pf.feature.auth.impl.domain.SignInFlow
import java.time.OffsetDateTime
import java.util.Locale
import javax.inject.Inject

data class PhoneUiState(
    /** Digits only (no `+7`) with the cursor; the mask is drawn by [PhoneMaskTransformation]. */
    val input: TextFieldValue = TextFieldValue(""),
    val ageConfirmed: Boolean = false,
    val pdConsentAccepted: Boolean = false,
    val document: ConsentDocument? = null,
    val documentBusy: Boolean = false,
    val documentError: String? = null,
    val documentErrorPresentation: ErrorPresentation? = null,
    val retryWaitText: String? = null,
    val documentRefreshRequired: Boolean = false,
    val documentRefreshReady: Boolean = true,
    val newIntentRequired: Boolean = false,
    val startFailed: Boolean = false,
    val error: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val errorPresentation: ErrorPresentation? = null,
    val retryReady: Boolean = true,
    val replayUnavailable: Boolean = false,
) {
    val digits: String get() = input.text
    val retrying: Boolean get() = documentError != null || startFailed || newIntentRequired
    val submitEnabled: Boolean get() = when {
        busy || documentBusy -> false
        documentRefreshRequired -> documentRefreshReady
        else -> ageConfirmed && pdConsentAccepted && document != null && retryReady
    }
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
    private val retryGate = flow.phoneStartGate(elapsedClock)
    private val documentGate = RequestRetryGate(elapsedClock)
    private var documentRetryAt: OffsetDateTime? = null
    private var rejectedVersion: String? = null
    private var inputGeneration = 0L
    init {
        viewModelScope.launch { retryGate.readiness.collect { updateReadiness() } }
        viewModelScope.launch { retryGate.remainingWait.collect { updateReadiness() } }
        viewModelScope.launch { documentGate.remainingWait.collect { updateReadiness() } }
        refreshDocument()
    }
    private fun hasExpiredStartKey(phone: String): Boolean =
        flow.phoneRequest?.phone == phone && flow.currentPhoneKey()?.replayWindow?.isOpen(elapsedClock) == false

    private fun updateReadiness() {
        val startWait = retryGate.remainingMillis()
        val documentWait = documentGate.remainingMillis()
        val waiting = retryGate.receivedRateLimit?.takeIf { startWait != 0L }
        _state.update {
            val needsDocument = rejectedVersion != null || it.document == null
            it.copy(
                retryReady = startWait == 0L && rejectedVersion == null,
                newIntentRequired = retryGate.newIntentRequired || retryGate.isExplicitlyForbidden() ||
                    it.replayUnavailable || hasExpiredStartKey(PhoneFormat.toE164(it.digits)),
                documentRefreshReady = documentWait == 0L,
                documentRefreshRequired = needsDocument,
                retryWaitText = if (needsDocument) retryWaitText(documentWait, documentRetryAt)
                    else retryWaitText(startWait, waiting?.retryAt),
                error = if (waiting != null) RATE_LIMIT_TEXT else it.error,
                errorPresentation = waiting?.presentation ?: it.errorPresentation,
            )
        }
    }

    /** Called on return from the call screen when a new Start rejected the pinned document version. */
    fun onResume() {
        if (flow.consentRefreshRequired) {
            flow.consentRefreshRequired = false
            rejectedVersion = _state.value.document?.version
            _state.update { it.copy(pdConsentAccepted = false) }
            refreshDocument()
        }
        updateReadiness()
    }

    fun setAgeConfirmed(value: Boolean) {
        if (!_state.value.busy) {
            _state.update { it.copy(ageConfirmed = value, error = null) }
            updateReadiness()
        }
    }
    fun setConsentAccepted(value: Boolean) {
        if (!_state.value.busy && _state.value.document != null && rejectedVersion == null) {
            _state.update { it.copy(pdConsentAccepted = value, error = null) }
            updateReadiness()
        }
    }
    private fun refreshDocument() {
        if (_state.value.documentBusy || _state.value.busy || !documentGate.isReady()) return
        documentRetryAt = null
        _state.update { it.copy(documentBusy = true, documentError = null, documentErrorPresentation = null) }
        updateReadiness()
        viewModelScope.launch {
            try {
                val document = authApi.getConsentDocument(ConsentType.PERSONAL_DATA)
                val changed = _state.value.document?.version != document.version
                val corrected = rejectedVersion != null && rejectedVersion != document.version
                if (corrected) {
                    rejectedVersion = null
                    if (!retryGate.newIntentRequired && retryGate.remainingMillis() == 0L) retryGate.clear()
                    _state.update { it.copy(error = null, errorPresentation = null, startFailed = false) }
                }
                _state.update { it.copy(document = document, pdConsentAccepted = if (changed) false else it.pdConsentAccepted,
                    documentErrorPresentation = null,
                    documentError = if (rejectedVersion != null) "Новая версия согласия пока недоступна. Обновите документ позже" else null) }
                documentGate.clear()
            } catch (e: AppError) {
                documentRetryAt = e.response?.retryAt ?: (e as? AppError.RateLimited)?.retryAt
                documentGate.record(e)
                _state.update { it.copy(
                    documentError = if (e is AppError.RateLimited) RATE_LIMIT_TEXT else "Не получилось загрузить документ согласия",
                    documentErrorPresentation = e.presentation,
                ) }
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
            // Retire any in-flight reply. The captured wait and denied key survive edits, including A → B → A.
            inputGeneration++
        }
        _state.update {
            // Selection-only changes keep the error; any text change clears it.
            val input = TextFieldValue(e.digits, TextRange(e.start, e.end))
            if (e.digits == it.digits) it.copy(input = input)
            else it.copy(input = input, error = null, errorPresentation = null,
                replayUnavailable = false, startFailed = false)
        }
        updateReadiness()
    }

    fun submit() {
        val state = _state.value
        if (state.busy || state.documentBusy) return
        if (state.documentRefreshRequired) {
            if (documentGate.remainingMillis() == 0L) {
                // A fresh document read may replace a refused GET after its own wait.
                documentGate.clear()
                refreshDocument()
            }
            return
        }
        // A refused original can only become a new Start, with a fresh key, after its captured wait.
        if (retryGate.remainingMillis() != 0L) return
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
        val needsNewKey = retryGate.newIntentRequired || retryGate.isExplicitlyForbidden() || state.replayUnavailable
        _state.update { it.copy(busy = true, error = null, errorPresentation = null) }
        viewModelScope.launch {
            try {
                if (generation != inputGeneration) return@launch
                val previousKey = flow.currentPhoneKey()
                val replayAvailable = previousKey?.replayWindow?.isOpen(elapsedClock) == true && flow.phoneRequest != null
                val bodyChanged = flow.phoneRequest?.let { it != request } == true
                val callExpired = flow.callServerDeadline?.remainingMillis(elapsedClock) == 0L
                // A known expired key can be replaced on this click, including after returning from Call.
                // A legacy key of unknown age or body needs an explicit Retry after the unavailable-replay notice.
                val freshStart = needsNewKey || flow.phone != phone || previousKey == null || bodyChanged ||
                    callExpired || hasExpiredStartKey(phone)
                if (!freshStart && !replayAvailable) {
                    _state.update {
                        it.copy(replayUnavailable = true, error = "Не получилось отправить номер. Повторите попытку")
                    }
                    return@launch
                }
                val key = if (freshStart) {
                    flow.phone = phone
                    flow.phoneRequest = request
                    flow.consentDocument = state.document
                    flow.clearVerification()
                    retryGate.clear()
                    _state.update { it.copy(replayUnavailable = false) }
                    flow.newPhoneRequest(elapsedClock)
                } else checkNotNull(previousKey)
                val verification = authApi.startPhoneVerification(key, request)
                if (generation != inputGeneration) return@launch
                // Device time of arrival: the wait is counted by the device clock, not by `expires_at` (api.md 1.1).
                flow.verificationReceived(verification, clock.now().toInstant())
                _state.update { it.copy(startFailed = false) }
                tracker.track(Events.AUTH_PHONE_SUBMITTED)
                _events.value = PhoneEvent.GoToCall
            } catch (e: AppError) {
                if (generation != inputGeneration) return@launch
                retryGate.record(e)
                _state.update { it.copy(error = errorText(e), errorPresentation = e.presentation, startFailed = true) }
                if (e is AppError.Api && e.code == ErrorCodes.CONSENT_OUTDATED) {
                    rejectedVersion = request.pdConsentVersion
                    _state.update { it.copy(pdConsentAccepted = false, documentError = "Текст согласия обновился — прочитайте его и подтвердите заново") }
                    _state.update { it.copy(busy = false) }
                    documentGate.clear()
                    refreshDocument()
                }
                updateReadiness()
            } finally {
                _state.update { it.copy(busy = false) }; updateReadiness()
            }
        }
    }

    fun consumeEvent() { _events.value = null }

    private fun retryWaitText(remainingMillis: Long?, retryAt: OffsetDateTime?): String? = when (remainingMillis) {
        0L -> null
        null -> retryAt?.let { "Время повтора по данным сервера — ${CallDeadline.clockText(it.toInstant(), clock.zone)}" }
            ?: "Точное время ожидания неизвестно"
        else -> {
            val seconds = (remainingMillis - 1L) / 1000L + 1L
            "Повторить можно через %d:%02d".format(Locale.ROOT, seconds / 60L, seconds % 60L)
        }
    }

    private fun errorText(e: AppError): String = when (e) {
        is AppError.Offline -> "Не получилось отправить номер"
        is AppError.RateLimited -> RATE_LIMIT_TEXT
        // The server checks the format too (api.md 1.1): a violation at `phone`, whatever its code, is the field's error.
        is AppError.Api -> if (e.code == ErrorCodes.VALIDATION_ERROR && e.details.any { it.field == "phone" }) {
            "Нужен российский номер: +7 и 10 цифр"
        } else {
            "Не получилось отправить номер. Попробуйте ещё раз"
        }
        else -> "Не получилось отправить номер. Попробуйте ещё раз"
    }

}

private const val RATE_LIMIT_TEXT = "Слишком много попыток"
