package ru.finassist.pf.core.toggles

import kotlinx.coroutines.flow.MutableSharedFlow
import ru.finassist.pf.core.common.availability.ClientVersion
import ru.finassist.pf.core.common.availability.ServiceGate
import kotlin.test.Test
import kotlin.test.assertEquals

class MinVersionCheckTest {
    private val last = object : LastMinVersion {
        var value: String? = null
        override fun get() = value
        override fun set(value: String) { this.value = value }
    }

    /** [loaded] — the provider has read a config. */
    private class Flags(var min: String, override var loaded: Boolean = true) : FeatureFlags {
        override fun isEnabled(flag: Flag) = flag.defaultValue
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        // Only strings in the config: the version of the defaults, whether or not it was read.
        override val version get() = FLAGS_VERSION_DEFAULTS
        override suspend fun setUser(userId: String?) = Unit
        override suspend fun refresh() = Unit
        override fun value(key: RemoteValue) = if (key == RemoteValue.APP_MIN_VERSION) min else key.defaultValue
    }

    @Test fun `the remote minimum asks for an update before any request (FIN-22)`() {
        val flags = Flags("")
        val gate = ServiceGate()
        val check = MinVersionCheck(flags, ClientVersion("0.2.7", 41), gate, last)
        check.check()
        assertEquals(ServiceGate.State.Open, gate.state.value, "no minimum")
        flags.min = "0.2.7"; check.check()
        assertEquals(ServiceGate.State.Open, gate.state.value, "equal passes")
        flags.min = "not a version"; check.check()
        assertEquals(ServiceGate.State.Open, gate.state.value, "malformed never blocks")
        flags.min = "0.3.0"; check.check()
        assertEquals(ServiceGate.State.UpdateRequired(fromServer = false), gate.state.value)

        // A mistaken minimum fixed in the console lifts the screen without a restart (review of #81).
        flags.min = ""; check.check()
        assertEquals(ServiceGate.State.Open, gate.state.value)

        // The server's requirement is final: the config does not lift it.
        gate.updateRequired(); check.check()
        assertEquals(ServiceGate.State.UpdateRequired(fromServer = true), gate.state.value)
    }

    @Test fun `a config that arrives right after start is not missed`() {
        val flags = Flags("")
        val gate = ServiceGate()
        // Runs nothing until advanced: only an undispatched subscription exists by the time of the emit.
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        MinVersionCheck(flags, ClientVersion("0.2.7", 41), gate, last).start(scope)
        flags.min = "0.3.0"
        check(flags.changes.tryEmit(Unit)) { "the collector is subscribed before start() returns" }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ServiceGate.State.UpdateRequired(fromServer = false), gate.state.value)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun `at a cold start before the provider read its cache the last seen minimum is in effect`() {
        val gate = ServiceGate()
        MinVersionCheck(Flags("0.3.0"), ClientVersion("0.2.7", 41), gate, last).check()
        assertEquals("0.3.0", last.value)

        val coldGate = ServiceGate()
        val cold = Flags("", loaded = false)
        val check = MinVersionCheck(cold, ClientVersion("0.2.7", 41), coldGate, last)
        check.check()
        assertEquals(ServiceGate.State.UpdateRequired(fromServer = false), coldGate.state.value, "before the first request")
        cold.loaded = true; check.check()
        assertEquals(ServiceGate.State.Open, coldGate.state.value, "the read config has no minimum now")
        assertEquals("", last.value)
    }

    @Test fun `without a remote value the default is no minimum`() {
        assertEquals("", StaticFlags().value(RemoteValue.APP_MIN_VERSION))
    }
}
