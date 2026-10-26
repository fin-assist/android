package ru.finassist.pf.feature.profile.impl.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.finassist.pf.core.storage.AuthSessionIdentityPreferences
import java.util.UUID
import javax.inject.Inject

/** Durable consumer state, separate from process-local ServerDeadline/RequestRetryGate. No tokens or error text. */
class ZoneSyncStateStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    @Serializable
    data class Owner(val userId: String, val epoch: String) {
        init { require(userId.isNotBlank() && epoch.isNotBlank()) }
    }

    enum class Operation { GET, PATCH }
    @Serializable enum class Phase { IN_FLIGHT, RESTRICTED }

    /** Proven same-boot sleep-aware elapsed deadline; never a fresh response or a wall-clock timestamp. */
    @Serializable
    data class Wait(val bootCount: Int, val capturedElapsedMillis: Long, val deadlineElapsedMillis: Long) {
        init { require(bootCount >= 0 && capturedElapsedMillis >= 0 && deadlineElapsedMillis >= capturedElapsedMillis) }
        fun fulfilled(boot: Int?, elapsed: Long): Boolean =
            boot == bootCount && elapsed >= capturedElapsedMillis && elapsed >= deadlineElapsedMillis
    }

    @Serializable
    data class Attempt(
        val id: String,
        val phase: Phase,
        @Required val forbidden: Boolean = false,
        @Required val needsWait: Boolean = false,
        @Required val wait: Wait? = null,
    ) {
        init { require(id.isNotBlank() && (wait == null || needsWait)) }
        fun ready(boot: Int?, elapsed: Long): Boolean =
            phase == Phase.RESTRICTED && !forbidden && (!needsWait || wait?.fulfilled(boot, elapsed) == true)
    }

    @Serializable
    data class Request(@Required val target: String? = null, @Required val attempt: Attempt? = null)

    @Serializable
    data class State(
        @Required val version: Int = 1,
        val owner: Owner,
        val revision: String,
        @Required val get: Request = Request(),
        @Required val patch: Request = Request(),
    ) {
        init {
            require(version == 1 && revision.isNotBlank() && get.target == null)
            require(patch.target == null || patch.target.isNotBlank())
            require(patch.attempt == null || patch.target != null)
        }
        fun request(operation: Operation): Request = if (operation == Operation.GET) get else patch
    }

    data class Ticket(val owner: Owner, val operation: Operation, val revision: String, val actionId: String)

    /** A new login is an actual context change. Unknown/corrupt schema throws; it never becomes an empty gate. */
    suspend fun bind(owner: Owner): State? = mutate(owner) { current ->
        current?.takeIf { it.owner == owner } ?: State(owner = owner, revision = nextId())
    }

    suspend fun current(owner: Owner): State? {
        val preferences = dataStore.data.first()
        if (!AuthSessionIdentityPreferences.matches(preferences, owner.userId, owner.epoch)) return null
        return decode(preferences)?.takeIf { it.owner == owner }
    }

    /** Only a genuinely different PATCH input retires that PATCH restriction. GET is independent. */
    suspend fun target(expected: State, target: String): State? {
        require(target.isNotBlank())
        return compare(expected) {
            if (it.patch.target == target) it else it.copy(revision = nextId(), patch = Request(target))
        }
    }

    /** Must finish durably before the caller dispatches RPC. A crashed/unfinished action is never ready. */
    suspend fun begin(expected: State, operation: Operation, boot: Int?, elapsed: Long): Ticket? {
        val request = expected.request(operation)
        if (operation == Operation.PATCH && request.target == null) return null
        if (request.attempt?.ready(boot, elapsed) == false) return null
        val action = Attempt(nextId(), Phase.IN_FLIGHT)
        val updated = compare(expected) { it.with(operation, request.copy(attempt = action)) } ?: return null
        return Ticket(updated.owner, operation, updated.revision, action.id)
    }

    /** Null outcome is a confirmed success; a known error records only retry restrictions, never UI strings. */
    suspend fun finish(ticket: Ticket, outcome: Attempt?): State? = mutate(ticket.owner) { current ->
        if (current == null || current.revision != ticket.revision ||
            current.request(ticket.operation).attempt?.let { it.id == ticket.actionId && it.phase == Phase.IN_FLIGHT } != true
        ) return@mutate null
        require(outcome == null || outcome.id == ticket.actionId && outcome.phase == Phase.RESTRICTED)
        current.with(ticket.operation, current.request(ticket.operation).copy(attempt = outcome))
    }

    private suspend fun compare(expected: State, change: (State) -> State): State? = mutate(expected.owner) {
        if (it != expected) null else change(it)
    }

    private suspend fun mutate(owner: Owner, change: (State?) -> State?): State? {
        var result: State? = null
        dataStore.edit { preferences ->
            if (!AuthSessionIdentityPreferences.matches(preferences, owner.userId, owner.epoch)) return@edit
            result = change(decode(preferences))
            result?.let { preferences[key] = json.encodeToString(it) }
        }
        return result
    }

    private fun State.with(operation: Operation, request: Request): State =
        if (operation == Operation.GET) copy(revision = nextId(), get = request)
        else copy(revision = nextId(), patch = request)

    private fun decode(preferences: Preferences): State? = preferences[key]?.let { json.decodeFromString<State>(it) }

    private companion object {
        val key = stringPreferencesKey("profile.tz_sync.state")
        val json = Json { encodeDefaults = true }
        fun nextId(): String = UUID.randomUUID().toString()
    }
}
