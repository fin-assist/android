package ru.finassist.pf.feature.applock.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.screenshot.DesignCheck
import ru.finassist.pf.feature.applock.api.BiometricAvailability

/** Artboards `PasscodeLogin` / `PasscodeLoginDark` / `PasscodeLoginWrong` / `PasscodeLoginLast`: the unlock overlay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnlockDesignCheckTest {
    private val base = UnlockUiState(biometric = BiometricAvailability.FINGERPRINT, biometricEnabled = true)

    private fun capture(name: String, state: UnlockUiState, dark: Boolean = false) = DesignCheck.capture(name, dark = dark) {
        UnlockContent(state, onDigit = {}, onDelete = {}, onBiometric = {}, onAskForgot = {}, onForgot = {})
    }

    @Test fun passcodeLogin() = capture("PasscodeLogin", base.copy(entered = "12"))

    @Test fun passcodeLoginDark() = capture("PasscodeLoginDark", base.copy(entered = "12"), dark = true)

    @Test fun passcodeLoginWrong() = capture("PasscodeLoginWrong", base.copy(error = wrongCodeMessage(attemptsLeft = 3)))

    @Test fun passcodeLoginLast() = capture("PasscodeLoginLast", base.copy(error = wrongCodeMessage(attemptsLeft = 1)))
}
