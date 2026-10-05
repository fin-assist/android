package ru.finassist.pf.feature.applock.impl.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.lockStore: DataStore<Preferences> by preferencesDataStore("app_lock")

/** Passcode hash (PBKDF2-SHA256, random salt), biometric switch and the wrong-attempt counter. */
@Singleton
class PasscodeStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val hashKey = stringPreferencesKey("hash")
    private val saltKey = stringPreferencesKey("salt")
    private val biometricKey = booleanPreferencesKey("biometric")
    private val attemptsKey = intPreferencesKey("attempts")

    val hasPasscode: Flow<Boolean> = context.lockStore.data.map { it[hashKey] != null }
    val biometricEnabled: Flow<Boolean> = context.lockStore.data.map { it[biometricKey] ?: false }
    val attempts: Flow<Int> = context.lockStore.data.map { it[attemptsKey] ?: 0 }

    suspend fun setPasscode(code: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hash(code, salt)
        context.lockStore.edit {
            it[saltKey] = Base64.encodeToString(salt, Base64.NO_WRAP)
            it[hashKey] = Base64.encodeToString(hash, Base64.NO_WRAP)
            it[attemptsKey] = 0
        }
    }

    /** Returns true on match and resets attempts; otherwise increments and returns false. */
    suspend fun verify(code: String): Boolean {
        val prefs = context.lockStore.data.first()
        val salt = prefs[saltKey]?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val expected = prefs[hashKey]?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val ok = MessageDigest.isEqual(hash(code, salt), expected)
        context.lockStore.edit { it[attemptsKey] = if (ok) 0 else (it[attemptsKey] ?: 0) + 1 }
        return ok
    }

    suspend fun setBiometric(enabled: Boolean) = context.lockStore.edit { it[biometricKey] = enabled }
    suspend fun clear() = context.lockStore.edit { it.clear() }

    private fun hash(code: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(code.toCharArray(), salt, 50_000, 256)).encoded
}
