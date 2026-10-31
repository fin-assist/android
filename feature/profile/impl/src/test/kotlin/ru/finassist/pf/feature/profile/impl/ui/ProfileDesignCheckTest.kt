package ru.finassist.pf.feature.profile.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import ru.finassist.pf.core.common.error.ConnectionEnvironment
import ru.finassist.pf.core.common.error.ConnectionEnvironmentReader
import ru.finassist.pf.core.designsystem.components.LocalConnectionEnvironmentReader
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.screenshot.DesignCheck

/** Artboards `Profile` / `ProfileDark` (390×984): same phone, statement summary and assistant limit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileDesignCheckTest {
    private val state = ProfileDesignFixtures.state
    private val actions = ProfileDesignFixtures.actions
    private val now = ProfileDesignFixtures.now

    private fun capture(
        name: String,
        dark: Boolean = false,
        state: ProfileUiState = this.state,
        popups: Boolean = false,
        environment: ConnectionEnvironment = ConnectionEnvironment.UNKNOWN,
    ) = DesignCheck.capture(name, dark = dark, heightDp = 984, tab = 3, popups = popups) {
        CompositionLocalProvider(LocalConnectionEnvironmentReader provides ConnectionEnvironmentReader { environment }) {
            ProfileContent(
                state, actions, onRetry = {}, onRevokeConsent = {}, onThemeSheet = {}, onTheme = {}, onSupport = {},
                onAskLogout = {}, onLogout = {}, onSnackbarShown = {}, versionName = "0.1.0",
            )
        }

    }

    @Test fun offlineVpn() = capture("ProfileOfflineVpn", state = state.copy(loading = false, profile = null, offline = true), environment = ConnectionEnvironment.VPN)
    @Test fun offlineVpnDark() = capture("ProfileOfflineVpnDark", dark = true, state = state.copy(loading = false, profile = null, offline = true), environment = ConnectionEnvironment.VPN)
    @Test fun offlineWithoutVpn() = capture("ProfileOffline", state = state.copy(loading = false, profile = null, offline = true))

    @Test fun profile() = capture("Profile")

    @Test fun profileDark() = capture("ProfileDark", dark = true)

    /** No statement uploaded: «Не загружена», the assistant row shows the full daily limit. */
    @Test fun profileNoData() = capture(
        "ProfileNoData",
        state = state.copy(
            statements = null,
            limit = AssistantLimit(remaining = 5, dailyMax = 5, resetsAt = now.plusHours(12)),
        ),
    )

    @Test fun profileThemeSheet() = capture("ProfileThemeSheet", state = state.copy(themeSheet = true), popups = true)
    // FIN-223 fixture: proposed artboard; snapshots are not executed under the current instruction.
    @Test fun serverWarning() = capture("ProfileServerWarning", state = state.copy(errorPresentation = ErrorPresentation(UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"), ErrorSeverity.WARNING)))
    @Test fun serverWarningDark() = capture("ProfileServerWarningDark", dark = true, state = state.copy(errorPresentation = ErrorPresentation(UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"), ErrorSeverity.WARNING)))

    @Test fun refreshing() = capture("ProfileRefreshing", state = state.copy(refreshing = true))
    @Test fun refreshingDark() = capture("ProfileRefreshingDark", dark = true, state = state.copy(refreshing = true))

}
