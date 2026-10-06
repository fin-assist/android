package ru.finassist.pf

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.Tracker
import javax.inject.Inject

/**
 * Injecting the providers here creates them in `onCreate`, so crash/ANR reporting, the tracker and the
 * remote config start with the process, before any screen.
 */
@HiltAndroidApp
class PfApplication : Application() {
    @Inject lateinit var crashReporter: CrashReporter
    @Inject lateinit var tracker: Tracker
    @Inject lateinit var flags: FeatureFlags
}
