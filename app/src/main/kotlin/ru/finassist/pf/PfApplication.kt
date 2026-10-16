package ru.finassist.pf

import android.content.pm.ApplicationInfo
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.finassist.pf.core.designsystem.components.pfExposeTestTags
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.MinVersionCheck
import ru.finassist.pf.core.tracking.CrashContextSync
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.profile.api.TimeZoneSync
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
    @Inject lateinit var timeZoneSync: TimeZoneSync
    @Inject lateinit var minVersionCheck: MinVersionCheck

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Debug builds upload too: a debug build only has a token when its crashes are wanted (the mock alpha).
    override val uploadCrashesFromDebugBuilds: Boolean get() = BuildConfig.DEBUG
    override val crashReportingEnabled: Boolean get() = BuildConfig.CRASH_REPORTING_ENABLED

    override fun onCreate() {
        // Test tags as resource ids for UI tests (docs/e2e.md): debuggable builds only, before any screen.
        pfExposeTestTags = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        super.onCreate()
        // `rc_ver` in crash reports: the config in effect now, then every config that arrives later.
        crashContext.follow(scope, flags.changes)
        // Remote minimum version (FIN-22): before the first request, then with every new config.
        minVersionCheck.start(scope)
        // Profile time zone after 72 h in a new device zone (FIN-91): app start, foreground, zone change, sign-in.
        timeZoneSync.start()
    }
}
