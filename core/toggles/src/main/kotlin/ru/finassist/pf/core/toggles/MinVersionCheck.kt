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
 *
 * The provider reads its cached config asynchronously, so at a cold start it may have nothing yet: until it does, the
 * minimum of the last config seen ([LastMinVersion], read synchronously) is in effect.
 */
class MinVersionCheck(
    private val flags: FeatureFlags,
    private val version: ClientVersion,
    private val gate: ServiceGate,
    private val last: LastMinVersion,
) {
    fun check() {
        // Not by FeatureFlags.version: a config holding only strings has the version of the defaults (review of #81).
        val minimum = if (!flags.loaded) {
            last.get()
        } else {
            flags.value(RemoteValue.APP_MIN_VERSION).also(last::set)
        }
        if (version.isBelow(minimum)) gate.updateRequiredByConfig() else gate.configAllowsVersion()
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

/** The `app.min_version` of the last config the app saw; read synchronously at start (app storage). */
interface LastMinVersion {
    fun get(): String?
    fun set(value: String)
}
