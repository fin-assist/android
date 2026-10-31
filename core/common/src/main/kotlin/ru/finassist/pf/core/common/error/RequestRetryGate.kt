package ru.finassist.pf.core.common.error

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.ResponseTiming
import ru.finassist.pf.core.common.time.ServerDeadline

/**
 * Restricts an already supported callback for ONE original request. It never grants permission, chooses an
 * action, or proves a keyed POST safe. The caller separately preserves the request/body/key and checks its TTL.
 * Use a separate instance for each operation. [clear] resets the whole restriction; [clearOriginalRefusal]
 * retires only an input-specific refusal while preserving its captured endpoint wait.
 * Result errors and their new-attempt permissions do not belong here.
 */
class RequestRetryGate(private val clock: ElapsedClock) {
    private data class Restriction(val forbidden: Boolean = false, val needsWait: Boolean = false, val deadline: ServerDeadline? = null)
    private val restriction = MutableStateFlow(Restriction())

    /** Capture ONCE at receipt, never on a retry tap or a new collection of [readiness]. */
    fun record(error: AppError) {
        capture(error)?.let { restriction.value = it }
    }

    private fun capture(error: AppError): Restriction? {
        val response = error.response
        if (response != null && response.context != ErrorContext.REQUEST) return null
        val seconds = response?.retryAfterSeconds ?: when (error) {
            is AppError.RateLimited -> error.retryAfterSeconds
            is AppError.InProgress -> error.retryAfterSeconds?.toLong()
            is AppError.Maintenance -> error.retryAfterSeconds?.toLong()
            else -> null
        }
        val target = response?.retryAt ?: when (error) {
            is AppError.RateLimited -> error.retryAt
            is AppError.Api -> error.retryAt
            else -> null
        }
        val needsWait = seconds?.let { it >= 0 } == true || target != null
        // Legacy typed failures are freshly caught here, so relative seconds can use this receipt. An absolute
        // target still needs Date from that response; phone wall time is never a substitute.
        val timing = response?.timing ?: ResponseTiming(receivedElapsedMillis = clock.nowMillis())
        return Restriction(error.requestRetryable == false, needsWait, timing.deadline(seconds, target?.toInstant()))
    }

    /** A changed input retires only its original-request refusal; the endpoint's captured wait still applies. */
    fun clearOriginalRefusal() {
        restriction.update { it.copy(forbidden = false) }
    }

    /**
     * A stale input may still reveal an endpoint wait, but cannot transfer its refusal to the current input.
     * Keep the exact captured deadlines; neither a shorter wait nor an unanchored reply can grant an early retry.
     */
    fun recordEndpointWait(error: AppError) {
        val received = capture(error) ?: return
        if (!received.needsWait) return
        val observedAt = clock.nowMillis()
        val observation = ElapsedClock { observedAt }
        restriction.update { current ->
            val deadline = if (!current.needsWait) received.deadline else {
                val currentLeft = current.deadline?.remainingMillis(observation)
                val receivedLeft = received.deadline?.remainingMillis(observation)
                when {
                    currentLeft == null || receivedLeft == null -> null
                    currentLeft >= receivedLeft -> current.deadline
                    else -> received.deadline
                }
            }
            current.copy(needsWait = true, deadline = deadline)
        }
    }

    fun clear() { restriction.value = Restriction() }

    fun remainingMillis(): Long? = restriction.value.let { if (it.needsWait) it.deadline?.remainingMillis(clock) else 0L }

    /** Explicit original-request refusal, independent of waiting; any recovery action remains caller-owned. */
    fun isExplicitlyForbidden(): Boolean = restriction.value.forbidden

    fun isReady(): Boolean = restriction.value.let { !it.forbidden && (!it.needsWait || (it.deadline?.remainingMillis(clock) == 0L)) }

    /** VM lifecycle collectors update button state with the same sleep-aware monotonic deadline. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val readiness: Flow<Boolean> = restriction.flatMapLatest { current ->
        flow {
            emit(isReady())
            while (!current.forbidden && current.needsWait) {
                val left = current.deadline?.remainingMillis(clock) ?: break
                if (left == 0L) break
                delay(minOf(left, 250L))
                emit(isReady())
            }
        }
    }.distinctUntilChanged()
    /** Wait only, not original-request permission: callers may offer their explicit new-context action afterwards. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val waitReadiness: Flow<Boolean> = restriction.flatMapLatest { current ->
        flow {
            emit(remainingMillis() == 0L)
            while (current.needsWait) {
                val left = current.deadline?.remainingMillis(clock) ?: break
                if (left == 0L) break
                delay(minOf(left, 250L))
                emit(remainingMillis() == 0L)
            }
        }
    }.distinctUntilChanged()

}
