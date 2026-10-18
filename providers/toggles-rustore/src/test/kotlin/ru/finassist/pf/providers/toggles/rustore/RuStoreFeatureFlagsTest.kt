package ru.finassist.pf.providers.toggles.rustore

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.finassist.pf.core.toggles.Experiment
import ru.finassist.pf.core.toggles.FLAGS_VERSION_DEFAULTS
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.toggles.RemoteValue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuStoreFeatureFlagsTest {

    /** A console config: [booleans] and [strings] by key; a key in both answers with its own type only. */
    private class Console(val booleans: Map<String, Boolean> = emptyMap(), val strings: Map<String, String> = emptyMap()) : ConfigSnapshot.Values {
        override fun has(key: String) = key in booleans || key in strings
        override fun boolean(key: String) = booleans[key] ?: throw IllegalArgumentException("$key is not a boolean")
        override fun string(key: String) = strings[key] ?: throw IllegalArgumentException("$key is not a string")
    }

    @Test fun `known keys are read with their type, the minimum version as a string (FIN-22)`() {
        val snapshot = ConfigSnapshot.of(
            Console(
                booleans = mapOf(Flag.ASSISTANT.key to false, "unknown.flag" to true),
                strings = mapOf(RemoteValue.APP_MIN_VERSION.key to "0.3.0", Experiment.ANALYTICS_ORDER.key to "b", "unknown.value" to "x"),
            ),
        )
        assertEquals(mapOf(Flag.ASSISTANT.key to false), snapshot.values)
        assertEquals(mapOf(Experiment.ANALYTICS_ORDER.key to "b"), snapshot.variants)
        assertEquals(mapOf(RemoteValue.APP_MIN_VERSION.key to "0.3.0"), snapshot.strings)
    }

    @Test fun `a value of the wrong type is left out, so the code default stays`() {
        // The minimum as a boolean and a flag as a string: neither can be read with the type the app expects.
        val snapshot = ConfigSnapshot.of(
            Console(booleans = mapOf(RemoteValue.APP_MIN_VERSION.key to true), strings = mapOf(Flag.ASSISTANT.key to "yes")),
        )
        assertTrue(snapshot.values.isEmpty())
        assertTrue(snapshot.strings.isEmpty())
    }

    @Test fun `without an app id the code defaults are the config and it counts as loaded`() = runTest {
        val flags = RuStoreFeatureFlags(RuntimeEnvironment.getApplication(), appId = " ")
        flags.refresh()
        assertTrue(flags.loaded)
        assertEquals(RemoteValue.APP_MIN_VERSION.defaultValue, flags.value(RemoteValue.APP_MIN_VERSION))
        assertEquals(Flag.ASSISTANT.defaultValue, flags.isEnabled(Flag.ASSISTANT))
        assertEquals(Experiment.ANALYTICS_ORDER.defaultVariant, flags.variant(Experiment.ANALYTICS_ORDER))
        assertEquals(FLAGS_VERSION_DEFAULTS, flags.version)
    }
}
