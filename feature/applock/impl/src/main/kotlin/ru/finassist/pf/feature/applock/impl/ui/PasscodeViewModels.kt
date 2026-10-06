package ru.finassist.pf.feature.applock.impl.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.applock.api.BiometricAvailability
import ru.finassist.pf.feature.applock.api.PASSCODE_LENGTH
import ru.finassist.pf.feature.applock.api.VerifyResult
import javax.inject.Inject

/** Steps of «set a passcode»: used both at first run and when changing the code (with [EnterStep.OLD] first). */
enum class EnterStep { OLD, NEW, REPEAT, BIOMETRIC }

data class PasscodeEntryUiState(
    val step: EnterStep,
    val entered: String = "",
    val error: String? = null,
    val biometric: BiometricAvailability = BiometricAvailability.NONE,
    val busy: Boolean = false,
    /** Set when the flow is complete: the screen navigates away. */
    val done: Boolean = false,
)

/**
 * Setup (first run) and change (profile) share the state machine: [withOld] adds the «current code» step.
 * The biometric offer is shown only at setup and only when the device has biometrics.
 */
@HiltViewModel
class PasscodeEntryViewModel @Inject constructor(private val appLock: AppLock) : ViewModel() {
    private var withOld = false
    private var offerBiometric = false
    private var newCode = ""
    private val _state = MutableStateFlow(PasscodeEntryUiState(step = EnterStep.NEW))
    val state: StateFlow<PasscodeEntryUiState> = _state

    fun configure(changeExisting: Boolean) {
        if (_state.value.entered.isNotEmpty() || _state.value.step != EnterStep.NEW) return
        withOld = changeExisting
        offerBiometric = !changeExisting && appLock.biometricAvailability() != BiometricAvailability.NONE
        _state.value = PasscodeEntryUiState(step = if (changeExisting) EnterStep.OLD else EnterStep.NEW, biometric = appLock.biometricAvailability())
    }

    fun digit(d: Int) {
        val s = _state.value
        if (s.busy || s.entered.length >= PASSCODE_LENGTH) return
        val next = s.entered + d
        _state.update { it.copy(entered = next, error = null) }
        if (next.length == PASSCODE_LENGTH) viewModelScope.launch { complete(next) }
    }

    fun delete() = _state.update { it.copy(entered = it.entered.dropLast(1), error = null) }

    /** System «back»: from the repeat step → back to the first entry; the screen decides what to do otherwise. */
    fun backToFirstEntry(): Boolean {
        val s = _state.value
        if (s.step == EnterStep.REPEAT) {
            newCode = ""
            _state.update { it.copy(step = EnterStep.NEW, entered = "", error = null) }
            return true
        }
        return false
    }

    private suspend fun complete(code: String) {
        when (_state.value.step) {
            EnterStep.OLD -> {
                _state.update { it.copy(busy = true) }
                val ok = appLock.matches(code)
                _state.update {
                    if (ok) it.copy(step = EnterStep.NEW, entered = "", busy = false)
                    else it.copy(entered = "", error = "Неверный код", busy = false)
                }
            }
            EnterStep.NEW -> {
                newCode = code
                _state.update { it.copy(step = EnterStep.REPEAT, entered = "") }
            }
            EnterStep.REPEAT -> {
                if (code != newCode) {
                    newCode = ""
                    _state.update { it.copy(step = EnterStep.NEW, entered = "", error = "Коды не совпали — придумайте код заново") }
                    return
                }
                _state.update { it.copy(busy = true) }
                appLock.setPasscode(code)
                _state.update {
                    if (offerBiometric) it.copy(step = EnterStep.BIOMETRIC, entered = "", busy = false)
                    else it.copy(done = true, busy = false)
                }
            }
            EnterStep.BIOMETRIC -> Unit
        }
    }

    fun enableBiometric() {
        viewModelScope.launch {
            appLock.setBiometricEnabled(true)
            _state.update { it.copy(done = true) }
        }
    }

    fun skipBiometric() = _state.update { it.copy(done = true) }
}

data class UnlockUiState(
    val entered: String = "",
    val error: String? = null,
    val biometric: BiometricAvailability = BiometricAvailability.NONE,
    val biometricEnabled: Boolean = false,
    val busy: Boolean = false,
    val askForgot: Boolean = false,
)

/** Unlock overlay: passcode or biometrics; «Забыли код?» ends the session. */
@HiltViewModel
class UnlockViewModel @Inject constructor(private val appLock: AppLock) : ViewModel() {
    private val _state = MutableStateFlow(UnlockUiState(biometric = appLock.biometricAvailability()))
    val state: StateFlow<UnlockUiState> = _state

    init {
        // The view model outlives one lock/unlock cycle (it is scoped to the activity), so follow the setting.
        viewModelScope.launch { appLock.biometricEnabled.collect { on -> _state.update { it.copy(biometricEnabled = on) } } }
        viewModelScope.launch { appLock.state.collect { _state.update { it.copy(entered = "", error = null) } } }
    }

    fun digit(d: Int) {
        val s = _state.value
        if (s.busy || s.entered.length >= PASSCODE_LENGTH) return
        val next = s.entered + d
        _state.update { it.copy(entered = next, error = null) }
        if (next.length == PASSCODE_LENGTH) {
            viewModelScope.launch {
                _state.update { it.copy(busy = true) }
                when (val r = appLock.verify(next)) {
                    VerifyResult.Ok -> _state.update { it.copy(entered = "", busy = false) }
                    is VerifyResult.Wrong -> _state.update {
                        it.copy(
                            entered = "",
                            busy = false,
                            error = if (r.attemptsLeft == 1) "Неверный код. Осталась последняя попытка — после неё придётся войти заново"
                            else "Неверный код. Осталось попыток: ${r.attemptsLeft}",
                        )
                    }
                    VerifyResult.LockedOut -> _state.update { it.copy(entered = "", busy = false) }
                }
            }
        }
    }

    fun delete() = _state.update { it.copy(entered = it.entered.dropLast(1), error = null) }

    fun onBiometricSuccess() = appLock.unlockByBiometric()

    fun askForgot(show: Boolean) = _state.update { it.copy(askForgot = show) }

    fun forgot() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, askForgot = false) }
            appLock.forgetAndSignOut()
        }
    }
}

data class ConfirmUiState(val entered: String = "", val error: String? = null, val busy: Boolean = false, val confirmed: Boolean = false)

/** Confirms the current code before a dangerous action; no attempt counting — the user is already unlocked. */
@HiltViewModel
class ConfirmPasscodeViewModel @Inject constructor(private val appLock: AppLock) : ViewModel() {
    private val _state = MutableStateFlow(ConfirmUiState())
    val state: StateFlow<ConfirmUiState> = _state

    fun digit(d: Int) {
        val s = _state.value
        if (s.busy || s.entered.length >= PASSCODE_LENGTH) return
        val next = s.entered + d
        _state.update { it.copy(entered = next, error = null) }
        if (next.length == PASSCODE_LENGTH) {
            viewModelScope.launch {
                _state.update { it.copy(busy = true) }
                val ok = appLock.matches(next)
                _state.update { if (ok) it.copy(confirmed = true, busy = false) else it.copy(entered = "", error = "Неверный код", busy = false) }
            }
        }
    }

    fun delete() = _state.update { it.copy(entered = it.entered.dropLast(1), error = null) }
}

data class SecurityUiState(
    val biometric: BiometricAvailability = BiometricAvailability.NONE,
    val biometricEnabled: Boolean = false,
)

@HiltViewModel
class SecurityViewModel @Inject constructor(private val appLock: AppLock) : ViewModel() {
    private val _state = MutableStateFlow(SecurityUiState(biometric = appLock.biometricAvailability()))
    val state: StateFlow<SecurityUiState> = _state

    init {
        viewModelScope.launch { appLock.biometricEnabled.collect { on -> _state.update { it.copy(biometricEnabled = on) } } }
    }

    fun setBiometric(enabled: Boolean) {
        viewModelScope.launch { appLock.setBiometricEnabled(enabled) }
    }
}
