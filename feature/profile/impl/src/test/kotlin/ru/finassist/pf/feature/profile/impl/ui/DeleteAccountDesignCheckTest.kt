package ru.finassist.pf.feature.profile.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.screenshot.DesignCheck

/**
 * Artboards `DeleteAccountDialog`, `DeleteAccountOffline`, `DeleteAccountBusy`. The app asks for the passcode after the
 * dialog (applock `Confirm`), so offline and busy are shown on the delete screen itself, not inside the dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeleteAccountDesignCheckTest {
    private fun capture(name: String, state: DeleteUiState, popups: Boolean = false) =
        DesignCheck.capture(name, popups = popups) {
            DeleteAccountContent(state, onBack = {}, onAskConfirm = {}, onConfirmed = {})
        }

    @Test fun deleteAccountDialog() = capture("DeleteAccountDialog", DeleteUiState(askConfirm = true), popups = true)

    @Test fun deleteAccountOffline() = capture(
        "DeleteAccountOffline",
        DeleteUiState(error = "Нет сети — аккаунт не удалён. Проверьте интернет и повторите"),
    )

    @Test fun deleteAccountBusy() = capture("DeleteAccountBusy", DeleteUiState(deleting = true))
}
