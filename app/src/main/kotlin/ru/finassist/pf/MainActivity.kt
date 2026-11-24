package ru.finassist.pf

import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.feature.availability.impl.ui.AvailabilityScreens
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
    @Inject lateinit var gate: ServiceGate
    @Inject lateinit var availabilityScreens: AvailabilityScreens
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var flags: FeatureFlags
    @Inject lateinit var crashContext: CrashContextSync
    @Inject lateinit var clock: Clock
    @Inject lateinit var reportZone: ReportZone

    private val incomingStatement: IncomingStatement by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { session.state.collect(incomingStatement::observeSession) }
        // The view model retains an unconsumed selection on rotation. Restoring navigation must never submit again.
        if (savedInstanceState == null) acceptIncomingStatement(intent)
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
                    gate = gate,
                    availabilityScreens = availabilityScreens,
                    preferences = preferences,
                    flags = flags,
                    incomingStatement = incomingStatement,
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIncomingStatement(intent)
    }

    private fun acceptIncomingStatement(intent: Intent) {
        incomingStatementUri(intent)?.let(incomingStatement::offer)
    }
}
