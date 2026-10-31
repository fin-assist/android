package ru.finassist.pf.feature.assistant.impl.ui

import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.common.error.AppError
import java.time.OffsetDateTime

/** Profile reads used by the retained chat's corrective-consent tests. */
internal class ConsentProfileFake : ProfileApi {
    var profile = Profile("u1", "+79160000000", Theme.SYSTEM, "Europe/Moscow",
        createdAt = OffsetDateTime.parse("2026-01-01T00:00:00+03:00"))
    var failure: AppError? = null
    var calls = 0

    override suspend fun getProfile(): Profile {
        calls++
        failure?.let { throw it }
        return profile
    }
    override suspend fun updateProfile(update: ProfileUpdate): Profile = error("unused")
    override suspend fun deleteAccount(): Unit = error("unused")
    override suspend fun grantAssistantConsent(grant: AssistantConsentGrant): Profile = error("unused")
    override suspend fun revokeAssistantConsent(): Unit = error("unused")
}
