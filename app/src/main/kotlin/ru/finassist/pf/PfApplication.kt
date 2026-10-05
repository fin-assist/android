package ru.finassist.pf

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import ru.finassist.pf.core.tracking.AppInitializer
import ru.finassist.pf.feature.applock.api.AppLock
import javax.inject.Inject

@HiltAndroidApp
class PfApplication : Application() {
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var initializers: Set<@JvmSuppressWildcards AppInitializer>

    override fun onCreate() {
        super.onCreate()
        // SDKs (Remote Config, MyTracker, AppMetrica) must start here, before any screen; the mock flavour has none.
        initializers.forEach { it.initialize(this) }
        // Lock after 5 minutes in background: the process lifecycle sees the whole app, not one activity.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = appLock.onAppForegrounded(SystemClock.elapsedRealtime())
            override fun onStop(owner: LifecycleOwner) = appLock.onAppBackgrounded(SystemClock.elapsedRealtime())
        })
    }
}
