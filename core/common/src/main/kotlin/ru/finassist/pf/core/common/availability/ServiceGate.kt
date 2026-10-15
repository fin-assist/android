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

        /** This version is no longer supported: nothing works until the app is updated. Never reopens. */
        data object UpdateRequired : State

        /** Maintenance: [until] — expected end (null: unknown); [retryAt] — not to ask the server before it. */
        data class Maintenance(val until: OffsetDateTime?, val retryAt: OffsetDateTime) : State
    }

    private val _state = MutableStateFlow<State>(State.Open)
    val state: StateFlow<State> = _state

    /** How many times the gate reopened after maintenance: the root re-creates the screens to reload them. */
    private val _reopened = MutableStateFlow(0)
    val reopened: StateFlow<Int> = _reopened

    fun updateRequired() = _state.update { State.UpdateRequired }

    /** Maintenance from [now]: retry after [retryAfterSeconds] (the server's `Retry-After`, [DEFAULT_RETRY_SECONDS] without one). */
    fun maintenance(until: OffsetDateTime?, retryAfterSeconds: Int?, now: OffsetDateTime) = _state.update { s ->
        if (s == State.UpdateRequired) s
        else State.Maintenance(until, now.plusSeconds((retryAfterSeconds ?: DEFAULT_RETRY_SECONDS).coerceAtLeast(MIN_RETRY_SECONDS).toLong()))
    }

    /** A request went through after maintenance. */
    fun reopen() {
        if (_state.value !is State.Maintenance) return
        _state.value = State.Open
        _reopened.update { it + 1 }
    }

    companion object {
        const val DEFAULT_RETRY_SECONDS = 60
        /** Not more often than this, whatever `Retry-After` says: no frequent polling (FIN-22). */
        const val MIN_RETRY_SECONDS = 15
    }
}
