package ru.finassist.pf.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.common.time.ElapsedClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `Idempotency-Key` for an action must survive process death (api.md: "клиент хранит его до окончательного
 * ответа"). A feature asks for the key of a logical action (`"statements.upload:<file-hash>"`); the same action
 * gets the same key until [complete] is called. An expired/unknown replay window never silently turns
 * a pending request into a new logical action. Only complete removes that action.
 */
@Singleton
class IdempotencyKeys @Inject constructor(private val dataStore: DataStore<Preferences>) {

    // Accessed only inside the same DataStore edit transaction as the persistent record.
    private val inProcess = mutableMapOf<String, IdempotencyKey>()

    suspend fun keyFor(action: String): IdempotencyKey = keyForInternal(action, null)
    suspend fun keyFor(action: String, clock: ElapsedClock): IdempotencyKey = keyForInternal(action, clock)

    /** Restored records retain UUID but have UNKNOWN age; wall timestamps are never safety evidence. */
    private suspend fun keyForInternal(action: String, clock: ElapsedClock?): IdempotencyKey {
        val prefKey = stringPreferencesKey(PREFIX + action)
        var result: IdempotencyKey? = null
        dataStore.edit { prefs ->
            val raw = prefs[prefKey]
            val stored = raw?.substringBefore("|")?.takeIf { it.isNotBlank() }
            check(raw == null || stored != null) { "Invalid pending request record" }
            result = if (stored != null) {
                inProcess[action]?.takeIf { it.value == stored } ?: IdempotencyKey(stored)
            } else {
                (clock?.let(IdempotencyKey::random) ?: IdempotencyKey.random()).also {
                    // Preserve the old record format for rollback; the timestamp is not used to renew a key.
                    prefs[prefKey] = "${it.value}|${System.currentTimeMillis()}"
                    inProcess[action] = it
                }
            }
        }
        return checkNotNull(result)
    }

    suspend fun complete(action: String) {
        dataStore.edit {
            it.remove(stringPreferencesKey(PREFIX + action))
            inProcess.remove(action)
        }
    }

    private companion object {
        const val PREFIX = "idem."
    }
}
