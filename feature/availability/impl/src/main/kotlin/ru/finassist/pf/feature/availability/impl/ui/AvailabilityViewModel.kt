package ru.finassist.pf.feature.availability.impl.ui

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
) : ViewModel() {
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private var scheduled: Job? = null

    init {
        viewModelScope.launch {
            gate.state.collect { s -> if (s is ServiceGate.State.Maintenance) schedule(s) else scheduled?.cancel() }
        }
    }

    private fun schedule(s: ServiceGate.State.Maintenance) {
        scheduled?.cancel()
        scheduled = viewModelScope.launch {
            val wait = Duration.between(clock.now(), s.retryAt).toMillis()
            if (wait > 0) delay(wait)
            probe()
        }
    }

    /** «Проверить сейчас»: replaces the scheduled check (the new state of the gate schedules the next one). */
    fun checkNow() {
        if (_checking.value) return
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
        val before = gate.state.value as? ServiceGate.State.Maintenance
        try {
            auth.getConsentDocument(ConsentType.PERSONAL_DATA)
            gate.reopen(before)
        } catch (e: AppError) {
            // Maintenance again: the gate already holds the new retry moment. Anything else (no network, a server
            // error): the screen stays, the next check after the same pause.
            val s = gate.state.value
            if (e !is AppError.Maintenance && s is ServiceGate.State.Maintenance) {
                gate.maintenance(s.until, ServiceGate.DEFAULT_RETRY_SECONDS, clock.now())
            }
        } finally {
            _checking.value = false
            probing.unlock()
        }
    }
}
