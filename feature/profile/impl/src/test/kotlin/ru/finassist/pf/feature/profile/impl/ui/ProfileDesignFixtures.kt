package ru.finassist.pf.feature.profile.impl.ui

import ru.finassist.pf.core.api.model.AssistantConsent
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.StatementsSummary
import ru.finassist.pf.core.api.model.Theme
import java.time.OffsetDateTime

/** The profile of the mockups (`Profile`, `DeleteAccountDialog`…): phone, statement summary, assistant limit. */
internal object ProfileDesignFixtures {
    val now = OffsetDateTime.parse("2026-09-28T12:00:00+03:00")

    val state = ProfileUiState(
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

    val actions = ProfileActions(
        openHistory = {}, openUpload = {}, openChat = {}, openConsent = {}, openSecurity = {}, openDeleteAccount = {},
    )
}
