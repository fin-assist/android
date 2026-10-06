package ru.finassist.pf.feature.applock.impl.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Key material behind the passcode record. Both keys are non-exportable Android Keystore keys, so a copy of the
 * app's files is useless off the device: the 4-digit code cannot be brute-forced from the MAC without this
 * phone's secure hardware, and the attempt counter cannot be edited without breaking the AEAD tag.
 *
 * Out of scope: an attacker running code as the app on the unlocked device (root) can use the keys too.
 */
internal interface PasscodeCrypto {
    /** HMAC-SHA256 under the passcode key; the key is created on first use. */
    fun mac(data: ByteArray): ByteArray

    fun seal(plain: ByteArray): ByteArray

    /** @throws GeneralSecurityException when the record was tampered with or its key is gone. */
    fun open(sealed: ByteArray): ByteArray

    /**
     * Marker outside the app's files, set only after the record is committed: a passcode was set and not
     * cleared since. The MAC key cannot serve as one — it appears before the record is written.
     */
    fun isCommitted(): Boolean

    fun markCommitted()

    /** Drops the marker and the MAC key: the next passcode gets a fresh key. */
    fun reset()
}

@Singleton
internal class KeystorePasscodeCrypto @Inject constructor(
    @ApplicationContext private val context: Context,
) : PasscodeCrypto {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    /** Same recovery as the token store: an unreadable keyset is recreated, the old record then fails to open. */
    private val aead: Aead by lazy {
        AeadConfig.register()
        runCatching { buildAead() }.getOrElse {
            context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            buildAead()
        }
    }

    private fun buildAead(): Aead = AndroidKeysetManager.Builder()
        .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
        .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
        .withMasterKeyUri(MASTER_KEY_URI)
        .build()
        .keysetHandle
        .getPrimitive(Aead::class.java)

    @Synchronized
    override fun mac(data: ByteArray): ByteArray {
        val key = (keyStore.getKey(MAC_ALIAS, null) as? SecretKey) ?: createMacKey()
        return Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
            init(key)
            doFinal(data)
        }
    }

    override fun seal(plain: ByteArray): ByteArray = aead.encrypt(plain, AAD)

    override fun open(sealed: ByteArray): ByteArray = aead.decrypt(sealed, AAD)

    @Synchronized
    override fun isCommitted(): Boolean = runCatching { keyStore.containsAlias(MARKER_ALIAS) }.getOrDefault(false)

    /** A tiny AES key whose only meaning is its existence; never used for crypto. */
    @Synchronized
    override fun markCommitted() {
        if (isCommitted()) return
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(KeyGenParameterSpec.Builder(MARKER_ALIAS, KeyProperties.PURPOSE_ENCRYPT).setKeySize(128).build())
            generateKey()
        }
    }

    /** Marker first: a crash in between leaves «not configured», never «configured without a record». */
    @Synchronized
    override fun reset() {
        runCatching { keyStore.deleteEntry(MARKER_ALIAS) }
        runCatching { keyStore.deleteEntry(MAC_ALIAS) }
    }

    /** StrongBox where the phone has it (API 28+), otherwise the TEE-backed keystore. */
    private fun createMacKey(): SecretKey {
        fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(MAC_ALIAS, KeyProperties.PURPOSE_SIGN).apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
            }.build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).run {
                init(spec)
                generateKey()
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return generate(strongBox = false)
        return try {
            generate(strongBox = true)
        } catch (e: ProviderException) {
            // StrongBoxUnavailableException (API 28) is a ProviderException; caught by the base type for API 26-27 verifiers.
            generate(strongBox = false)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MAC_ALIAS = "pf_passcode_mac"
        const val MARKER_ALIAS = "pf_passcode_committed"
        const val KEYSET_NAME = "pf_applock_keyset"
        const val KEYSET_PREFS = "pf_applock_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://pf_applock_master_key"
        val AAD = "pf.applock.record".toByteArray()
    }
}
