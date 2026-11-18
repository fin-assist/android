package ru.finassist.pf.providers.tracking.log

import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.tracking.Breadcrumb
import ru.finassist.pf.core.tracking.CrashKey
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.CrashValue
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.NonFatal
import ru.finassist.pf.core.tracking.ScrubbedThrowable
import ru.finassist.pf.core.tracking.Tracker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logcat tracking for the `mock` flavor: the same event stream as production, no SDK. As a crash reporter it runs
 * next to AppMetrica when configured (`CompositeCrashReporter`), so non-fatals and breadcrumbs are visible locally.
 */
@Singleton
class LogTracker @Inject constructor() : Tracker, CrashReporter {
    override fun track(event: Event, params: Map<String, String>) {
        Log.i(TAG, "event ${event.name} ${if (params.isEmpty()) "" else params}")
    }

    override fun report(error: NonFatal) {
        // The same scrubbed copy AppMetrica gets: in `mock` messages may quote the statement (titles, amounts).
        Log.e(TAG, "non-fatal ${error.severity} ${error.issueKey}", ScrubbedThrowable.of(error.cause, error.code?.value))
    }

    override fun breadcrumb(crumb: Breadcrumb) {
        Log.d(TAG, crumb.render())
    }

    override fun setKey(key: CrashKey, value: CrashValue?) {
        Log.d(TAG, "key ${key.name}=${value?.value}")
    }

    private companion object {
        const val TAG = "PfTracking"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LogTrackingModule {
    @Binds abstract fun tracker(impl: LogTracker): Tracker
    @Binds @IntoSet abstract fun crashReporter(impl: LogTracker): CrashReporter
}
