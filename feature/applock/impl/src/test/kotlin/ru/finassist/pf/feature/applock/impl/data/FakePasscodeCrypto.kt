package ru.finassist.pf.feature.applock.impl.data

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** JVM stand-in for the Keystore: fixed keys, same primitives (HMAC-SHA256, AES-GCM). */
internal class FakePasscodeCrypto : PasscodeCrypto {
    /** Makes [seal] throw, as a Keystore failure while the record is written would. */
    @Volatile var failSeal = false
    /** Makes [isCommitted] throw, as a Keystore that cannot be read would. */
    @Volatile var failCommitCheck = false
    var macKey = ByteArray(32) { 1 }
    val aesKey = SecretKeySpec(ByteArray(32) { 2 }, "AES")
    var resets = 0
    var committed = false

    override fun mac(data: ByteArray): ByteArray {
        return Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(macKey, "HmacSHA256")); doFinal(data) }
    }

    override fun isCommitted(): Boolean {
        if (failCommitCheck) throw GeneralSecurityException("keystore unavailable")
        return committed
    }

    override fun markCommitted() {
        committed = true
    }

    override fun seal(plain: ByteArray): ByteArray {
        if (failSeal) throw GeneralSecurityException("keystore unavailable")
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
        committed = false
        macKey = ByteArray(32) { 3 }
    }
}
