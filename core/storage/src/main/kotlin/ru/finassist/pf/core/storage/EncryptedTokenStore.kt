package ru.finassist.pf.core.storage

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.common.error.AppError
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tokens in DataStore, encrypted with Tink AES-GCM under a master key in the Android Keystore
 * (`androidx.security:security-crypto` is deprecated; architecture.md names Tink or Keystore directly).
 * The ciphertext holds `userId\naccess\nrefresh`; a decryption failure (keystore wiped, restored backup)
 * is treated as "no session" so the user simply signs in again.
 */
@Singleton
class EncryptedTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) : TokenStore {

    /**
     * A keyset that can no longer be opened (Keystore key lost, data restored on another device) is dropped and
     * recreated: the stored session becomes undecryptable, i.e. «signed out», instead of crashing every start.
     */
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

    // Keystore + AES work stays off the main thread (PfApp collects this flow from composition).
    override val session: Flow<TokenStore.Session?> =
        dataStore.data.map { prefs -> prefs[KEY_BLOB]?.let(::decode) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun current(): TokenStore.Session? = session.first()

    /** Encryption failures surface as [AppError.Unknown], which every sign-in screen already handles. */
    override suspend fun save(session: TokenStore.Session) {
        val blob = withContext(Dispatchers.IO) {
            try {
                encode(session)
            } catch (e: GeneralSecurityException) {
                throw AppError.Unknown(e)
            }
        }
        dataStore.edit { it[KEY_BLOB] = blob }
    }

    override suspend fun updateTokens(tokens: TokenPair) {
        val existing = current() ?: return
        save(existing.copy(tokens = tokens))
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY_BLOB) }
    }

    private fun encode(session: TokenStore.Session): String {
        val plain = listOf(session.userId, session.tokens.accessToken, session.tokens.refreshToken)
            .joinToString("\n").toByteArray(Charsets.UTF_8)
        return Base64.encodeToString(aead.encrypt(plain, AAD), Base64.NO_WRAP)
    }

    private fun decode(blob: String): TokenStore.Session? = runCatching {
        val plain = aead.decrypt(Base64.decode(blob, Base64.NO_WRAP), AAD).toString(Charsets.UTF_8)
        val (userId, access, refresh) = plain.split("\n", limit = 3)
        TokenStore.Session(userId, TokenPair(accessToken = access, refreshToken = refresh))
    }.getOrNull()

    private companion object {
        val KEY_BLOB = stringPreferencesKey("session.blob")
        const val KEYSET_NAME = "pf_session_keyset"
        const val KEYSET_PREFS = "pf_session_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://pf_session_master_key"
        val AAD = "pf.session".toByteArray()
    }
}
