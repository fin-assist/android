package ru.finassist.pf.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import ru.finassist.pf.core.api.model.IdempotencyKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `Idempotency-Key` for an action must survive process death (api.md: "клиент хранит его до окончательного
 * ответа"). A feature asks for the key of a logical action (`"statements.upload:<file-hash>"`); the same action
 * gets the same key until [complete] is called. Entries older than 24 h are dropped lazily — the server
 * forgets them at the same age, so a retry after that is a new action anyway.
 */
@Singleton
class IdempotencyKeys @Inject constructor(private val dataStore: DataStore<Preferences>) {

    suspend fun keyFor(action: String): IdempotencyKey {
        val prefKey = stringPreferencesKey(PREFIX + action)
        val now = System.currentTimeMillis()
        val stored = dataStore.data.first()[prefKey]?.let(::parse)
        if (stored != null && now - stored.second < TTL_MS) return IdempotencyKey(stored.first)
        val fresh = IdempotencyKey.random()
        dataStore.edit { it[prefKey] = "${fresh.value}|$now" }
        return fresh
    }

    suspend fun complete(action: String) {
        dataStore.edit { it.remove(stringPreferencesKey(PREFIX + action)) }
    }

    private fun parse(raw: String): Pair<String, Long>? {
        val parts = raw.split("|")
        if (parts.size != 2) return null
        return parts[0] to (parts[1].toLongOrNull() ?: return null)
    }

    private companion object {
        const val PREFIX = "idem."
        const val TTL_MS = 24L * 60 * 60 * 1000
    }
}
