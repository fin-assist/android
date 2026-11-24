package ru.finassist.pf.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TooltipOnboardingStoreTest {
    @get:Rule
    val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun onlyOneScreenClaimWinsAndNewLoginDoesNotReplayIt() = runBlocking {
        val store = device("first")
        val claims = TooltipOnboardingStore(store)
        store.owner("user-a", "login-1")
        assertEquals(false, claims.isClaimed("analytics", "user-a", "login-1"))
        val attempts = List(2) { async { claims.claim("analytics", "user-a", "login-1") } }.awaitAll()
        assertEquals(1, attempts.count { it })
        store.owner("user-a", "login-2")
        assertNull(claims.isClaimed("analytics", "user-a", "login-1"))
        assertFalse(TooltipOnboardingStore(store).claim("analytics", "user-a", "login-2"))
        assertEquals(true, TooltipOnboardingStore(store).isClaimed("analytics", "user-a", "login-2"))
        assertEquals(false, claims.isClaimed("operations", "user-a", "login-2"))
        assertTrue(claims.claim("operations", "user-a", "login-2"))
    }

    @Test
    fun differentAccountOrDeviceCanStartAndStaleSessionCannotConsumeClaim() = runBlocking {
        val first = device("first")
        val claims = TooltipOnboardingStore(first)
        first.owner("user-a", "login-1")
        assertFalse(claims.claim("analytics", "user-a", "stale"))
        assertTrue(claims.claim("analytics", "user-a", "login-1"))
        first.owner("user-b", "login-2")
        assertFalse(claims.claim("analytics", "user-a", "login-1"))
        assertTrue(claims.claim("analytics", "user-b", "login-2"))
        val second = device("second")
        second.owner("user-a", "login-1")
        assertTrue(TooltipOnboardingStore(second).claim("analytics", "user-a", "login-1"))
    }

    @Test
    fun failedStorageDoesNotLookLikeAnExistingClaim() = runBlocking {
        val unavailable = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw IOException("read failed") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("write failed")
        }
        val claims = TooltipOnboardingStore(unavailable)
        assertFalse(claims.claim("analytics", "user-a", "login-1"))
        assertFailsWith<IOException> { claims.isClaimed("analytics", "user-a", "login-1") }
        Unit
    }

    private fun device(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "$name.preferences_pb") }

    private suspend fun DataStore<Preferences>.owner(user: String, login: String) {
        edit { AuthSessionIdentityPreferences.write(it, user, login) }
    }
}
