package ru.finassist.pf.feature.profile.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.screenshot.DesignCheck

/**
 * Artboards `DeleteAccountDialog`, `DeleteAccountBusy`, `DeleteAccountOffline`: after the passcode the dialog lies
 * over the profile; the request runs from it, its progress and a failure are shown inside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeleteAccountDesignCheckTest {
    private fun capture(name: String, state: DeleteUiState) = DesignCheck.capture(name, popups = true, tab = 2) {
        ProfileContent(
            ProfileDesignFixtures.state, ProfileDesignFixtures.actions, onRetry = {}, onRevokeConsent = {}, onThemeSheet = {},
            onTheme = {}, onSupport = {}, onAskLogout = {}, onLogout = {}, onSnackbarShown = {}, versionName = "0.1.0",
            deleteState = state,
        )
    }

    @Test fun deleteAccountDialog() = capture("DeleteAccountDialog", DeleteUiState(dialog = true))

    @Test fun deleteAccountBusy() = capture("DeleteAccountBusy", DeleteUiState(dialog = true, deleting = true))

    @Test fun deleteAccountOffline() = capture("DeleteAccountOffline", DeleteUiState(dialog = true, error = DeleteAccountViewModel.OFFLINE))
}
