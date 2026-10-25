package ru.finassist.pf.mock.api

import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.model.Consent
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.MockErrorOperation
import ru.finassist.pf.mock.apiError

class MockProfileApi(private val backend: MockBackend) : ProfileApi {

    override suspend fun getProfile(): Profile {
        backend.simulateNetwork()
        backend.requireSession()
        return backend.profile
    }

    override suspend fun updateProfile(update: ProfileUpdate): Profile {
        backend.simulateNetwork()
        backend.requireSession()
        backend.maybeInjectError(MockErrorOperation.PROFILE_UPDATE)
        return backend.mutex.withLock {
            backend.profile = backend.profile.copy(
                theme = update.theme ?: backend.profile.theme,
                timezone = update.timezone ?: backend.profile.timezone,
            )
            backend.profile
        }
    }

    override suspend fun deleteAccount() {
        backend.simulateNetwork()
        backend.wipe()
    }

    override suspend fun grantAssistantConsent(grant: AssistantConsentGrant): Profile {
        backend.simulateNetwork()
        backend.requireSession()
        if (grant.version != MockBackend.CONSENT_VERSION_ASSISTANT) throw backend.apiError(ErrorCodes.CONSENT_OUTDATED, 422)
        return backend.mutex.withLock {
            backend.profile = backend.profile.withConsent(
                Consent(ConsentType.ASSISTANT, granted = true, version = grant.version, currentVersion = MockBackend.CONSENT_VERSION_ASSISTANT),
            )
            backend.profile
        }
    }

    override suspend fun revokeAssistantConsent() {
        backend.simulateNetwork()
        backend.requireSession()
        backend.mutex.withLock {
            backend.profile = backend.profile.withConsent(
                Consent(ConsentType.ASSISTANT, granted = false, version = null, currentVersion = MockBackend.CONSENT_VERSION_ASSISTANT),
            )
        }
    }
}
