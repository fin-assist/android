package ru.finassist.pf.feature.auth.impl.data

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.finassist.pf.core.network.client.SessionTokens
import ru.finassist.pf.core.network.dto.TokenPairDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tokens at rest: AES-GCM via Tink, keyset wrapped by an Android Keystore master key
 * (architecture.md: «refresh — под ключом Android Keystore»). Ciphertext lives in SharedPreferences.
 * Reads are synchronous (OkHttp interceptors need them) and cached in memory after the first load.
 */
@Singleton
class SecureTokenStore @Inject constructor(@ApplicationContext private val context: Context) : SessionTokens {
    private val prefs = context.getSharedPreferences("pf_tokens", Context.MODE_PRIVATE)
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "pf_keyset", "pf_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://pf_master")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    @Volatile private var cached: TokenPairDto? = null
    @Volatile private var loaded = false
    private val lock = Any()

    /** Called when the session is lost (refresh rejected). The auth feature observes it to switch state. */
    @Volatile var onCleared: (() -> Unit)? = null

    private fun load(): TokenPairDto? = synchronized(lock) {
        if (!loaded) {
            cached = runCatching {
                val a = prefs.getString("a", null) ?: return@runCatching null
                val r = prefs.getString("r", null) ?: return@runCatching null
                TokenPairDto(decrypt(a), decrypt(r))
            }.getOrNull()
            loaded = true
        }
        cached
    }

    override fun accessToken(): String? = load()?.accessToken
    override fun refreshToken(): String? = load()?.refreshToken

    override fun update(pair: TokenPairDto) = synchronized(lock) {
        cached = pair; loaded = true
        prefs.edit().putString("a", encrypt(pair.accessToken)).putString("r", encrypt(pair.refreshToken)).apply()
    }

    override fun clear() {
        synchronized(lock) { cached = null; loaded = true; prefs.edit().clear().apply() }
        onCleared?.invoke()
    }

    fun hasTokens(): Boolean = load() != null

    private fun encrypt(s: String): String = Base64.encodeToString(aead.encrypt(s.toByteArray(), AD), Base64.NO_WRAP)
    private fun decrypt(s: String): String = String(aead.decrypt(Base64.decode(s, Base64.NO_WRAP), AD))

    private companion object { val AD = "pf-tokens".toByteArray() }
}
