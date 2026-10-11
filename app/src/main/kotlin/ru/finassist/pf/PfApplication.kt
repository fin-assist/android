package ru.finassist.pf

import android.content.pm.ApplicationInfo
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.finassist.pf.core.designsystem.components.pfExposeTestTags
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.tracking.CrashContextSync
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.providers.crash.tracer.CrashReportingApplication
import javax.inject.Inject

/**
 * Injecting the providers here creates them in `onCreate`, so the tracker and the remote config start with the
 * process, before any screen. Crash and ANR collection starts even earlier, from the SDK's own startup provider
 * ([CrashReportingApplication]) — when the build has a Tracer token.
 */
@HiltAndroidApp
class PfApplication : CrashReportingApplication() {
    @Inject lateinit var crashReporter: CrashReporter
    @Inject lateinit var crashContext: CrashContextSync
    @Inject lateinit var tracker: Tracker
    @Inject lateinit var flags: FeatureFlags

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Debug builds upload too: a debug build only has a token when its crashes are wanted (the mock alpha).
    override val uploadCrashesFromDebugBuilds: Boolean get() = BuildConfig.DEBUG

    override fun onCreate() {
        // Test tags as resource ids for UI tests (docs/e2e.md): debuggable builds only, before any screen.
        pfExposeTestTags = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        super.onCreate()
        // `rc_ver` in crash reports: the config in effect now, then every config that arrives later.
        crashContext.onConfigApplied()
        scope.launch { flags.changes.collect { crashContext.onConfigApplied() } }
    }
}
