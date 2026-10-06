package ru.finassist.pf.core.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.TokenPair

internal class InMemoryTokenStore(initial: TokenStore.Session? = null) : TokenStore {
    private val state = MutableStateFlow(initial)
    override val session: Flow<TokenStore.Session?> = state
    override suspend fun current(): TokenStore.Session? = state.value
    override suspend fun save(session: TokenStore.Session) { state.value = session }
    override suspend fun updateTokens(tokens: TokenPair) { state.value = state.value?.copy(tokens = tokens) }
    override suspend fun clear() { state.value = null }
}
