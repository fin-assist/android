package ru.finassist.pf.core.storage

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        // Explicit configuration (Tink 1.12+): the Class-only overload is deprecated. RegistryConfiguration
        // reads the same global registry that AeadConfig.register() fills, so existing keysets keep working.
        .getPrimitive(RegistryConfiguration.get(), Aead::class.java)

    /**
     * Lives as long as the store (a singleton): it keeps the one collection of the DataStore that [session] is
     * refreshed from, so no subscriber of [session] collects the DataStore itself.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The session as last read by [refresh]; null until the first read. DataStore collectors cannot be trusted with
     * it: one that starts while a write is in flight can miss that write for good (FIN-88, as FIN-87 for the app
     * lock), e.g. `PfApp` resubscribing while the sign-in is being saved. So nothing here publishes a collected
     * value: every value is a fresh read, taken after each own write and whenever the store reports a change.
     */
    private val stored = MutableStateFlow<Stored?>(null)

    /** Serializes [refresh]: a read is published only after every read started before it. */
    private val reads = Mutex()

    init {
        // A trigger only (its values may be stale, see [stored]); it also takes the first read. Other keys of the
        // shared DataStore do not concern the session. A failed read (I/O) would end the collection for good, so it
        // starts over after a pause.
        scope.launch {
            dataStore.data.map { it[KEY_BLOB] }.distinctUntilChanged()
                .retryWhen { e, _ -> (e !is CancellationException).also { if (it) delay(READ_RETRY_MS) } }
                .collect { refreshUntilRead() }
        }
    }

    /**
     * Every subscriber gets the latest read at once and every read after it, whenever it subscribes. Keystore + AES
     * work runs in [refresh] on the IO dispatcher, off the main thread `PfApp` collects this flow on.
     */
    override val session: Flow<TokenStore.Session?> = stored.filterNotNull().map { it.session }.distinctUntilChanged()

    override suspend fun current(): TokenStore.Session? = read()

    private suspend fun read(): TokenStore.Session? =
        withContext(Dispatchers.IO) { dataStore.data.first()[KEY_BLOB]?.let(::decode) }

    /**
     * NonCancellable: a write that has landed is published even if its caller has gone away meanwhile. A failed
     * read keeps the last value and does not throw: it runs after every own write, where it would replace the
     * write's own outcome, and in the trigger, which would stop. Returns whether the read landed.
     */
    private suspend fun refresh(): Boolean = withContext(NonCancellable) {
        reads.withLock {
            stored.value = try {
                Stored(read())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "session could not be read, the last one stays", e)
                return@withLock false
            }
            true
        }
    }

    /**
     * Until a read lands, [session] is behind the store (or has nothing for its subscribers yet), and no later
     * change may come to fix it: a failed read is retried, not left waiting for the next write.
     */
    private suspend fun refreshUntilRead() {
        while (!refresh()) delay(READ_RETRY_MS)
    }

    /**
     * Runs an own write of the session and publishes the store's state after it, whether the write landed or not.
     * A failed read is retried in the background: the writer gets its own outcome now.
     */
    private suspend fun <T> writing(write: suspend () -> T): T = try {
        write()
    } finally {
        if (!refresh()) scheduleRetry()
    }

    /** The background retry after a write that failed to read back; one at a time, however many writes fail. */
    private var retry: Job? = null

    /** A write failed to read back while [retry] was running: it reads again once more before it ends. */
    private var retryAgain = false

    /**
     * Starts the retry, or has the running one read once more. Both sides decide under the same lock: a retry that
     * has just read cannot end while a later failed write counts on it.
     */
    private fun scheduleRetry() = synchronized(this) {
        if (retry != null) retryAgain = true else retry = scope.launch { retryUntilCurrent() }
    }

    private suspend fun retryUntilCurrent() {
        while (true) {
            refreshUntilRead()
            synchronized(this) {
                if (!retryAgain) {
                    retry = null
                    return
                }
                retryAgain = false
            }
        }
    }

    /** Encryption failures surface as [AppError.Unknown], which every sign-in screen already handles. */
    override suspend fun save(session: TokenStore.Session) {
        val blob = withContext(Dispatchers.IO) {
            try {
                encode(session)
            } catch (e: GeneralSecurityException) {
                throw AppError.Unknown(e)
            }
        }
        writing { dataStore.edit { it[KEY_BLOB] = blob } }
    }

    override suspend fun updateTokens(expectedRefreshToken: String, tokens: TokenPair): Boolean {
        var updated = false
        // One DataStore transaction: read, check, write — serialized with save() and clear().
        writing {
            dataStore.edit { prefs ->
                val existing = prefs[KEY_BLOB]?.let(::decode) ?: return@edit
                if (existing.tokens.refreshToken != expectedRefreshToken) return@edit
                prefs[KEY_BLOB] = try {
                    encode(existing.copy(tokens = tokens))
                } catch (e: GeneralSecurityException) {
                    throw AppError.Unknown(e)
                }
                updated = true
            }
        }
        return updated
    }

    override suspend fun clear() {
        writing { dataStore.edit { it.remove(KEY_BLOB) } }
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
    }.onFailure { e ->
        // «No session» is the right outcome, but a silent one hides a keystore hiccup behind a sign-in that
        // seems to go nowhere (FIN-63). The exception names the reason (bad MAC, invalidated key, a short blob)
        // and never carries the blob.
        Log.w(TAG, "stored session could not be decoded, treated as signed out", e)
    }.getOrNull()

    /** Wraps a read, so «read: no session» and «not read yet» (null [stored]) stay apart. */
    private class Stored(val session: TokenStore.Session?)

    private companion object {
        const val TAG = "PfSession"
        const val READ_RETRY_MS = 1_000L
        val KEY_BLOB = stringPreferencesKey("session.blob")
        const val KEYSET_NAME = "pf_session_keyset"
        const val KEYSET_PREFS = "pf_session_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://pf_session_master_key"
        val AAD = "pf.session".toByteArray()
    }
}
