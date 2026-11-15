package ru.finassist.pf.providers.toggles.varioqub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VarioqubFeatureFlagsTest {

    @Test fun `known keys are decoded from strings (FIN-22)`() {
        val snapshot = ConfigSnapshot.of(
            mapOf(
                Flag.ASSISTANT.key to "false",
                RemoteValue.APP_MIN_VERSION.key to "0.3.0",
                Experiment.ANALYTICS_ORDER.key to "b",
                "unknown.flag" to "true",
                "unknown.value" to "x",
            )::get,
        )
        assertEquals(mapOf(Flag.ASSISTANT.key to false), snapshot.values)
        assertEquals(mapOf(Experiment.ANALYTICS_ORDER.key to "b"), snapshot.variants)
        assertEquals(mapOf(RemoteValue.APP_MIN_VERSION.key to "0.3.0"), snapshot.strings)
    }

    @Test fun `an invalid boolean and absent keys preserve defaults`() {
        val snapshot = ConfigSnapshot.of(mapOf(Flag.ASSISTANT.key to "yes")::get)
        assertTrue(snapshot.values.isEmpty())
        assertTrue(snapshot.strings.isEmpty())
    }

    @Test fun `without an app id the code defaults are the config and it counts as loaded`() = runTest {
        val flags = VarioqubFeatureFlags(RuntimeEnvironment.getApplication(), appId = " ", analyticsActive = false)
        flags.refresh()
        assertTrue(flags.loaded)
        assertEquals(RemoteValue.APP_MIN_VERSION.defaultValue, flags.value(RemoteValue.APP_MIN_VERSION))
        assertEquals(Flag.ASSISTANT.defaultValue, flags.isEnabled(Flag.ASSISTANT))
        assertEquals(Experiment.ANALYTICS_ORDER.defaultVariant, flags.variant(Experiment.ANALYTICS_ORDER))
        assertEquals(FLAGS_VERSION_DEFAULTS, flags.version)
    }

    @Test fun `an offline first load stays unconfirmed for the persisted minimum check`() = runTest {
        val flags = VarioqubFeatureFlags(TestClient())

        flags.refresh()

        assertFalse(flags.loaded)
        assertEquals(Flag.ASSISTANT.defaultValue, flags.isEnabled(Flag.ASSISTANT))
    }

    @Test fun `a failed fetch keeps the cached flags and an empty successful config removes them`() = runTest {
        val client = TestClient(
            mapOf(Flag.ASSISTANT.key to "false", RemoteValue.APP_MIN_VERSION.key to "0.3.0"),
        )
        val flags = VarioqubFeatureFlags(client)

        flags.refresh()

        assertTrue(flags.loaded)
        assertFalse(flags.isEnabled(Flag.ASSISTANT))
        assertEquals("0.3.0", flags.value(RemoteValue.APP_MIN_VERSION))

        client.response = emptyMap()
        flags.refresh()

        assertTrue(flags.loaded)
        assertTrue(flags.isEnabled(Flag.ASSISTANT))
        assertEquals(RemoteValue.APP_MIN_VERSION.defaultValue, flags.value(RemoteValue.APP_MIN_VERSION))
    }

    @Test fun `a successful fetch activates the new variant before returning`() = runTest {
        val client = TestClient().apply { response = mapOf(Experiment.ANALYTICS_ORDER.key to "b") }
        val flags = VarioqubFeatureFlags(client)

        flags.refresh()

        assertTrue(flags.loaded)
        assertEquals("b", flags.variant(Experiment.ANALYTICS_ORDER))
    }

    @Test fun `moving to the background while fetching still applies a successful empty config`() = runTest {
        val client = TestClient(mapOf(RemoteValue.APP_MIN_VERSION.key to "0.3.0"))
        val loading = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        client.beforeFetch = {
            loading.complete(Unit)
            complete.await()
        }
        client.response = emptyMap()
        val flags = VarioqubFeatureFlags(client)

        val foreground = launch { flags.refreshInForeground() }
        loading.await()
        assertEquals("0.3.0", flags.value(RemoteValue.APP_MIN_VERSION))
        foreground.cancel()

        complete.complete(Unit)
        foreground.join()

        assertTrue(flags.loaded)
        assertEquals(RemoteValue.APP_MIN_VERSION.defaultValue, flags.value(RemoteValue.APP_MIN_VERSION))
    }

    private class TestClient(private var persisted: Map<String, String> = emptyMap()) : ConfigClient {
        var response: Map<String, String>? = null
        var beforeFetch: suspend () -> Unit = {}
        private var active: Map<String, String> = emptyMap()

        override suspend fun activate(): Set<String> {
            active = persisted
            return active.keys
        }

        override suspend fun fetch(): Boolean {
            beforeFetch()
            persisted = response ?: return false
            return true
        }

        override fun read(key: String): String? = active[key]
    }
}
