package ru.finassist.pf.feature.applock.impl.ui

import androidx.compose.runtime.Composable
import javax.inject.Inject

/**
 * Composables the app embeds outside the NavHost (by lock state, not by route). Injected as an interface so
 * the app shell does not reference screen functions of this module directly.
 */
interface AppLockScreens {
    /** First-run passcode setup; [onDone] after the code (and the biometric offer) is finished. */
    @Composable
    fun Setup(onDone: () -> Unit)

    /** Full-screen unlock overlay. Disappears by itself when the lock state becomes `Unlocked`. */
    @Composable
    fun Unlock()
}

internal class AppLockScreensImpl @Inject constructor() : AppLockScreens {
    @Composable
    override fun Setup(onDone: () -> Unit) = PasscodeSetupScreen(onDone)

    @Composable
    override fun Unlock() = UnlockScreen()
}
