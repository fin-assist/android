package ru.finassist.pf.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-level preferences that are needed before sign-in or by several features: the theme (the server copy in
 * the profile is synced after sign-in, this one drives rendering) and a stable device id used to key feature
 * flags before a user id exists.
 */
@Singleton
class AppPreferences @Inject constructor(private val dataStore: DataStore<Preferences>) {

    /** `system` / `light` / `dark`, as `Theme` in the API. */
    val theme: Flow<String?> = dataStore.data.map { it[KEY_THEME] }

    suspend fun setTheme(theme: String) {
        dataStore.edit { it[KEY_THEME] = theme }
    }

    /** Random UUID generated on first access; survives sign-out, not reinstall. */
    suspend fun deviceId(): String {
        val existing = dataStore.data.first()[KEY_DEVICE_ID]
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
        dataStore.edit { prefs -> if (prefs[KEY_DEVICE_ID] == null) prefs[KEY_DEVICE_ID] = fresh }
        return dataStore.data.first()[KEY_DEVICE_ID] ?: fresh
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("app.theme")
        val KEY_DEVICE_ID = stringPreferencesKey("app.device_id")
    }
}
