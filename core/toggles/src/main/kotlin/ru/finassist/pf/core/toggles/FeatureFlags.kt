package ru.finassist.pf.core.toggles

import kotlinx.coroutines.flow.Flow

/**
 * A boolean feature toggle. Defaults live in code (see [Flags]); the provider only overrides them.
 * Values are read at screen boundaries, not on every recomposition: a flag flip must not re-layout a visible screen.
 */
class Flag(val key: String, val default: Boolean, val description: String)

interface FeatureFlags {
    /** Current value: provider value if known, otherwise [Flag.default]. Never suspends, never throws. */
    fun isEnabled(flag: Flag): Boolean
    /** Emits the current value and then every change (after a refresh). */
    fun observe(flag: Flag): Flow<Boolean>
    /** Re-fetch from the provider. Called after login (user id becomes known) and on app start. */
    suspend fun refresh()
    /** Identity passed to the provider: our own user id after login, device id before. */
    suspend fun setUser(userId: String?)
}
