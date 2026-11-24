package ru.finassist.pf.feature.assistant.impl.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.RequestOwner
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.StartPhoneVerificationRequest
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ErrorResponseInfo
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.storage.TooltipOnboardingStore
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantAboutViewModelTest {
    @Test fun `a tap reads the current document with its owner and does not cache a version`() = withViewModel { fixture, vm ->
        assertTrue(fixture.reads.isEmpty())
        assertEquals(fixture.validDocument.url, vm.loadDocument(fixture.owner))
        fixture.reply = { fixture.validDocument.copy(version = "2", url = "https://example.ru/legal/about-2") }
        assertEquals("https://example.ru/legal/about-2", vm.loadDocument(fixture.owner))
        val request: Pair<ConsentType, RequestOwner?> = ConsentType.ASSISTANT to fixture.owner
        assertEquals(listOf(request, request), fixture.reads)
    }

    @Test fun `malformed URLs and the wrong document type cannot open a browser`() = withViewModel { fixture, vm ->
        val doc = fixture.validDocument
        for (rejected in listOf(
            doc.copy(url = "http://example.ru/legal/about"),
            doc.copy(url = "javascript:example"),
            doc.copy(url = "https:/missing-host"),
            doc.copy(type = ConsentType.PERSONAL_DATA),
        )) {
            fixture.reply = { rejected }
            assertNull(vm.loadDocument(fixture.owner))
            assertNotNull(vm.document.value.error)
            assertTrue(vm.document.value.retryReady)
        }
        assertEquals(4, fixture.reads.size)
    }

    @Test fun `a refusal survives a subsequent tap without another GET`() = withViewModel { fixture, vm ->
        fixture.reply = { throw AppError.Server(503, response = ErrorResponseInfo("NOT_READY", 503, requestRetryable = false)) }
        assertNull(vm.loadDocument(fixture.owner))
        assertNotNull(vm.document.value.error)
        assertFalse(vm.document.value.retryReady)
        assertNull(vm.loadDocument(fixture.owner))
        assertEquals(1, fixture.reads.size)
    }

    @Test fun `a rate limited request becomes retryable only after its wait`() = withViewModel { fixture, vm ->
        fixture.reply = { throw AppError.RateLimited(null, retryAfterSeconds = 1) }
        assertNull(vm.loadDocument(fixture.owner))
        assertFalse(vm.document.value.retryReady)
        advanceTimeBy(999)
        assertNull(vm.loadDocument(fixture.owner))
        assertEquals(1, fixture.reads.size)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(vm.document.value.retryReady)
        fixture.reply = { fixture.validDocument }
        assertEquals(fixture.validDocument.url, vm.loadDocument(fixture.owner))
        assertNull(vm.document.value.error)
    }

    @Test fun `only the active login can claim About and its claim is scoped to chat`() = withViewModel { fixture, vm ->
        assertFalse(vm.claimOnboarding(RequestOwner(fixture.owner.userId, "old-login")))
        assertTrue(vm.claimOnboarding(fixture.owner))
        assertFalse(fixture.onboarding.claim("assistant_chat", fixture.owner.userId, fixture.owner.authSessionEpoch))
        assertTrue(fixture.onboarding.claim("analytics", fixture.owner.userId, fixture.owner.authSessionEpoch))
    }

    private fun withViewModel(block: suspend kotlinx.coroutines.test.TestScope.(AssistantAboutTestFixture, AssistantAboutViewModel) -> Unit) = runTest {
        Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val fixture = AssistantAboutTestFixture(ElapsedClock { testScheduler.currentTime })
        val vm = fixture.viewModel()
        try {
            runCurrent()
            block(fixture, vm)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}

internal class AssistantAboutTestFixture(private val clock: ElapsedClock = ElapsedClock { 0L }) : AuthApi, SessionRepository {
    val owner = RequestOwner("user", "login")
    override val state = MutableStateFlow<SessionState>(SessionState.SignedIn(owner.userId, owner.authSessionEpoch))
    val validDocument = ConsentDocument(ConsentType.ASSISTANT, "1", "Согласие", "https://example.ru/legal/about-1")
    val reads = mutableListOf<Pair<ConsentType, RequestOwner?>>()
    var reply: suspend () -> ConsentDocument = { validDocument }
    private val preferences = MutableStateFlow(preferencesOf(
        stringPreferencesKey("session.identity.user") to owner.userId,
        stringPreferencesKey("session.identity.epoch") to owner.authSessionEpoch,
    ))
    val onboarding = TooltipOnboardingStore(object : DataStore<Preferences> {
        override val data = preferences
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(preferences.value).also { preferences.value = it }
    })

    fun viewModel() = AssistantAboutViewModel(this, onboarding, this, clock)

    override suspend fun getConsentDocument(type: ConsentType): ConsentDocument {
        reads += type to currentCoroutineContext()[RequestOwner]
        return reply()
    }

    override suspend fun startPhoneVerification(key: IdempotencyKey, request: StartPhoneVerificationRequest) = error("unused")
    override fun streamPhoneVerification(verificationToken: String) = error("unused")
    override suspend fun register(key: IdempotencyKey, request: RegisterRequest) = error("unused")
    override suspend fun refreshTokens(key: IdempotencyKey, refreshToken: String) = error("unused")
    override suspend fun logout(accessToken: String, refreshToken: String) = Unit
    override suspend fun consumeJustRegistered() = false
    override suspend fun signOut(reason: String?) = Unit
    override suspend fun clearLocal(reason: String?) = Unit
    override fun consumeSignOutReason(): String? = null
}
