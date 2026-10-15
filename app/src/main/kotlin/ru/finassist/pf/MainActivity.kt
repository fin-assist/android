package ru.finassist.pf

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.ReportZone
import ru.finassist.pf.core.common.time.withZone
import ru.finassist.pf.core.designsystem.theme.LocalPfClock
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.storage.AppPreferences
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.tracking.Breadcrumb
import ru.finassist.pf.core.tracking.CrashContextSync
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.applock.impl.ui.AppLockScreens
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.ui.PfApp
import javax.inject.Inject

/**
 * Single activity. [FragmentActivity] rather than `ComponentActivity` because `BiometricPrompt` needs one.
 * Everything else lives in [PfApp].
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var entries: Set<@JvmSuppressWildcards FeatureEntry>
    @Inject lateinit var session: SessionRepository
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var appLockScreens: AppLockScreens
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var flags: FeatureFlags
    @Inject lateinit var crashContext: CrashContextSync
    @Inject lateinit var clock: Clock
    @Inject lateinit var reportZone: ReportZone

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Financial data: no screenshots and a blank Recents thumbnail in release builds.
        if (!BuildConfig.DEBUG) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            // A new profile zone regroups what is on screen by day (FIN-92).
            val profileZone by reportZone.profile.collectAsState()
            val shownClock = remember(profileZone) { clock.withZone(reportZone.current) }
            CompositionLocalProvider(LocalPfClock provides shownClock) {
                PfApp(
                    entries = entries,
                    session = session,
                    appLock = appLock,
                    appLockScreens = appLockScreens,
                    preferences = preferences,
                    flags = flags,
                    onScreenOpened = crashContext::onScreenOpened,
                    onSessionShown = { s ->
                        crashContext.onSessionShown(
                            when (s) {
                                SessionState.Unknown -> null
                                SessionState.SignedOut -> Breadcrumb.SessionCrumb.SIGNED_OUT
                                is SessionState.SignedIn -> Breadcrumb.SessionCrumb.SIGNED_IN
                            },
                        )
                    },
                )
            }
        }
    }
}
