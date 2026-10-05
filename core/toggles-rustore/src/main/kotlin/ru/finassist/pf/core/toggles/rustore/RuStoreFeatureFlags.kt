package ru.finassist.pf.core.toggles.rustore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RuStore Remote Config provider. Stage 8 replaces [fetch] with the SDK call; the contract stays:
 * values are cached in [values], defaults apply until the first successful fetch, `user_id` is set after login.
 */
@Singleton
class RuStoreFeatureFlags @Inject constructor() : FeatureFlags {
    private val values = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private var userId: String? = null

    override fun isEnabled(flag: Flag): Boolean = values.value[flag.key] ?: flag.default
    override fun observe(flag: Flag): Flow<Boolean> = values.map { it[flag.key] ?: flag.default }.distinctUntilChanged()
    override suspend fun refresh() { values.value = fetch(userId) }
    override suspend fun setUser(userId: String?) { this.userId = userId; refresh() }

    /** TODO(stage 8): RuStore Remote Config SDK — account parameter = our user id, device id before login. */
    private suspend fun fetch(@Suppress("UNUSED_PARAMETER") userId: String?): Map<String, Boolean> = values.value
}
