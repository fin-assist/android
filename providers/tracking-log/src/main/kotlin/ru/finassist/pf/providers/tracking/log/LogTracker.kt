package ru.finassist.pf.providers.tracking.log

import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.Tracker
import javax.inject.Inject
import javax.inject.Singleton

/** Logcat-only tracking for the `mock` flavor. Same event stream as production, no SDK. */
@Singleton
class LogTracker @Inject constructor() : Tracker, CrashReporter {
    private var userId: String? = null

    override fun track(event: Event, params: Map<String, String>) {
        Log.i(TAG, "event ${event.name} user=$userId ${if (params.isEmpty()) "" else params}")
    }

    override fun setUserId(userId: String?) {
        this.userId = userId
    }

    override fun report(throwable: Throwable, message: String?) {
        Log.e(TAG, "non-fatal ${message ?: ""}", throwable)
    }

    override fun log(message: String) {
        Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "PfTracking"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LogTrackingModule {
    @Binds abstract fun tracker(impl: LogTracker): Tracker
    @Binds abstract fun crashReporter(impl: LogTracker): CrashReporter
}
