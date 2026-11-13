package ru.finassist.pf.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject

/** Device-local one-time claim per account and screen, independent of the login epoch. */
class TooltipOnboardingStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    suspend fun claim(screen: String, userId: String, authSessionEpoch: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray())
        val account = digest.joinToString("") { "%02x".format(it) }
        val key = booleanPreferencesKey("tooltip_onboarding.$screen.$account")
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
}
