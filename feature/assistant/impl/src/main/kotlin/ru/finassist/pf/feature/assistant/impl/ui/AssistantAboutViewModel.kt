package ru.finassist.pf.feature.assistant.impl.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.RequestOwner
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.storage.TooltipOnboardingStore
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import java.net.URI
import javax.inject.Inject

internal data class AboutDocumentState(
    val opening: Boolean = false,
    val error: ErrorPresentation? = null,
    val retryReady: Boolean = true,
)

/** Read-only support for the chat's About sheet. */
@HiltViewModel
class AssistantAboutViewModel @Inject constructor(
    sessionRepository: SessionRepository,
    private val onboarding: TooltipOnboardingStore,
    private val authApi: AuthApi,
    elapsedClock: ElapsedClock,
) : ViewModel() {
    private val documentGate = RequestRetryGate(elapsedClock)
    private val _document = MutableStateFlow(AboutDocumentState())
    internal val document = _document.asStateFlow()
    internal val session = sessionRepository.state.stateIn(viewModelScope, SharingStarted.Eagerly, SessionState.Unknown)

    init {
        viewModelScope.launch {
            documentGate.readiness.collect { _document.update { state -> state.copy(retryReady = documentGate.isReady()) } }
        }
    }

    internal suspend fun claimOnboarding(owner: RequestOwner): Boolean =
        onboarding.claim("assistant_chat", owner.userId, owner.authSessionEpoch)

    /** Each tap reads the current document; closing the sheet cancels its attempt to open the browser. */
    internal suspend fun loadDocument(owner: RequestOwner): String? {
        if (!isCurrent(owner) || _document.value.opening || !documentGate.isReady()) return null
        _document.update { it.copy(opening = true, error = null) }
        return try {
            val response = withContext(owner) { authApi.getConsentDocument(ConsentType.ASSISTANT) }
            if (!isCurrent(owner)) return null
            documentUrl(response).also { if (it == null) documentLaunchFailed(owner) }
        } catch (error: AppError) {
            if (isCurrent(owner)) {
                documentGate.record(error)
                _document.update { it.copy(error = error.presentation, retryReady = documentGate.isReady()) }
            }
            null
        } finally {
            _document.update { it.copy(opening = false) }
        }
    }

    internal fun documentLaunchFailed(owner: RequestOwner) {
        if (isCurrent(owner)) _document.update { it.copy(error = ErrorPresentation()) }
    }

    private fun isCurrent(owner: RequestOwner): Boolean =
        session.value == SessionState.SignedIn(owner.userId, owner.authSessionEpoch)

    private fun documentUrl(document: ConsentDocument): String? {
        if (document.type != ConsentType.ASSISTANT) return null
        val uri = runCatching { URI(document.url) }.getOrNull() ?: return null
        return document.url.takeIf { uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() }
    }
}
