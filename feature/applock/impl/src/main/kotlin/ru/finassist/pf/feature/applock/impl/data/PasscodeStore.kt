package ru.finassist.pf.feature.applock.impl.data

import java.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Passcode storage: one AEAD-sealed record `salt, HMAC(salt:code), wrong attempts` in the shared DataStore.
 *
 * - The MAC key never leaves the Android Keystore ([PasscodeCrypto]), so the 10 000 codes cannot be tried
 *   offline against a copied file; the 5-attempt limit is the only way in.
 * - The attempt counter is inside the sealed record: editing or resetting it breaks the tag. A record that
 *   no longer opens counts as exhausted attempts, so the next wrong code signs the user out.
 * - The biometric switch stays plain: turning it on only offers the system prompt, which needs the owner's
 *   enrolled biometrics anyway.
 *
 * Installs from before the sealed record keep a salted SHA-256 hash; it is accepted once and replaced by a
 * sealed record on the first successful unlock.
 */
@Singleton
internal class PasscodeStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val crypto: PasscodeCrypto,
) {
    private class Record(val salt: String, val mac: String, val attempts: Int)

    val isConfigured: Flow<Boolean> = dataStore.data.map { it[KEY_RECORD] != null || it[LEGACY_HASH] != null }
    val biometricEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_BIOMETRIC] ?: false }

    val wrongAttempts: Flow<Int> = dataStore.data.map { prefs ->
        val sealed = prefs[KEY_RECORD] ?: return@map prefs[LEGACY_ATTEMPTS] ?: 0
        open(sealed)?.attempts ?: EXHAUSTED
    }.flowOn(Dispatchers.IO)

    suspend fun save(code: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        val sealed = withContext(Dispatchers.IO) { seal(Record(salt, mac(salt, code), 0)) }
        dataStore.edit {
            it[KEY_RECORD] = sealed
            it.removeLegacy()
        }
    }

    suspend fun matches(code: String): Boolean {
        val prefs = dataStore.data.first()
        val sealed = prefs[KEY_RECORD]
        if (sealed == null) return matchesLegacy(prefs, code)
        return withContext(Dispatchers.IO) {
            val record = open(sealed) ?: return@withContext false
            MessageDigest.isEqual(record.mac.toByteArray(), mac(record.salt, code).toByteArray())
        }
    }

    suspend fun setWrongAttempts(value: Int) {
        dataStore.edit { prefs ->
            val sealed = prefs[KEY_RECORD]
            if (sealed == null) {
                if (prefs[LEGACY_HASH] != null) prefs[LEGACY_ATTEMPTS] = value
                return@edit
            }
            // An unopenable record stays as it is: it already counts as exhausted.
            val record = open(sealed) ?: return@edit
            prefs[KEY_RECORD] = seal(Record(record.salt, record.mac, value))
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_BIOMETRIC] = enabled }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(KEY_RECORD)
            it.remove(KEY_BIOMETRIC)
            it.removeLegacy()
        }
        withContext(Dispatchers.IO) { crypto.reset() }
    }

    private suspend fun matchesLegacy(prefs: Preferences, code: String): Boolean {
        val salt = prefs[LEGACY_SALT] ?: return false
        val stored = prefs[LEGACY_HASH] ?: return false
        val hash = MessageDigest.getInstance("SHA-256").digest("$salt:$code".toByteArray()).toHex()
        if (!MessageDigest.isEqual(stored.toByteArray(), hash.toByteArray())) return false
        save(code)
        return true
    }

    private fun mac(salt: String, code: String): String = crypto.mac("$salt:$code".toByteArray()).toHex()

    private fun seal(record: Record): String {
        val plain = listOf(VERSION, record.salt, record.mac, record.attempts.toString()).joinToString("\n")
        return Base64.getEncoder().encodeToString(crypto.seal(plain.toByteArray()))
    }

    private fun open(sealed: String): Record? = try {
        val parts = crypto.open(Base64.getDecoder().decode(sealed)).toString(Charsets.UTF_8).split("\n")
        if (parts.size != 4 || parts[0] != VERSION) null else Record(parts[1], parts[2], parts[3].toInt())
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        // Not Base64 or not a number: tampered as well.
        null
    }

    private fun MutablePreferences.removeLegacy() {
        remove(LEGACY_SALT)
        remove(LEGACY_HASH)
        remove(LEGACY_ATTEMPTS)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val VERSION = "v1"
        const val EXHAUSTED = Int.MAX_VALUE / 2
        val KEY_RECORD = stringPreferencesKey("applock.record")
        val KEY_BIOMETRIC = booleanPreferencesKey("applock.biometric")
        val LEGACY_SALT = stringPreferencesKey("applock.salt")
        val LEGACY_HASH = stringPreferencesKey("applock.hash")
        val LEGACY_ATTEMPTS = intPreferencesKey("applock.wrong_attempts")
    }
}
