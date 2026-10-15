package ru.finassist.pf.core.toggles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.finassist.pf.core.common.availability.ClientVersion
import ru.finassist.pf.core.common.availability.ServiceGate

/**
 * The Remote Config minimum `app.min_version` (FIN-22): checked at start, before the first request, and again with every
 * new config. Below it the [ServiceGate] asks for an update. The server's `CLIENT_VERSION_UNSUPPORTED` stays the source
 * of truth: an empty or malformed minimum never blocks.
 */
class MinVersionCheck(private val flags: FeatureFlags, private val version: ClientVersion, private val gate: ServiceGate) {
    fun check() {
        if (version.isBelow(flags.value(RemoteValue.APP_MIN_VERSION))) gate.updateRequired()
    }

    /** [check] now and after every config update. */
    fun start(scope: CoroutineScope) {
        check()
        scope.launch { flags.changes.collect { check() } }
    }
}
