package ru.finassist.pf.feature.assistant.impl.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.withContext
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.RequestOwner
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.storage.TooltipOnboardingStore
import ru.finassist.pf.feature.auth.api.SessionRepository
import javax.inject.Inject

/** Read-only support for the chat's About sheet. */
@HiltViewModel
class AssistantAboutViewModel @Inject constructor(
    sessionRepository: SessionRepository,
    private val onboarding: TooltipOnboardingStore,
    private val authApi: AuthApi,
    elapsedClock: ElapsedClock,
) : ViewModel() {
    private val documentGate = RequestRetryGate(elapsedClock)
    internal val session = sessionRepository.state
    internal val documentReady = documentGate.readiness

    internal suspend fun claimOnboarding(owner: RequestOwner): Boolean =
        onboarding.claim("assistant_chat", owner.userId, owner.authSessionEpoch)

    internal suspend fun loadDocument(owner: RequestOwner): ConsentDocument? {
        if (!documentGate.isReady()) return null
        return try {
            withContext(owner) { authApi.getConsentDocument(ConsentType.ASSISTANT) }
        } catch (error: AppError) {
            documentGate.record(error)
            null
        }
    }
}
