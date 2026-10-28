package ru.finassist.pf.feature.auth.impl.domain

import java.time.OffsetDateTime
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.time.ElapsedClock

/** A received phone 429 permits only an explicit new intent after its original server wait (FIN-363). */
internal class PhoneStartGate(clock: ElapsedClock) {
    data class RateLimit(val retryAt: OffsetDateTime?, val presentation: ErrorPresentation, val requestRetryable: Boolean?)
    private val gate = RequestRetryGate(clock)
    var receivedRateLimit: RateLimit? = null
        private set
    var newIntentRequired: Boolean = false
        private set
    val readiness get() = gate.readiness
    fun isReady() = gate.isReady()
    fun isExplicitlyForbidden() = gate.isExplicitlyForbidden()
    fun record(error: AppError) {
        if (error is AppError.RateLimited) {
            newIntentRequired = true
            receivedRateLimit = RateLimit(error.retryAt ?: error.response?.retryAt, error.presentation, error.requestRetryable)
            // This gate delays a NEW key; it never replays the refused original request.
            gate.record(AppError.RateLimited(error.retryAt, error.retryAfterSeconds, error.response?.copy(requestRetryable = null)))
        } else gate.record(error)
    }
    fun clear() {
        newIntentRequired = false
        receivedRateLimit = null
        gate.clear()
    }
}
