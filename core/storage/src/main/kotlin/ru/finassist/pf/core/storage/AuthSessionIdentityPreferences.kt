package ru.finassist.pf.core.storage

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Non-secret login identity in the same DataStore transaction as the encrypted session. Consumers may compare
 * their captured owner inside an edit; no tokens, mutable keys or write API are exposed outside storage.
 */
object AuthSessionIdentityPreferences {
    private val user = stringPreferencesKey("session.identity.user")
    private val epoch = stringPreferencesKey("session.identity.epoch")

    fun matches(preferences: Preferences, userId: String, authSessionEpoch: String): Boolean =
        userId.isNotBlank() && authSessionEpoch.isNotBlank() && preferences[user] == userId &&
            preferences[epoch] == authSessionEpoch

    fun signedOut(preferences: Preferences): Boolean = preferences[epoch] == null

    internal fun revision(preferences: Preferences): String? = preferences[epoch]

    internal fun epoch(preferences: Preferences, userId: String): String? =
        preferences[epoch]?.takeIf { it.isNotBlank() && preferences[user] == userId }

    internal fun write(preferences: MutablePreferences, userId: String, authSessionEpoch: String) {
        preferences[user] = userId
        preferences[epoch] = authSessionEpoch
    }

    internal fun clear(preferences: MutablePreferences) {
        preferences.remove(user)
        preferences.remove(epoch)
    }
}
