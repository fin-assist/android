package ru.finassist.pf.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.TokenPair
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the real store on Robolectric. Robolectric has no AndroidKeyStore, so Tink's AndroidKeysetManager falls back
 * to an unwrapped keyset in SharedPreferences: the AES-GCM layer, the blob format and the session logic are what
 * these tests check; the Keystore wrapping of the keyset is not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EncryptedTokenStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: EncryptedTokenStore

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "test.preferences_pb") }
        store = EncryptedTokenStore(context, dataStore)
    }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun rawBlob(): String? = dataStore.data.first()[BLOB]

    private fun keysetPrefs() = context.getSharedPreferences(KEYSET_PREFS, Context.MODE_PRIVATE)

    @Test
    fun `no session until one is saved`() = runBlocking {
        assertNull(store.current())
    }

    @Test
    fun `saved session reads back and is not stored in the clear`() = runBlocking {
        store.save(SESSION)

        assertEquals(SESSION, store.current())
        val blob = assertNotNull(rawBlob())
        for (secret in listOf("user-1", "access-1", "refresh-1")) assertFalse(secret in blob, "blob leaks $secret")
    }

    @Test
    fun `same session encrypts to a different blob every time`() = runBlocking {
        store.save(SESSION)
        val first = rawBlob()
        store.save(SESSION)

        assertNotEquals(first, rawBlob())
        assertEquals(SESSION, store.current())
    }

    @Test
    fun `a new store instance over the same data reads the session`() = runBlocking {
        store.save(SESSION)

        assertEquals(SESSION, EncryptedTokenStore(context, dataStore).current())
    }

    @Test
    fun `update with the current refresh token rotates the pair and keeps the user`() = runBlocking {
        store.save(SESSION)

        assertTrue(store.updateTokens("refresh-1", TokenPair("access-2", "refresh-2")))

        assertEquals(TokenStore.Session("user-1", TokenPair("access-2", "refresh-2")), store.current())
    }

    @Test
    fun `update for a session that changed meanwhile is refused`() = runBlocking {
        store.save(SESSION)
        val newSignIn = TokenStore.Session("user-2", TokenPair("access-9", "refresh-9"))
        store.save(newSignIn)

        assertFalse(store.updateTokens("refresh-1", TokenPair("access-2", "refresh-2")))
        assertEquals(newSignIn, store.current())
    }

    @Test
    fun `update after sign-out does not resurrect the session`() = runBlocking {
        store.save(SESSION)
        store.clear()

        assertFalse(store.updateTokens("refresh-1", TokenPair("access-2", "refresh-2")))
        assertNull(store.current())
        assertNull(rawBlob())
    }

    @Test
    fun `session flow follows sign-in, rotation and sign-out without repeats`() = runBlocking {
        store.session.test {
            assertNull(awaitItem())
            store.save(SESSION)
            assertEquals(SESSION, awaitItem())
            // Same content, different ciphertext: not a new emission.
            store.save(SESSION)
            store.updateTokens("refresh-1", TokenPair("access-2", "refresh-2"))
            assertEquals("access-2", awaitItem()?.tokens?.accessToken)
            store.clear()
            assertNull(awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `undecryptable blob is treated as signed out`() = runBlocking {
        dataStore.edit { it[BLOB] = "bm90IGEgY2lwaGVydGV4dA==" }

        assertNull(store.current())
        assertFalse(store.updateTokens("refresh-1", TokenPair("a", "r")))
    }

    @Test
    fun `session encrypted under a lost keyset is signed out and a new sign-in works`() = runBlocking {
        store.save(SESSION)
        // Keystore key lost / app data restored on another device: the keyset is gone, the blob stays.
        keysetPrefs().edit().clear().commit()
        val restored = EncryptedTokenStore(context, dataStore)

        assertNull(restored.current())
        val next = TokenStore.Session("user-1", TokenPair("access-3", "refresh-3"))
        restored.save(next)
        assertEquals(next, restored.current())
    }

    @Test
    fun `unreadable keyset is dropped and recreated instead of crashing`() = runBlocking {
        keysetPrefs().edit().putString(KEYSET_NAME, "not a keyset").commit()
        val fresh = EncryptedTokenStore(context, dataStore)

        fresh.save(SESSION)

        assertEquals(SESSION, fresh.current())
        assertNotEquals("not a keyset", keysetPrefs().getString(KEYSET_NAME, null))
    }

    @Test
    fun `a failed read after a write neither fails the write nor stops the session updates`() = runBlocking<Unit> {
        val flaky = object : DataStore<Preferences> {
            @Volatile var failReads = false
            override val data: Flow<Preferences> = flow {
                if (failReads) throw IOException("disk read failed")
                emitAll(dataStore.data)
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = dataStore.updateData(transform)
        }
        val store = EncryptedTokenStore(context, flaky)
        store.save(SESSION)
        withTimeout(5_000) { store.session.first { it == SESSION } }

        flaky.failReads = true
        store.clear() // the write lands; the read after it fails and must not surface here
        assertNull(rawBlob())
        assertEquals(SESSION, withTimeout(5_000) { store.session.first() }, "the last read stays")

        flaky.failReads = false
        val next = TokenStore.Session("user-2", TokenPair("access-2", "refresh-2"))
        store.save(next)
        withTimeout(5_000) { store.session.first { it == next } }
    }

    @Test
    fun `a failed first read is retried, not left waiting for the next write`() = runBlocking<Unit> {
        store.save(SESSION)
        // The store's own collection (the first) gets the data; the read it starts (the second) fails.
        val collections = java.util.concurrent.atomic.AtomicInteger()
        val flaky = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                if (collections.incrementAndGet() == 2) throw IOException("disk read failed")
                emitAll(dataStore.data)
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = dataStore.updateData(transform)
        }
        val fresh = EncryptedTokenStore(context, flaky)

        assertEquals(SESSION, withTimeout(5_000) { fresh.session.first() })
    }

    private companion object {
        val SESSION = TokenStore.Session("user-1", TokenPair("access-1", "refresh-1"))
        val BLOB = stringPreferencesKey("session.blob")
        const val KEYSET_NAME = "pf_session_keyset"
        const val KEYSET_PREFS = "pf_session_keyset_prefs"
    }
}
