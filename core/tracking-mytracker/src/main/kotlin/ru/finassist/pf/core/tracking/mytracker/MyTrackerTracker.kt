package ru.finassist.pf.core.tracking.mytracker

import android.app.Application
import android.util.Log
import com.my.tracker.MyTracker
import io.appmetrica.analytics.AppMetrica
import io.appmetrica.analytics.AppMetricaConfig
import ru.finassist.pf.core.tracking.AppInitializer
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.Tracker
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Product events → MyTracker; crashes, ANRs and non-fatals → AppMetrica. Both receive our user id as the custom
 * identifier after login. Empty keys (not configured yet) switch the corresponding SDK off.
 */
@Singleton
class MyTrackerTracker @Inject constructor(
    @Named("myTrackerId") private val myTrackerId: String,
    @Named("appMetricaKey") private val appMetricaKey: String,
) : Tracker, CrashReporter, AppInitializer {
    private val myTracker get() = myTrackerId.isNotBlank()
    private val appMetrica get() = appMetricaKey.isNotBlank()

    override fun initialize(application: Application) {
        if (myTracker) MyTracker.initTracker(myTrackerId, application) else Log.w(TAG, "MyTracker id is empty — events disabled")
        if (appMetrica) {
            AppMetrica.activate(application, AppMetricaConfig.newConfigBuilder(appMetricaKey).withCrashReporting(true).build())
        } else Log.w(TAG, "AppMetrica key is empty — crash reporting disabled")
    }

    override fun track(event: String, params: Map<String, String>) {
        if (myTracker) MyTracker.trackEvent(event, params)
    }

    override fun setUser(userId: String?) {
        if (myTracker) MyTracker.getTrackerParams().setCustomUserId(userId)
        if (appMetrica) AppMetrica.setUserProfileID(userId)
    }

    override fun reportNonFatal(throwable: Throwable, message: String?) {
        if (appMetrica) AppMetrica.reportError(message ?: throwable.javaClass.simpleName, throwable)
    }

    private companion object { const val TAG = "PfTrack" }
}
