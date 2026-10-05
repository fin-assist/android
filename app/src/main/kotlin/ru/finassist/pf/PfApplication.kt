package ru.finassist.pf

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import ru.finassist.pf.feature.applock.api.AppLock
import javax.inject.Inject

@HiltAndroidApp
class PfApplication : Application() {
    @Inject lateinit var appLock: AppLock

    override fun onCreate() {
        super.onCreate()
        // Lock after 5 minutes in background: the process lifecycle sees the whole app, not one activity.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = appLock.onAppForegrounded(SystemClock.elapsedRealtime())
            override fun onStop(owner: LifecycleOwner) = appLock.onAppBackgrounded(SystemClock.elapsedRealtime())
        })
    }
}
