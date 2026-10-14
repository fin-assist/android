package ru.finassist.pf.feature.profile.impl.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.profile.api.TimeZoneSync
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs [TimeZoneChecker] on app start and every return from the background (ProcessLifecycleOwner `ON_START`),
 * on `ACTION_TIMEZONE_CHANGED` while the process lives, and on sign-in; drops the record on sign-out.
 */
@Singleton
internal class TimeZoneSyncImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checker: TimeZoneChecker,
    private val session: SessionRepository,
    private val deviceZone: DeviceZone,
) : TimeZoneSync, DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
            deviceZone.reset()
            launchCheck()
        }
    }

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        // A system broadcast reaches a non-exported receiver as well.
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch(Dispatchers.Main.immediate) { ProcessLifecycleOwner.get().lifecycle.addObserver(this@TimeZoneSyncImpl) }
        scope.launch {
            session.state.distinctUntilChanged().collect { s ->
                if (s is SessionState.SignedIn) checker.check() else checker.signedOut()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) = launchCheck()

    private fun launchCheck() {
        scope.launch { checker.check() }
    }
}
