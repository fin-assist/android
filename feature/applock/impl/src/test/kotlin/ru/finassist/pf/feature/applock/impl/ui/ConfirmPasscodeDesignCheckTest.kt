package ru.finassist.pf.feature.applock.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.screenshot.DesignCheck
import ru.finassist.pf.feature.applock.api.BiometricAvailability

/**
 * Artboards `DeleteAccount` / `DeleteAccountDark` / `DeleteAccountCodeWrong`: the passcode step of account deletion.
 * In the app it is [ConfirmPasscodeScreen], which the profile's delete flow opens before the request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfirmPasscodeDesignCheckTest {
    private val base = ConfirmUiState(biometric = BiometricAvailability.FINGERPRINT, biometricEnabled = true)

    private fun capture(name: String, state: ConfirmUiState = base, dark: Boolean = false) =
        DesignCheck.capture(name, dark = dark) {
            ConfirmPasscodeContent(state, onBack = {}, onDigit = {}, onDelete = {}, onBiometric = {})
        }

    @Test fun deleteAccount() = capture("DeleteAccount")

    @Test fun deleteAccountDark() = capture("DeleteAccountDark", dark = true)

    @Test fun deleteAccountCodeWrong() = capture("DeleteAccountCodeWrong", base.copy(error = CONFIRM_WRONG_MESSAGE))
}
