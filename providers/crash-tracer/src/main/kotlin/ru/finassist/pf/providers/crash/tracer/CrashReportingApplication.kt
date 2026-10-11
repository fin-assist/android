package ru.finassist.pf.providers.crash.tracer

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import ru.ok.tracer.CoreTracerConfiguration
import ru.ok.tracer.HasTracerConfiguration
import ru.ok.tracer.TracerConfiguration
import ru.ok.tracer.crash.report.CrashFreeConfiguration
import ru.ok.tracer.crash.report.CrashReportConfiguration

/**
 * Base class of the application: hands the SDK its configuration. Tracer reads [tracerConfiguration] once, from
 * its startup provider — after `attachBaseContext` and before `Application.onCreate`, so nothing injected is
 * available here yet and the values come from build constants.
 *
 * It lives in this module so that `:app` does not reference the SDK: the app class only extends this one. In a
 * build without an app token the startup provider is disabled and the property is never read.
 */
abstract class CrashReportingApplication : Application(), HasTracerConfiguration {

    /**
     * Whether a debuggable build uploads reports (the SDK does not by default). The app turns it on for debug
     * builds: a build gets a token only when someone wants its crashes — the `mock` debug build of the alpha.
     */
    protected abstract val uploadCrashesFromDebugBuilds: Boolean

    /** `true` when the build has a Tracer app token (the SDK started from its provider before [onCreate]). */
    protected abstract val crashReportingEnabled: Boolean

    override val tracerConfiguration: List<TracerConfiguration>
        get() = tracerConfiguration(uploadCrashesFromDebugBuilds)

    override fun onCreate() {
        super.onCreate()
        // Tracer has installed its handler by now; put the scrubbing one in front of it. The original message
        // stays in logcat only in debuggable builds.
        if (crashReportingEnabled) {
            val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            ScrubbingExceptionHandler.install { thread, error ->
                if (debuggable) Log.e(TAG, "Uncaught in ${thread.name} (sent to Tracer without the message)", error)
            }
        }
    }

    private companion object {
        const val TAG = "PfCrash"
    }
}

/** The SDK configuration of the app (FIN-24): crashes, ANR, crash-free sessions; no native crashes. */
internal fun tracerConfiguration(debugUpload: Boolean): List<TracerConfiguration> = listOf(
    CoreTracerConfiguration.build {
        setDebugUpload(debugUpload)
    },
    CrashReportConfiguration.build {
        setEnabled(true)
        // No native code in the app: `tracer-crash-report-native` is not on the classpath.
        setNativeEnabled(false)
        setSendAnr(true)
        // A soft rate limit instead of the hard cap on non-fatals: a noisy degradation thins out rather than
        // cutting off every later report of the session.
        setExperimentalNonFatalRateLimitEnabled(true)
    },
    CrashFreeConfiguration.build {
        setEnabled(true)
    },
)
