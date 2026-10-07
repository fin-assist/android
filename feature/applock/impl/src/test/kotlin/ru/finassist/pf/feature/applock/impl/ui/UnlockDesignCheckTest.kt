package ru.finassist.pf.feature.applock.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.screenshot.DesignCheck
import ru.finassist.pf.feature.applock.api.BiometricAvailability

/** Artboards `PasscodeLogin` / `PasscodeLoginDark`: two digits entered, fingerprint key on the pad. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnlockDesignCheckTest {
    private val state = UnlockUiState(entered = "12", biometric = BiometricAvailability.FINGERPRINT, biometricEnabled = true)

    private fun capture(name: String, dark: Boolean) = DesignCheck.capture(name, dark = dark) {
        UnlockContent(state, onDigit = {}, onDelete = {}, onBiometric = {}, onAskForgot = {}, onForgot = {})
    }

    @Test fun passcodeLogin() = capture("PasscodeLogin", dark = false)

    @Test fun passcodeLoginDark() = capture("PasscodeLoginDark", dark = true)
}
