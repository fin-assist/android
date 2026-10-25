package ru.finassist.pf.core.common.time

import java.time.Duration
import java.time.Instant

/** Android supplies elapsedRealtime (includes deep sleep); tests supply a controlled clock. */
fun interface ElapsedClock { fun nowMillis(): Long }

/** Receipt anchor is process-local and must not be persisted/reused as a fresh server response. */
data class ResponseTiming(val serverDate: Instant? = null, val receivedElapsedMillis: Long? = null) {
    fun deadline(durationSeconds: Long? = null, target: Instant? = null): ServerDeadline? {
        val anchor = receivedElapsedMillis ?: return null
        val millis = durationSeconds?.takeIf { it >= 0 }?.let {
            if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000
        } ?: target?.let { end -> serverDate?.let { start ->
            runCatching { Duration.between(start, end).toMillis().coerceAtLeast(0) }.getOrNull()
        } } ?: return null
        return ServerDeadline(anchor, millis)
    }
}

/** Display/wait only; never proof of permission, token validity, or key validity. */
class ServerDeadline internal constructor(private val anchorMillis: Long, private val durationMillis: Long) {
    fun remainingMillis(clock: ElapsedClock): Long? {
        val now = clock.nowMillis()
        if (now < anchorMillis) return null // lost monotonic anchor / reboot
        return (durationMillis - (now - anchorMillis)).coerceAtLeast(0)
    }
}

/** Client-side guard of an already established replay window; not a replacement for service validation. */
data class RequestReplayWindow(val startedElapsedMillis: Long, val validForMillis: Long) {
    fun isOpen(clock: ElapsedClock): Boolean {
        val elapsed = clock.nowMillis() - startedElapsedMillis
        return validForMillis > 0 && elapsed >= 0 && elapsed < validForMillis
    }
}
