package ru.finassist.pf.core.toggles

import kotlinx.coroutines.flow.MutableSharedFlow
import ru.finassist.pf.core.common.availability.ClientVersion
import ru.finassist.pf.core.common.availability.ServiceGate
import kotlin.test.Test
import kotlin.test.assertEquals

class MinVersionCheckTest {
    private class Flags(var min: String) : FeatureFlags {
        override fun isEnabled(flag: Flag) = flag.defaultValue
        override val changes = MutableSharedFlow<Unit>()
        override val version = FLAGS_VERSION_DEFAULTS
        override suspend fun setUser(userId: String?) = Unit
        override suspend fun refresh() = Unit
        override fun value(key: RemoteValue) = if (key == RemoteValue.APP_MIN_VERSION) min else key.defaultValue
    }

    @Test fun `the remote minimum asks for an update before any request (FIN-22)`() {
        val flags = Flags("")
        val gate = ServiceGate()
        val check = MinVersionCheck(flags, ClientVersion("0.2.7", 41), gate)
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
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        MinVersionCheck(flags, ClientVersion("0.2.7", 41), gate).start(scope)
        flags.min = "0.3.0"
        kotlinx.coroutines.runBlocking { flags.changes.emit(Unit) }
        assertEquals(ServiceGate.State.UpdateRequired(fromServer = false), gate.state.value)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun `without a remote value the default is no minimum`() {
        assertEquals("", StaticFlags().value(RemoteValue.APP_MIN_VERSION))
    }
}
