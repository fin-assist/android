package ru.finassist.pf.core.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.IdempotencyKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class InMemoryTokenStore(initial: TokenStore.Session? = null) : TokenStore {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()
    private var pending: Pair<String, String>? = null
    override val session: Flow<TokenStore.Session?> = state
    override suspend fun current(): TokenStore.Session? = state.value
    override suspend fun save(session: TokenStore.Session) { mutex.withLock { state.value = session; pending = null } }
    override suspend fun updateTokens(expectedRefreshToken: String, tokens: TokenPair): Boolean = updateCaptured(expectedRefreshToken, tokens, null)
    override suspend fun updateTokens(expectedRefreshToken: String, tokens: TokenPair, authSessionEpoch: String): Boolean = updateCaptured(expectedRefreshToken, tokens, authSessionEpoch)
    private suspend fun updateCaptured(expectedRefreshToken: String, tokens: TokenPair, epoch: String?): Boolean = mutex.withLock {
        val s = state.value ?: return@withLock false
        if (s.tokens.refreshToken != expectedRefreshToken || (epoch != null && s.authSessionEpoch != epoch)) return@withLock false
        state.value = s.copy(tokens = tokens)
        pending = null
        true
    }
    override suspend fun reserveRefreshAttempt(expectedRefreshToken: String, key: IdempotencyKey): Boolean = reserveCaptured(expectedRefreshToken, key, null)
    override suspend fun reserveRefreshAttempt(expectedRefreshToken: String, key: IdempotencyKey, authSessionEpoch: String): Boolean = reserveCaptured(expectedRefreshToken, key, authSessionEpoch)
    private suspend fun reserveCaptured(expectedRefreshToken: String, key: IdempotencyKey, epoch: String?): Boolean = mutex.withLock {
        val s = state.value ?: return@withLock false
        if (s.tokens.refreshToken != expectedRefreshToken || (epoch != null && s.authSessionEpoch != epoch)) return@withLock false
        val wanted = expectedRefreshToken to key.value
        if (pending != null && pending != wanted) return@withLock false
        pending = wanted
        true
    }
    override suspend fun retireRefreshAttempt(expectedRefreshToken: String, key: IdempotencyKey): Boolean = retireCaptured(expectedRefreshToken, key, null)
    override suspend fun retireRefreshAttempt(expectedRefreshToken: String, key: IdempotencyKey, authSessionEpoch: String): Boolean = retireCaptured(expectedRefreshToken, key, authSessionEpoch)
    private suspend fun retireCaptured(expectedRefreshToken: String, key: IdempotencyKey, epoch: String?): Boolean = mutex.withLock {
        val s = state.value ?: return@withLock false
        if (s.tokens.refreshToken != expectedRefreshToken || (epoch != null && s.authSessionEpoch != epoch) || pending != (expectedRefreshToken to key.value)) return@withLock false
        pending = null
        true
    }
    override suspend fun clearIfRefreshToken(expectedRefreshToken: String): Boolean = clearCaptured(expectedRefreshToken, null)
    override suspend fun clearIfRefreshToken(expectedRefreshToken: String, authSessionEpoch: String): Boolean = clearCaptured(expectedRefreshToken, authSessionEpoch)
    private suspend fun clearCaptured(expectedRefreshToken: String, epoch: String?): Boolean = mutex.withLock {
        val s = state.value ?: return@withLock false
        if (s.tokens.refreshToken != expectedRefreshToken || (epoch != null && s.authSessionEpoch != epoch)) return@withLock false
        state.value = null
        pending = null
        true
    }
    override suspend fun clear() { mutex.withLock { state.value = null; pending = null } }
}
