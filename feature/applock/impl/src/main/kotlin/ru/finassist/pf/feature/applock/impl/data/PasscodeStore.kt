package ru.finassist.pf.feature.applock.impl.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Passcode storage: a salted SHA-256 hash in the shared preferences DataStore (the file lives in the app's
 * private storage; the hash alone is useless without the salt, and the 4-digit space is small enough that the
 * real protection is the 5-attempt limit, not the hash).
 */
@Singleton
internal class PasscodeStore @Inject constructor(private val dataStore: DataStore<Preferences>) {

    val isConfigured: Flow<Boolean> = dataStore.data.map { it[KEY_HASH] != null }
    val biometricEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_BIOMETRIC] ?: false }
    val wrongAttempts: Flow<Int> = dataStore.data.map { it[KEY_ATTEMPTS] ?: 0 }

    suspend fun save(code: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        dataStore.edit {
            it[KEY_SALT] = salt
            it[KEY_HASH] = hash(salt, code)
            it[KEY_ATTEMPTS] = 0
        }
    }

    suspend fun matches(code: String): Boolean {
        val prefs = dataStore.data.first()
        val salt = prefs[KEY_SALT] ?: return false
        val stored = prefs[KEY_HASH] ?: return false
        return MessageDigest.isEqual(stored.toByteArray(), hash(salt, code).toByteArray())
    }

    suspend fun setWrongAttempts(value: Int) {
        dataStore.edit { it[KEY_ATTEMPTS] = value }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_BIOMETRIC] = enabled }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(KEY_SALT)
            it.remove(KEY_HASH)
            it.remove(KEY_ATTEMPTS)
            it.remove(KEY_BIOMETRIC)
        }
    }

    private fun hash(salt: String, code: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$code".toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        val KEY_SALT = stringPreferencesKey("applock.salt")
        val KEY_HASH = stringPreferencesKey("applock.hash")
        val KEY_ATTEMPTS = intPreferencesKey("applock.wrong_attempts")
        val KEY_BIOMETRIC = booleanPreferencesKey("applock.biometric")
    }
}
