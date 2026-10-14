package ru.finassist.pf.feature.profile.impl.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import ru.finassist.pf.feature.profile.impl.domain.PendingZone
import java.time.Instant
import javax.inject.Inject

/**
 * The device zone that waits for the threshold of [ru.finassist.pf.feature.profile.impl.domain.TimeZoneRule]. Persisted:
 * the 72 hours span many process starts. Kept per user, so a record left by another account is never counted.
 */
class PendingZoneStore @Inject constructor(private val dataStore: DataStore<Preferences>) {

    suspend fun get(userId: String): PendingZone? {
        val prefs = dataStore.data.first()
        if (prefs[KEY_USER] != userId) return null
        val zone = prefs[KEY_ZONE] ?: return null
        val since = prefs[KEY_SINCE] ?: return null
        return PendingZone(zone, Instant.ofEpochMilli(since))
    }

    suspend fun set(userId: String, pending: PendingZone) {
        dataStore.edit {
            it[KEY_USER] = userId
            it[KEY_ZONE] = pending.zone
            it[KEY_SINCE] = pending.since.toEpochMilli()
        }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(KEY_USER)
            it.remove(KEY_ZONE)
            it.remove(KEY_SINCE)
        }
    }

    private companion object {
        val KEY_USER = stringPreferencesKey("profile.tz_pending.user")
        val KEY_ZONE = stringPreferencesKey("profile.tz_pending.zone")
        val KEY_SINCE = longPreferencesKey("profile.tz_pending.since")
    }
}
