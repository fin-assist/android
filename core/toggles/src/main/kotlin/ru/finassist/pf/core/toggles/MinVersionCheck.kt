package ru.finassist.pf.core.toggles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import ru.finassist.pf.core.common.availability.ClientVersion
import ru.finassist.pf.core.common.availability.ServiceGate

/**
 * The Remote Config minimum `app.min_version` (FIN-22): checked at start, before the first request, and again with every
 * new config. Below it the [ServiceGate] asks for an update; a later config without that minimum lifts it (a mistaken
 * value in the console must not lock users out for good). The server's `CLIENT_VERSION_UNSUPPORTED` stays the source of
 * truth and is final: an empty or malformed minimum never blocks.
 */
class MinVersionCheck(private val flags: FeatureFlags, private val version: ClientVersion, private val gate: ServiceGate) {
    fun check() {
        if (version.isBelow(flags.value(RemoteValue.APP_MIN_VERSION))) gate.updateRequiredByConfig() else gate.configAllowsVersion()
    }

    /**
     * [check] now and after every config update. The subscription comes first (undispatched): a config arriving between
     * the first check and the subscription is not lost.
     */
    fun start(scope: CoroutineScope) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { flags.changes.collect { check() } }
        check()
    }
}
