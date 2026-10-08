package ru.finassist.pf.feature.profile.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.AssistantConsent
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.StatementsSummary
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.screenshot.DesignCheck
import java.time.OffsetDateTime

/** Artboards `Profile` / `ProfileDark` (390×984): same phone, statement summary and assistant limit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileDesignCheckTest {
    private val now = OffsetDateTime.parse("2026-09-28T12:00:00+03:00")

    private val state = ProfileUiState(
        loading = false,
        profile = Profile(
            userId = "u1", phone = "+79161234567", theme = Theme.SYSTEM, timezone = "Europe/Moscow",
            assistantConsent = AssistantConsent(granted = true, version = "1", currentVersion = "1"), createdAt = now,
        ),
        statements = StatementsSummary(
            uploadCount = 3, operationCount = 1086,
            firstOperationAt = now.minusMonths(12), lastOperationAt = now.minusDays(3), gaps = emptyList(),
        ),
        statementsLoaded = true,
        limit = AssistantLimit(remaining = 3, dailyMax = 5, resetsAt = now.plusHours(12)),
        theme = Theme.SYSTEM,
        biometric = true,
        flags = ProfileFlags(assistant = true, deleteAccount = true, upload = true),
    )

    private val actions = ProfileActions(
        openHistory = {}, openUpload = {}, openChat = {}, openConsent = {}, openSecurity = {}, openDeleteAccount = {},
    )

    private fun capture(
        name: String,
        dark: Boolean = false,
        state: ProfileUiState = this.state,
        popups: Boolean = false,
    ) = DesignCheck.capture(name, dark = dark, heightDp = 984, tab = 2, popups = popups) {
        ProfileContent(
            state, actions, onRetry = {}, onRevokeConsent = {}, onThemeSheet = {}, onTheme = {}, onSupport = {},
            onAskLogout = {}, onLogout = {}, onSnackbarShown = {}, versionName = "0.1.0",
        )
    }

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
}
