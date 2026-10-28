package ru.finassist.pf.feature.auth.impl.domain

import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.time.ElapsedClock

/** A received phone 429 permits only an explicit new intent after its original server wait (FIN-363). */
internal class PhoneStartGate(clock: ElapsedClock) {
    private val gate = RequestRetryGate(clock)
    var newIntentRequired: Boolean = false
        private set
    val readiness get() = gate.readiness
    fun isReady() = gate.isReady()
    fun record(error: AppError) {
        if (error is AppError.RateLimited) {
            newIntentRequired = true
            // This gate delays a NEW key; it never replays the refused original request.
            gate.record(AppError.RateLimited(error.retryAt, error.retryAfterSeconds, error.response?.copy(requestRetryable = null)))
        } else gate.record(error)
    }
    fun clear() {
        newIntentRequired = false
        gate.clear()
    }
}
