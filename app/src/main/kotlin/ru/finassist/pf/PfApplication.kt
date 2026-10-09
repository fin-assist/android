package ru.finassist.pf

import android.app.Application
import android.content.pm.ApplicationInfo
import dagger.hilt.android.HiltAndroidApp
import ru.finassist.pf.core.designsystem.components.pfExposeTestTags
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

    override fun onCreate() {
        // Test tags as resource ids for UI tests (docs/e2e.md): debuggable builds only, before any screen.
        pfExposeTestTags = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        super.onCreate()
    }
}
