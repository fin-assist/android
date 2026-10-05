package ru.finassist.pf

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.ui.PfApp
import javax.inject.Inject

/** FragmentActivity (not ComponentActivity) because BiometricPrompt requires one. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var session: SessionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { session.state.value is SessionState.Unknown }
        enableEdgeToEdge()
        setContent { PfApp() }
    }
}
