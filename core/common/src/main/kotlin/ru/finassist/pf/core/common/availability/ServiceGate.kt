package ru.finassist.pf.core.common.availability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.OffsetDateTime

/**
 * Whether the app may work with the server (FIN-22): open, blocked until an update (`CLIENT_VERSION_UNSUPPORTED` or the
 * Remote Config minimum `app.min_version`), or under maintenance (`MAINTENANCE`). The network layer and the fake
 * backend report what the server answered; the app root covers everything with the matching screen.
 */
class ServiceGate {
    sealed interface State {
        data object Open : State

        /**
         * This version is no longer supported: nothing works until the app is updated. Told by the server
         * ([fromServer], final) or by the Remote Config minimum (lifted when a later config no longer asks for it).
         */
        data class UpdateRequired(val fromServer: Boolean = true) : State

        /** Maintenance: [until] — expected end (null: unknown); [retryAt] — not to ask the server before it. */
        data class Maintenance(val until: OffsetDateTime?, val retryAt: OffsetDateTime) : State
    }

    private val _state = MutableStateFlow<State>(State.Open)
    val state: StateFlow<State> = _state

    /** How many times the gate reopened after maintenance: the root re-creates the screens to reload them. */
    private val _reopened = MutableStateFlow(0)
    val reopened: StateFlow<Int> = _reopened

    /** The server's `CLIENT_VERSION_UNSUPPORTED`: final. */
    fun updateRequired() = _state.update { State.UpdateRequired(fromServer = true) }

    /** The Remote Config minimum asks for an update; the server's own answer is not replaced. */
    fun updateRequiredByConfig() = _state.update { s -> if (s is State.UpdateRequired) s else State.UpdateRequired(fromServer = false) }

    /** A later config no longer asks for an update: lifts only what the config set. */
    fun configAllowsVersion() = _state.update { s -> if (s == State.UpdateRequired(fromServer = false)) State.Open else s }

    /** Maintenance from [now]: retry after [retryAfterSeconds] (the server's `Retry-After`, [DEFAULT_RETRY_SECONDS] without one). */
    fun maintenance(until: OffsetDateTime?, retryAfterSeconds: Int?, now: OffsetDateTime) = _state.update { s ->
        if (s is State.UpdateRequired) s
        else State.Maintenance(until, now.plusSeconds((retryAfterSeconds ?: DEFAULT_RETRY_SECONDS).coerceAtLeast(MIN_RETRY_SECONDS).toLong()))
    }

    /**
     * A request went through after maintenance. With [expected], only that very maintenance ends: a newer one (set by a
     * nested refresh, say) stays. Atomic: an update requirement set meanwhile is never overwritten.
     */
    fun reopen(expected: State.Maintenance? = null) {
        while (true) {
            val s = _state.value
            if (s !is State.Maintenance || (expected != null && s != expected)) return
            if (_state.compareAndSet(s, State.Open)) {
                _reopened.update { it + 1 }
                return
            }
        }
    }

    companion object {
        const val DEFAULT_RETRY_SECONDS = 60
        /** Not more often than this, whatever `Retry-After` says: no frequent polling (FIN-22). */
        const val MIN_RETRY_SECONDS = 15
    }
}
