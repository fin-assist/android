package ru.finassist.pf.feature.profile.impl.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import ru.finassist.pf.core.storage.AuthSessionIdentityPreferences
import javax.inject.Inject

/**
 * The last profile zone read from or written to the server, per user. Restored into `ReportZone` on start, so an
 * offline start groups by the profile zone, not the device's (FIN-92).
 */
class KnownZoneStore @Inject constructor(private val dataStore: DataStore<Preferences>) {

    suspend fun get(userId: String): String? {
        val prefs = dataStore.data.first()
        return prefs[KEY_ZONE]?.takeIf { prefs[KEY_USER] == userId }
    }

    suspend fun set(userId: String, zone: String) {
        dataStore.edit {
            it[KEY_USER] = userId
            it[KEY_ZONE] = zone
        }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(KEY_USER)
            it.remove(KEY_ZONE)
        }
    }

    suspend fun set(owner: ZoneSyncStateStore.Owner, zone: String) {
        dataStore.edit {
            if (!AuthSessionIdentityPreferences.matches(it, owner.userId, owner.epoch)) return@edit
            it[KEY_USER] = owner.userId
            it[KEY_ZONE] = zone
        }
    }

    suspend fun clearSignedOut() {
        dataStore.edit {
            if (!AuthSessionIdentityPreferences.signedOut(it)) return@edit
            it.remove(KEY_USER); it.remove(KEY_ZONE)
        }
    }

    private companion object {
        val KEY_USER = stringPreferencesKey("profile.tz_known.user")
        val KEY_ZONE = stringPreferencesKey("profile.tz_known.zone")
    }
}
