package ru.finassist.pf.core.common.availability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.OffsetDateTime

/**
 * Whether the app may work with the server (FIN-22): open, blocked until an update (`CLIENT_VERSION_UNSUPPORTED` or the
 * Remote Config minimum `app.min_version`), or under maintenance (`MAINTENANCE`). The network layer and the fake
 * backend report what the server answered; the app root covers everything with the matching screen.
 *
 * The reasons are kept apart and the screen shows the strongest one: an update requirement over maintenance. Lifting
 * one reason leaves the others — a corrected Remote Config minimum does not hide a maintenance still going on.
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

    private data class Reasons(
        val serverUpdate: Boolean = false,
        val configUpdate: Boolean = false,
        val maintenance: State.Maintenance? = null,
    ) {
        val state: State
            get() = when {
                serverUpdate -> State.UpdateRequired(fromServer = true)
                configUpdate -> State.UpdateRequired(fromServer = false)
                else -> maintenance ?: State.Open
            }
    }

    private val lock = Any()
    private var reasons = Reasons()
    /** A maintenance ended since the gate was last open: the screens behind it still show its errors. */
    private var maintenanceEnded = false
    private val _state = MutableStateFlow<State>(State.Open)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * How many times the gate opened after a maintenance ended: the root re-creates the screens to reload them. A
     * maintenance that ended while another reason kept the gate closed counts when that reason is lifted.
     */
    private val _reopened = MutableStateFlow(0)
    val reopened: StateFlow<Int> = _reopened.asStateFlow()

    /**
     * The maintenance in effect, also when an update requirement hides it from [state]: what a request that went through
     * ends with [reopen].
     */
    val maintenance: State.Maintenance? get() = synchronized(lock) { reasons.maintenance }

    /** All changes go through here, one at a time: no reason is lost to a concurrent answer. */
    private inline fun change(block: (Reasons) -> Reasons) {
        val opened = synchronized(lock) {
            val before = reasons
            reasons = block(before)
            if (before.maintenance != null && reasons.maintenance == null) maintenanceEnded = true
            _state.value = reasons.state
            (_state.value == State.Open && maintenanceEnded).also { if (it) maintenanceEnded = false }
        }
        if (opened) _reopened.update { it + 1 }
    }

    /** The server's `CLIENT_VERSION_UNSUPPORTED`: final. */
    fun updateRequired() = change { it.copy(serverUpdate = true) }

    /** The Remote Config minimum asks for an update. */
    fun updateRequiredByConfig() = change { it.copy(configUpdate = true) }

    /** A later config no longer asks for an update: lifts only what the config set. */
    fun configAllowsVersion() = change { it.copy(configUpdate = false) }

    /** Maintenance from [now]: retry after [retryAfterSeconds] (the server's `Retry-After`, [DEFAULT_RETRY_SECONDS] without one). */
    fun maintenance(until: OffsetDateTime?, retryAfterSeconds: Int?, now: OffsetDateTime) = change {
        val wait = (retryAfterSeconds ?: DEFAULT_RETRY_SECONDS).coerceAtLeast(MIN_RETRY_SECONDS).toLong()
        it.copy(maintenance = State.Maintenance(until, now.plusSeconds(wait)))
    }

    /**
     * A request went through after maintenance. With [expected], only that very maintenance ends: a newer one (set by a
     * nested refresh, say) stays.
     */
    fun reopen(expected: State.Maintenance? = null) = change {
        val m = it.maintenance
        if (m == null || (expected != null && m != expected)) it else it.copy(maintenance = null)
    }

    companion object {
        const val DEFAULT_RETRY_SECONDS = 60
        /** Not more often than this, whatever `Retry-After` says: no frequent polling (FIN-22). */
        const val MIN_RETRY_SECONDS = 15
    }
}
