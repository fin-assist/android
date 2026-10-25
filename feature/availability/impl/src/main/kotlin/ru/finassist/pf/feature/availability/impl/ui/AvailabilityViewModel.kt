package ru.finassist.pf.feature.availability.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import java.time.Duration
import javax.inject.Inject

/**
 * Maintenance (FIN-22): asks the server again not before `Retry-After` — once, not by polling — and on «Проверить сейчас».
 * The probe is `GET /v1/consents/personal_data`: it needs no session and goes through every check of the server. Its
 * answer reaches the [ServiceGate] the usual way (network layer, fake backend): another maintenance moves the next
 * check, a success reopens the app.
 */
@HiltViewModel
class AvailabilityViewModel @Inject constructor(
    val gate: ServiceGate,
    private val auth: AuthApi,
    private val clock: Clock,
    private val elapsedClock: ElapsedClock,
) : ViewModel() {
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()
    private val _canCheck = MutableStateFlow(false)
    val canCheck: StateFlow<Boolean> = _canCheck.asStateFlow()

    private var scheduled: Job? = null
    private var readiness: Job? = null
    private var automaticProbeUsed = false

    init {
        viewModelScope.launch {
            gate.state.collect { s ->
                readiness?.cancel()
                if (s is ServiceGate.State.Maintenance) {
                    _canCheck.value = s.deadline?.remainingMillis(elapsedClock) == 0L
                    readiness = viewModelScope.launch {
                        while (true) {
                            val left = s.deadline?.remainingMillis(elapsedClock)
                            _canCheck.value = left == 0L
                            if (left == null || left == 0L) break
                            delay(minOf(left, 250L))
                        }
                    }
                    if (!automaticProbeUsed) schedule(s)
                } else {
                    _canCheck.value = false
                    scheduled?.cancel()
                    automaticProbeUsed = false
                }
            }
        }
    }

    private fun schedule(s: ServiceGate.State.Maintenance) {
        scheduled?.cancel()
        scheduled = viewModelScope.launch {
            val wait = s.deadline?.remainingMillis(elapsedClock) ?: return@launch
            if (wait > 0) delay(wait)
            automaticProbeUsed = true
            probe()
        }
    }

    /** «Проверить сейчас»: replaces the scheduled check (the new state of the gate schedules the next one). */
    fun checkNow() {
        if (_checking.value) return
        val maintenance = gate.maintenance ?: return
        if (maintenance.deadline?.remainingMillis(elapsedClock) != 0L) return
        automaticProbeUsed = true
        scheduled?.cancel()
        viewModelScope.launch { probe() }
    }

    /** One probe at a time, whoever asked: the button and the timer never send two requests. */
    private val probing = Mutex()

    private suspend fun probe() {
        if (!probing.tryLock()) return
        _checking.value = true
        // Only the maintenance this probe was sent under ends with its answer: requests of the screens re-created right
        // after it may have met a new one.
        val before = gate.maintenance
        try {
            auth.getConsentDocument(ConsentType.PERSONAL_DATA)
            gate.reopen(before)
        } catch (e: AppError) {
            // Maintenance again: the gate already holds the new retry moment. Anything else (no network, a server
            // error): the screen stays, the next check after the same pause.
            // A fresh refusal never starts another automatic loop. A user may make a guarded manual check.
        } finally {
            _checking.value = false
            probing.unlock()
        }
    }
}
