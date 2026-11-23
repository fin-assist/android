package ru.finassist.pf.e2e

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.storage.TooltipOnboardingStore
import ru.finassist.pf.mock.MockAnalyticsHelp
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull

class E2eAnalyticsHelpControlTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val owner = TokenStore.Session("user", TokenPair("access", "refresh"), "login")

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `manual needs an active login even when an automatic mode could be queued`() = runBlocking {
        val store = TooltipOnboardingStore(preferences())
        for (session in listOf(null, owner.copy(authSessionEpoch = null))) {
            val receipt = E2eAnalyticsHelpControl(Tokens { session }, store)
            assertEquals("analytics_help needs an active login", receipt.verify(MockAnalyticsHelp.MANUAL, allowSignedOut = true))
        }
    }

    @Test
    fun `manual persists a durable claim and repeated calls succeed`() = runBlocking {
        val store = TooltipOnboardingStore(preferences())
        val receipt = E2eAnalyticsHelpControl(Tokens { owner }, store)
        assertNull(receipt.verify(MockAnalyticsHelp.MANUAL))
        assertEquals(true, store.isClaimed("analytics", owner.userId, "login"))
        assertNull(receipt.verify(MockAnalyticsHelp.MANUAL))
    }

    @Test
    fun `manual with an unsuccessful write cannot acknowledge the receipt`() = runBlocking {
        val preferences = preferences()
        val readOnly = object : DataStore<Preferences> by preferences {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("write failed")
        }
        val receipt = E2eAnalyticsHelpControl(Tokens { owner }, TooltipOnboardingStore(readOnly))
        assertEquals("analytics_help=manual: claim was not saved", receipt.verify(MockAnalyticsHelp.MANUAL))
    }

    @Test
    fun `automatic mode can be queued before sign in and is validated afterwards`() = runBlocking {
        val store = TooltipOnboardingStore(preferences())
        var session: TokenStore.Session? = null
        val receipt = E2eAnalyticsHelpControl(Tokens { session }, store)
        assertNull(receipt.verify(MockAnalyticsHelp.ONBOARDING, allowSignedOut = true))
        assertEquals("analytics_help needs an active login", receipt.verify(MockAnalyticsHelp.ONBOARDING))
        session = owner
        assertNull(receipt.verify(MockAnalyticsHelp.ONBOARDING))
        assertEquals(false, store.isClaimed("analytics", owner.userId, "login"))
    }

    @Test
    fun `automatic modes reject an existing claim`() = runBlocking {
        val store = TooltipOnboardingStore(preferences())
        val receipt = E2eAnalyticsHelpControl(Tokens { owner }, store)
        assertNull(receipt.verify(MockAnalyticsHelp.MANUAL))
        for (mode in listOf(MockAnalyticsHelp.ONBOARDING, MockAnalyticsHelp.REVERSED)) {
            assertEquals("analytics_help: onboarding already claimed; clear app data first", receipt.verify(mode, allowSignedOut = true))
        }
    }

    @Test
    fun `changing the login after the token read rejects the receipt`() = runBlocking {
        val preferences = preferences()
        val tokens = Tokens {
            preferences.edit { it[epochKey] = "new-login" }
            owner
        }
        val store = TooltipOnboardingStore(preferences)
        val receipt = E2eAnalyticsHelpControl(tokens, store)
        assertEquals("analytics_help: login changed", receipt.verify(MockAnalyticsHelp.MANUAL))
        assertEquals(false, store.isClaimed("analytics", owner.userId, "new-login"))
    }

    private suspend fun preferences(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "analytics.preferences_pb") }.also { store ->
            // The fake token reader does not write an encrypted session. Supply its persisted identity for the real claim store.
            store.edit {
                it[stringPreferencesKey("session.identity.user")] = owner.userId
                it[epochKey] = "login"
            }
        }

    private class Tokens(private val read: suspend () -> TokenStore.Session?) : TokenStore {
        override val session = emptyFlow<TokenStore.Session?>()
        override suspend fun current() = read()
        override suspend fun save(session: TokenStore.Session) = error("not used")
        override suspend fun updateTokens(expectedRefreshToken: String, tokens: TokenPair): Boolean = error("not used")
        override suspend fun clear() = error("not used")
    }

    private companion object {
        val epochKey = stringPreferencesKey("session.identity.epoch")
    }
}
