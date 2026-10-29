package ru.finassist.pf.mock

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import ru.finassist.pf.core.api.RequestOwner
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.mock.api.MockProfileApi
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MockProfileOwnerTest {
    private val initial = TokenStore.Session("u1", TokenPair("a", "r"), "login-1")
    private val owner = RequestOwner("u1", "login-1")
    private class Tokens(initial: TokenStore.Session) : TokenStore {
        override val session = MutableStateFlow<TokenStore.Session?>(initial)
        override suspend fun current() = session.value
        override suspend fun save(session: TokenStore.Session) { this.session.value = session }
        override suspend fun updateTokens(expectedRefreshToken: String, tokens: TokenPair) = false
        override suspend fun clear() { session.value = null }
    }

    @Test fun `captured profile requests use only their original login`() = runBlocking<Unit> {
        val backend = testBackend()
        val tokens = Tokens(initial)
        val api = MockProfileApi(backend, tokens)
        withContext(owner) {
            assertEquals("u1", api.getProfile().userId)
            assertEquals("Asia/Novosibirsk", api.updateProfile(ProfileUpdate(timezone = "Asia/Novosibirsk")).timezone)
        }
        tokens.save(initial.copy(authSessionEpoch = "login-2"))
        withContext(owner) {
            assertFailsWith<AppError.Unauthorized> { api.getProfile() }
            assertFailsWith<AppError.Unauthorized> { api.updateProfile(ProfileUpdate(timezone = "Asia/Yekaterinburg")) }
        }
        assertEquals("Asia/Novosibirsk", backend.profile.timezone)
    }

    @Test fun `owner is checked after waiting for mutation lock`() = runBlocking<Unit> {
        val backend = testBackend()
        val tokens = Tokens(initial)
        val api = MockProfileApi(backend, tokens)
        backend.mutex.lock()
        val request = async(owner, start = CoroutineStart.UNDISPATCHED) {
            assertFailsWith<AppError.Unauthorized> { api.updateProfile(ProfileUpdate(timezone = "Asia/Novosibirsk")) }
        }
        tokens.save(initial.copy(authSessionEpoch = "login-2"))
        backend.mutex.unlock()
        request.await()
        assertEquals("Europe/Moscow", backend.profile.timezone)
    }

    @Test fun `unsupported identity never authorizes a captured call`() = runBlocking<Unit> {
        val api = MockProfileApi(testBackend())
        withContext(owner) { assertFailsWith<AppError.Unauthorized> { api.getProfile() } }
    }
}
