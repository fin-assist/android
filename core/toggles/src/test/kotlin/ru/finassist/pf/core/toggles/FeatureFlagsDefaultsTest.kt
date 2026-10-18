package ru.finassist.pf.core.toggles

import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a provider that overrides nothing but the switches gives for the rest of [FeatureFlags]. */
class FeatureFlagsDefaultsTest {
    private val flags = object : FeatureFlags {
        override fun isEnabled(flag: Flag) = flag.defaultValue
        override val changes = emptyFlow<Unit>()
        override val version = FLAGS_VERSION_DEFAULTS
        override suspend fun setUser(userId: String?) = Unit
        override suspend fun refresh() = Unit
    }

    @Test fun `a string value is its code default, the minimum version is none (FIN-22)`() {
        RemoteValue.entries.forEach { assertEquals(it.defaultValue, flags.value(it)) }
        assertEquals("", flags.value(RemoteValue.APP_MIN_VERSION))
    }

    @Test fun `a provider without its own loading counts as loaded`() {
        assertTrue(flags.loaded)
    }
}
