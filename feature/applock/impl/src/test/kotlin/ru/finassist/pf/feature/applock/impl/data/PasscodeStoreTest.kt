package ru.finassist.pf.feature.applock.impl.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasscodeStoreTest {

    /** JVM stand-in for the Keystore: fixed keys, same primitives (HMAC-SHA256, AES-GCM). */
    private class FakeCrypto : PasscodeCrypto {
        var macKey = ByteArray(32) { 1 }
        val aesKey = SecretKeySpec(ByteArray(32) { 2 }, "AES")
        var resets = 0
        var keyExists = false

        override fun mac(data: ByteArray): ByteArray {
            keyExists = true
            return Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(macKey, "HmacSHA256")); doFinal(data) }
        }

        override fun hasKey() = keyExists

        override fun seal(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv)) }
            return iv + c.doFinal(plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            if (sealed.size < 13) throw GeneralSecurityException("short")
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            }
            return c.doFinal(sealed, 12, sealed.size - 12)
        }

        override fun reset() {
            resets++
            keyExists = false
            macKey = ByteArray(32) { 3 }
        }
    }

    private val dir: File = createTempDirectory("passcode").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }
    private val crypto = FakeCrypto()
    private val store = PasscodeStore(dataStore, crypto)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `saved code matches and nothing readable is stored`() = runBlocking {
        store.save("1234")
        assertTrue(store.isConfigured.first())
        assertTrue(store.matches("1234"))
        assertFalse(store.matches("4321"))
        assertEquals(0, store.wrongAttempts.first())
        // Only the sealed record is on disk: no salt, hash or counter in the clear.
        val keys = dataStore.data.first().asMap().keys.map { it.name }
        assertEquals(listOf("applock.record"), keys)
    }

    @Test
    fun `attempts survive inside the sealed record`() = runBlocking {
        store.save("1234")
        store.setWrongAttempts(3)
        assertEquals(3, store.wrongAttempts.first())
        assertTrue(store.matches("1234"))
    }

    @Test
    fun `tampered record counts as exhausted and never matches`() = runBlocking {
        store.save("1234")
        dataStore.edit { prefs ->
            val raw = Base64.getDecoder().decode(prefs[RECORD]!!)
            raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
            prefs[RECORD] = Base64.getEncoder().encodeToString(raw)
        }
        assertFalse(store.matches("1234"))
        assertTrue(store.wrongAttempts.first() >= 5)
        assertTrue(store.isConfigured.first())
    }

    @Test
    fun `a different MAC key rejects the code`() = runBlocking {
        store.save("1234")
        crypto.macKey = ByteArray(32) { 9 }
        assertFalse(store.matches("1234"))
    }

    @Test
    fun `legacy hash is accepted once and replaced by a sealed record`() = runBlocking {
        val salt = "abcd"
        val hash = MessageDigest.getInstance("SHA-256").digest("$salt:1234".toByteArray()).joinToString("") { "%02x".format(it) }
        dataStore.edit {
            it[stringPreferencesKey("applock.salt")] = salt
            it[stringPreferencesKey("applock.hash")] = hash
        }
        assertTrue(store.isConfigured.first())
        assertFalse(store.matches("0000"))
        assertTrue(store.matches("1234"))
        val prefs = dataStore.data.first()
        assertNull(prefs[stringPreferencesKey("applock.hash")])
        assertTrue(prefs[RECORD] != null)
        assertTrue(store.matches("1234"))
    }

    @Test
    fun `clear forgets the code and rotates the MAC key`() = runBlocking {
        store.save("1234")
        store.setBiometricEnabled(true)
        store.clear()
        assertFalse(store.isConfigured.first())
        assertFalse(store.biometricEnabled.first())
        assertEquals(1, crypto.resets)
        assertFalse(store.matches("1234"))
    }

    @Test
    fun `removed record keeps the lock configured with exhausted attempts`() = runBlocking {
        store.save("1234")
        store.setBiometricEnabled(true)
        dataStore.edit { it.remove(RECORD) }
        assertTrue(store.isConfigured.first())
        assertTrue(store.wrongAttempts.first() >= 5)
        assertFalse(store.biometricEnabled.first())
        assertFalse(store.matches("1234"))
    }

    @Test
    fun `legacy install has no biometric unlock until the code is entered`() = runBlocking {
        val salt = "abcd"
        val hash = MessageDigest.getInstance("SHA-256").digest("$salt:1234".toByteArray()).joinToString("") { "%02x".format(it) }
        dataStore.edit {
            it[stringPreferencesKey("applock.salt")] = salt
            it[stringPreferencesKey("applock.hash")] = hash
            it[androidx.datastore.preferences.core.booleanPreferencesKey("applock.biometric")] = true
        }
        assertFalse(store.biometricEnabled.first())
        assertTrue(store.matches("1234"))
        assertTrue(store.biometricEnabled.first())
    }

    private companion object {
        val RECORD = stringPreferencesKey("applock.record")
    }
}
