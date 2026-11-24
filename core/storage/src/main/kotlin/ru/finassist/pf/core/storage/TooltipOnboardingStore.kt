package ru.finassist.pf.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject

/** Device-local one-time claim per account and screen, independent of the login epoch. */
class TooltipOnboardingStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    suspend fun claim(screen: String, userId: String, authSessionEpoch: String): Boolean {
        val key = claimKey(screen, userId)
        var claimed = false
        return try {
            dataStore.edit { prefs ->
                if (AuthSessionIdentityPreferences.matches(prefs, userId, authSessionEpoch) && prefs[key] != true) {
                    prefs[key] = true
                    claimed = true
                }
            }
            claimed
        } catch (_: IOException) {
            false
        }
    }

    /** Returns the persisted claim for the captured login, or null if that login is no longer active. */
    suspend fun isClaimed(screen: String, userId: String, authSessionEpoch: String): Boolean? {
        val preferences = dataStore.data.first()
        if (!AuthSessionIdentityPreferences.matches(preferences, userId, authSessionEpoch)) return null
        return preferences[claimKey(screen, userId)] == true
    }

    private fun claimKey(screen: String, userId: String): Preferences.Key<Boolean> {
        val digest = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray())
        val account = digest.joinToString("") { "%02x".format(it) }
        return booleanPreferencesKey("tooltip_onboarding.$screen.$account")
    }
}
