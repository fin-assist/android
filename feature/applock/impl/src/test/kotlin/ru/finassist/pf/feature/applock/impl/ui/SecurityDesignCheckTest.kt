package ru.finassist.pf.feature.applock.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.screenshot.DesignCheck
import ru.finassist.pf.feature.applock.api.BiometricAvailability

/** Artboards `Security` / `SecurityDark` (biometric login on) and `SecurityBiometricOff`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecurityDesignCheckTest {
    private fun capture(name: String, biometricEnabled: Boolean, dark: Boolean = false) = DesignCheck.capture(name, dark = dark) {
        SecurityContent(
            SecurityUiState(biometric = BiometricAvailability.FINGERPRINT, biometricEnabled = biometricEnabled),
            onBack = {}, onChangePasscode = {}, onBiometricSwitch = {},
        )
    }

    @Test fun security() = capture("Security", biometricEnabled = true)

    @Test fun securityDark() = capture("SecurityDark", biometricEnabled = true, dark = true)

    @Test fun securityBiometricOff() = capture("SecurityBiometricOff", biometricEnabled = false)
}
