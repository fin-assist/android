package ru.finassist.pf

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.storage.AppPreferences
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.applock.impl.ui.AppLockScreens
import ru.finassist.pf.feature.auth.api.SessionRepository
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Financial data: no screenshots and a blank Recents thumbnail in release builds.
        if (!BuildConfig.DEBUG) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            PfApp(
                entries = entries,
                session = session,
                appLock = appLock,
                appLockScreens = appLockScreens,
                preferences = preferences,
                flags = flags,
            )
        }
    }
}
