package ru.finassist.pf.mock.api

import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.RequestOwner
import ru.finassist.pf.core.api.TokenStore
import kotlinx.coroutines.currentCoroutineContext
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

class MockProfileApi(private val backend: MockBackend, private val tokenStore: TokenStore? = null) : ProfileApi {

    private suspend fun checkCapturedOwner() {
        val owner = currentCoroutineContext()[RequestOwner] ?: return
        if (backend.profile.userId != owner.userId || !owner.matches(tokenStore?.current())) throw AppError.Unauthorized(ErrorCodes.INVALID_TOKEN)
    }

    override suspend fun getProfile(): Profile {
        backend.simulateNetwork()
        return backend.mutex.withLock {
            backend.requireSession()
            checkCapturedOwner()
            val current = backend.consents.publishedVersion(ConsentType.ASSISTANT)
            backend.profile.copy(consents = backend.profile.consents.map {
                if (it.type == ConsentType.ASSISTANT) it.copy(currentVersion = current) else it
            })
        }
    }

    override suspend fun updateProfile(update: ProfileUpdate): Profile {
        backend.simulateNetwork()
        backend.requireSession()
        backend.maybeInjectError(MockErrorOperation.PROFILE_UPDATE)
        return backend.mutex.withLock {
            checkCapturedOwner()
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
        backend.consents.recordGrant(grant.version)
        backend.simulateNetwork()
        backend.requireSession()
        return backend.mutex.withLock {
            val current = backend.consents.publishedVersion(ConsentType.ASSISTANT)
            if (grant.version != current) throw backend.apiError(ErrorCodes.CONSENT_OUTDATED, 422)
            backend.profile = backend.profile.withConsent(
                Consent(ConsentType.ASSISTANT, granted = true, version = grant.version, currentVersion = current),
            )
            backend.consents.granted(grant.version)
            backend.profile
        }
    }

    override suspend fun revokeAssistantConsent() {
        backend.simulateNetwork()
        val current = backend.consents.publishedVersion(ConsentType.ASSISTANT)
        backend.requireSession()
        backend.mutex.withLock {
            backend.profile = backend.profile.withConsent(
                Consent(ConsentType.ASSISTANT, granted = false, version = null, currentVersion = current),
            )
        }
    }
}
