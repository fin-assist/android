package ru.finassist.pf.core.tracking.log

import android.util.Log
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.Tracker
import javax.inject.Inject
import javax.inject.Singleton

/** Mock flavor: events go to logcat under the `PfTrack` tag. */
@Singleton
class LogTracker @Inject constructor() : Tracker, CrashReporter {
    private var user: String? = null
    override fun track(event: String, params: Map<String, String>) {
        Log.i("PfTrack", "$event ${params.entries.joinToString(" ") { "${it.key}=${it.value}" }} user=${user ?: "-"}")
    }
    override fun setUser(userId: String?) { user = userId }
    override fun reportNonFatal(throwable: Throwable, message: String?) { Log.w("PfTrack", "non-fatal: $message", throwable) }
}
