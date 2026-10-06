package ru.finassist.pf.core.toggles

import kotlinx.coroutines.flow.Flow

/**
 * Feature flags as the app reads them. The provider (RuStore Remote Config in `prod`, a JSON asset plus
 * debug overrides in `mock`) is one implementation behind this interface, so switching to GrowthBook later
 * touches only `:providers:*`. Values are read at the screen boundary (view model init) and not re-read
 * mid-screen; [changes] lets the root re-evaluate the screen when a new config arrives.
 */
interface FeatureFlags {
    /** Current value; the code default from [Flag.defaultValue] when the provider has nothing. */
    fun isEnabled(flag: Flag): Boolean

    /** Emits after every config update (fetch, user change, debug override). */
    val changes: Flow<Unit>

    /**
     * Identity for targeting: our own `user_id` after sign-in, `null` before sign-in and after sign-out
     * (then the provider keys by device id). Triggers a re-fetch in providers that support it.
     */
    suspend fun setUser(userId: String?)

    /** Fetches the newest config; errors are swallowed — defaults or the cached config stay in effect. */
    suspend fun refresh()
}

/** Debug-only overrides (mock flavor, hidden settings screen). */
interface FlagOverrides {
    val overrides: Flow<Map<Flag, Boolean>>
    suspend fun set(flag: Flag, enabled: Boolean?)
    suspend fun clear()
}
