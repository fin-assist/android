package ru.finassist.pf.core.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import ru.finassist.pf.core.common.error.AppError
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Pause schedule of re-opening an SSE stream (api.md «Общие правила» → «SSE», FIN-170). The base pause d starts at
 * [BASE_MS] and doubles after each drop up to [MAX_MS]; the actual pause is random in [d/2; d], so that clients do not
 * all come back at once after a common failure. 429 on a re-open waits for the server's time plus random [0; d/2],
 * and d doubles as after a drop. [reset] brings d back to [BASE_MS].
 */
class SseBackoff(private val random: Random = Random.Default) {
    /** Current base pause d, ms. */
    var baseMs: Long = BASE_MS
        private set

    /** Pause before re-opening after a drop: random in [d/2; d], both ends included; d then doubles. */
    fun afterDrop(): Long {
        val d = baseMs
        grow()
        return random.nextLong(d / 2, d + 1)
    }

    /** Pause after 429 on a re-open: [waitMs] (until `retry_at`, not below 0) plus random [0; d/2]; d then doubles. */
    fun afterRateLimit(waitMs: Long): Long {
        val d = baseMs
        grow()
        return waitMs.coerceAtLeast(0) + random.nextLong(0, d / 2 + 1)
    }

    fun reset() {
        baseMs = BASE_MS
    }

    private fun grow() {
        baseMs = (baseMs * 2).coerceAtMost(MAX_MS)
    }

    companion object {
        const val BASE_MS = 1_000L
        const val MAX_MS = 30_000L
        /** A stream that worked this long without a drop resets d. */
        const val HEALTHY_MS = 30_000L
    }
}

/**
 * Whether a stream is in re-open mode: it has brought an event or dropped, and no terminal event came since. Kept by the
 * caller per stream key across collections ([reopeningStream]); not thread-safe — one collection at a time.
 */
class SseReopenState {
    var reopening: Boolean = false
        internal set
}

/**
 * The one re-open loop of the three SSE streams (1.2 verification, 3.3 import progress, 7.2 assistant answer), for every
 * flavor: collects [open] and, after a drop, opens it again with the pauses of [SseBackoff]. Re-collecting loses
 * nothing: the first event of a stream is the full current state.
 *
 * - A drop is a stream that ends without a terminal event ([isTerminal]) — the server closes it on its own failure
 *   instead of sending `error` — or one that fails with [AppError.Offline] or a 5xx [AppError.Server]. The caller sees
 *   neither: the events of the re-opened stream simply follow.
 * - d resets after a terminal event, or when the dropped stream had been open (its first event) for
 *   [SseBackoff.HEALTHY_MS]. Events alone do not reset it: a server that fails right after the snapshot would
 *   otherwise be re-opened without pauses.
 * - 429 on a re-open: wait until `retry_at` — `Retry-After` when present (relative, independent of the device clock),
 *   else `retry_at` against [wallClock] — plus random [0; d/2]; d doubles. Without either, the pause of a drop.
 * - Everything else (401, 404, a malformed event, 429 on the first opening) goes to the caller as before.
 * - [reopenFirstFailure] false: a failure of the very first opening, before any event, goes to the caller too, so a
 *   screen keeps its own «нет сети» state for a stream that could not be opened at all. A clean close without a
 *   terminal event is a drop either way.
 * - [state] carries «this stream is being re-opened» across collections of the same stream (one per stream key —
 *   token, upload, answer — for the life of the screen): the loop marks it at the first event or the first drop,
 *   whichever comes first. A screen that opens the stream again (it came back, a timer re-checks) passes the same
 *   holder, so a 429 on that opening is waited for too instead of going to the caller. A terminal event clears it.
 *   [reopenFirstFailure] still looks at this collection only: a stream that cannot be opened after the screen came
 *   back shows the screen's «нет сети» state, as on the first visit.
 *
 * The flow completes after the stream that brought the terminal event ends. Cancelling the collector stops it,
 * pauses included.
 */
fun <T> reopeningStream(
    isTerminal: (T) -> Boolean,
    reopenFirstFailure: Boolean = true,
    state: SseReopenState = SseReopenState(),
    random: Random = Random.Default,
    timeSource: TimeSource = TimeSource.Monotonic,
    wallClock: () -> Instant = Instant::now,
    open: () -> Flow<T>,
): Flow<T> = flow {
    val backoff = SseBackoff(random)
    // A drop has happened in this collection (the first-failure rule looks at this one only).
    var reopening = false
    while (true) {
        var openedAt: TimeMark? = null
        var terminal = false
        var failure: Throwable? = null
        open()
            .onEach { event ->
                if (openedAt == null) {
                    openedAt = timeSource.markNow()
                    state.reopening = true
                }
                if (isTerminal(event)) terminal = true
            }
            // Upstream failures only: an exception of the collector passes through untouched.
            .catch { e ->
                if (e is CancellationException) throw e
                failure = e
            }
            .collect { emit(it) }
        if (terminal) {
            // The server closes the stream after its terminal event; a failure of that close changes nothing.
            backoff.reset()
            state.reopening = false
            return@flow
        }
        val error = failure
        val pause = when {
            error == null || error.isDrop() -> {
                if (error != null && !reopening && openedAt == null && !reopenFirstFailure) throw error
                if ((openedAt?.elapsedNow() ?: kotlin.time.Duration.ZERO) >= SseBackoff.HEALTHY_MS.milliseconds) backoff.reset()
                backoff.afterDrop()
            }
            error is AppError.RateLimited && state.reopening -> {
                val wait = error.retryAfterSeconds?.let { it * 1_000 }
                    ?: error.retryAt?.let { it.toInstant().toEpochMilli() - wallClock().toEpochMilli() }
                if (wait != null) backoff.afterRateLimit(wait) else backoff.afterDrop()
            }
            else -> throw error
        }
        reopening = true
        state.reopening = true
        delay(pause)
    }
}

private fun Throwable.isDrop(): Boolean =
    this is AppError.Offline || (this is AppError.Server && httpStatus >= 500)
