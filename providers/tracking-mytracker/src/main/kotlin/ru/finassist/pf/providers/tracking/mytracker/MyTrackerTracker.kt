package ru.finassist.pf.providers.tracking.mytracker

import android.app.Application
import android.content.Context
import com.my.tracker.MyTracker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.Tracker
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Product events in MyTracker. Event names come from the `Events` registry; parameters never contain personal
 * data. The user is identified by our own `user_id` (custom user id), never by the phone number. A blank SDK
 * key (local builds) turns tracking into a no-op.
 */
@Singleton
class MyTrackerTracker @Inject constructor(
    @ApplicationContext context: Context,
    @Named("mytrackerSdkKey") sdkKey: String,
) : Tracker {

    private val enabled: Boolean = sdkKey.isNotBlank()

    init {
        if (enabled) {
            MyTracker.initTracker(sdkKey, context.applicationContext as Application)
        }
    }

    override fun track(event: Event, params: Map<String, String>) {
        if (!enabled) return
        if (params.isEmpty()) MyTracker.trackEvent(event.name) else MyTracker.trackEvent(event.name, params)
    }

    override fun setUserId(userId: String?) {
        if (!enabled) return
        MyTracker.getTrackerParams().setCustomUserId(userId)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MyTrackerModule {
    @Binds abstract fun tracker(impl: MyTrackerTracker): Tracker
}
