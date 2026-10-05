package ru.finassist.pf.core.toggles

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Fixed values — tests and previews. Missing flags fall back to code defaults. */
class StaticFlags(private val values: Map<Flag, Boolean> = emptyMap()) : FeatureFlags {
    private val changesFlow = MutableSharedFlow<Unit>()

    override fun isEnabled(flag: Flag): Boolean = values[flag] ?: flag.defaultValue
    override val changes: Flow<Unit> = changesFlow
    override suspend fun setUser(userId: String?) = Unit
    override suspend fun refresh() = Unit
}
