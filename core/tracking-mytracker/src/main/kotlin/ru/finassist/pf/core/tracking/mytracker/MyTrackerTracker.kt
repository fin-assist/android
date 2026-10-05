package ru.finassist.pf.core.tracking.mytracker

import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.Tracker
import javax.inject.Inject
import javax.inject.Singleton

/** MyTracker events + AppMetrica crashes. Stage 8 wires the SDKs; until then calls are no-ops. */
@Singleton
class MyTrackerTracker @Inject constructor() : Tracker, CrashReporter {
    override fun track(event: String, params: Map<String, String>) = Unit
    override fun setUser(userId: String?) = Unit
    override fun reportNonFatal(throwable: Throwable, message: String?) = Unit
}
