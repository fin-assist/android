package ru.finassist.pf.feature.applock.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.screenshot.DesignCheck
import ru.finassist.pf.feature.applock.api.BiometricAvailability

/**
 * Artboards `Passcode` / `PasscodeDark` / `PasscodeRepeat` / `PasscodeMismatch` / `PasscodeBiometric` (first-run setup)
 * and `PasscodeChange` (profile → change code, new-code step).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasscodeDesignCheckTest {
    private fun setup(step: EnterStep, entered: String = "", error: String? = null) =
        PasscodeEntryUiState(step = step, entered = entered, error = error, biometric = BiometricAvailability.FINGERPRINT)

    private fun capture(name: String, state: PasscodeEntryUiState, dark: Boolean = false) = DesignCheck.capture(name, dark = dark) {
        PasscodeSetupContent(state, onDigit = {}, onDelete = {}, onEnableBiometric = {}, onSkipBiometric = {})
    }

    @Test fun passcode() = capture("Passcode", setup(EnterStep.NEW, entered = "12"))

    @Test fun passcodeDark() = capture("PasscodeDark", setup(EnterStep.NEW, entered = "12"), dark = true)

    @Test fun passcodeRepeat() = capture("PasscodeRepeat", setup(EnterStep.REPEAT, entered = "123"))

    @Test fun passcodeMismatch() = capture("PasscodeMismatch", setup(EnterStep.NEW, error = MISMATCH_MESSAGE))

    @Test fun passcodeBiometric() = capture("PasscodeBiometric", setup(EnterStep.BIOMETRIC))

    @Test fun passcodeChange() = DesignCheck.capture("PasscodeChange") {
        ChangePasscodeContent(setup(EnterStep.NEW), onBack = {}, onDigit = {}, onDelete = {})
    }
}
