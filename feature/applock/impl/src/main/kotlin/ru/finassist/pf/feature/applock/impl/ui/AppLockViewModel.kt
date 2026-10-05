package ru.finassist.pf.feature.applock.impl.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.finassist.pf.feature.applock.impl.domain.AppLockImpl
import javax.inject.Inject

@HiltViewModel
class AppLockViewModel @Inject constructor(private val lock: AppLockImpl) : ViewModel() {
    val biometricEnabled: StateFlow<Boolean> = lock.biometricEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    suspend fun setPasscode(code: String) = lock.setPasscode(code)
    suspend fun tryUnlock(code: String): Int? = lock.tryUnlock(code)
    suspend fun verify(code: String): Int? = lock.verify(code)
    fun unlockedByBiometric() = lock.unlockedByBiometric()
    fun setBiometric(enabled: Boolean) = viewModelScope.launch { lock.setBiometric(enabled) }
    fun confirmed(reason: String) = lock.confirmed(reason)
    fun forgot() = viewModelScope.launch { lock.forgot() }

    /** Error text for a wrong code by remaining attempts (README «PasscodeDots»). */
    fun wrongCodeText(remaining: Int): String = when (remaining) {
        0 -> "Неверный код. Попытки закончились"
        1 -> "Неверный код. Осталась 1 попытка"
        in 2..4 -> "Неверный код. Осталось $remaining попытки"
        else -> "Неверный код. Осталось $remaining попыток"
    }
}
