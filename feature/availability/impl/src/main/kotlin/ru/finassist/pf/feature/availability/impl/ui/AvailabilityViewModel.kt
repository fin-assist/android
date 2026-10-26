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
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.time.Clock
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

    private val probeRetryGate = RequestRetryGate(elapsedClock)
    private var scheduled: Job? = null
    private var readiness: Job? = null
    private var automaticProbeUsed = false

    init {
        viewModelScope.launch { probeRetryGate.readiness.collect { updateReadiness() } }
        viewModelScope.launch {
            gate.state.collect { s ->
                readiness?.cancel()
                if (s is ServiceGate.State.Maintenance) {
                    updateReadiness()
                    readiness = viewModelScope.launch {
                        while (true) {
                            val left = s.deadline?.remainingMillis(elapsedClock)
                            updateReadiness()
                            if (left == null || left == 0L) break
                            delay(minOf(left, 250L))
                        }
                    }
                    if (!automaticProbeUsed) schedule(s)
                } else {
                    _canCheck.value = false
                    scheduled?.cancel()
                    automaticProbeUsed = false
                    if (s == ServiceGate.State.Open) probeRetryGate.clear()
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
        if (!probeReady()) return
        automaticProbeUsed = true
        scheduled?.cancel()
        viewModelScope.launch { probe() }
    }

    private fun probeReady(): Boolean {
        val maintenance = gate.state.value as? ServiceGate.State.Maintenance ?: return false
        return maintenance.deadline?.remainingMillis(elapsedClock) == 0L && probeRetryGate.isReady()
    }

    private fun updateReadiness() {
        _canCheck.value = !_checking.value && probeReady()
    }

    /** One probe at a time, whoever asked: the button and the timer never send two requests. */
    private val probing = Mutex()

    private suspend fun probe() {
        if (!probeReady() || !probing.tryLock()) return
        _checking.value = true
        updateReadiness()
        // Only the maintenance this probe was sent under ends with its answer: requests of the screens re-created right
        // after it may have met a new one.
        val before = gate.maintenance
        try {
            auth.getConsentDocument(ConsentType.PERSONAL_DATA)
            probeRetryGate.clear()
            gate.reopen(before)
        } catch (e: AppError) {
            // Only the known maintenance correction uses ServiceGate's deadline. A later independent refusal of
            // this GET retains its own original-request prohibition/wait, even if maintenance's old deadline expired.
            if (ServiceGate.recoveryDeadline(e, elapsedClock) == null) probeRetryGate.record(e)
            // A fresh refusal never starts another automatic loop. Manual checks obey both independent gates.
        } finally {
            _checking.value = false
            updateReadiness()
            probing.unlock()
        }
    }
}
