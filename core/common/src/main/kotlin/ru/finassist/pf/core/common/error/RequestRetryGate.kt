package ru.finassist.pf.core.common.error

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.ResponseTiming
import ru.finassist.pf.core.common.time.ServerDeadline

/**
 * Restricts an already supported callback for ONE original request. It never grants permission, chooses an
 * action, or proves a keyed POST safe. The caller separately preserves the request/body/key and checks its TTL.
 * Use a separate instance for each operation; [clear] means success or an explicit change of its context/input.
 * Result errors and their new-attempt permissions do not belong here.
 */
class RequestRetryGate(private val clock: ElapsedClock) {
    private data class Restriction(val forbidden: Boolean = false, val needsWait: Boolean = false, val deadline: ServerDeadline? = null)
    private val restriction = MutableStateFlow(Restriction())

    /** Capture ONCE at receipt, never on a retry tap or a new collection of [readiness]. */
    fun record(error: AppError) {
        val response = error.response
        if (response != null && response.context != ErrorContext.REQUEST) return
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
        restriction.value = Restriction(error.requestRetryable == false, needsWait, timing.deadline(seconds, target?.toInstant()))
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
