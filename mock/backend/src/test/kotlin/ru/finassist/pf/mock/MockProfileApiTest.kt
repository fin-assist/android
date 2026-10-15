package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.mock.api.MockAssistantApi
import ru.finassist.pf.mock.api.MockProfileApi
import ru.finassist.pf.mock.api.MockStatementsApi
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Profile (api.md 2): settings, assistant consent, account deletion. */
class MockProfileApiTest {
    private val backend = testBackend()
    private val api = MockProfileApi(backend)

    @Test
    fun `a new profile has no assistant consent and the system theme`() = runBlocking<Unit> {
        val profile = api.getProfile()
        assertEquals("u1", profile.userId)
        assertEquals(Theme.SYSTEM, profile.theme)
        assertEquals("Europe/Moscow", profile.timezone)
        assertFalse(profile.assistantConsent!!.granted)
        assertTrue(profile.assistantConsent!!.needsRenewal)
        assertEquals(MockBackend.CONSENT_VERSION_ASSISTANT, profile.assistantConsent!!.currentVersion)
    }

    @Test
    fun `an update changes only the fields it carries`() = runBlocking<Unit> {
        val dark = api.updateProfile(ProfileUpdate(theme = Theme.DARK))
        assertEquals(Theme.DARK, dark.theme)
        assertEquals("Europe/Moscow", dark.timezone)
        val moved = api.updateProfile(ProfileUpdate(timezone = "Asia/Novosibirsk"))
        assertEquals(Theme.DARK, moved.theme)
        assertEquals("Asia/Novosibirsk", moved.timezone)
        assertEquals(moved, api.getProfile())
    }

    @Test
    fun `the assistant consent is granted for the current version only and can be revoked`() = runBlocking<Unit> {
        val outdated = assertFailsWith<AppError.Api> { api.grantAssistantConsent(AssistantConsentGrant("2000-01-01")) }
        assertEquals(ErrorCodes.CONSENT_OUTDATED to 422, outdated.code to outdated.httpStatus)
        assertFalse(api.getProfile().assistantConsent!!.granted)

        val granted = api.grantAssistantConsent(AssistantConsentGrant(MockBackend.CONSENT_VERSION_ASSISTANT)).assistantConsent!!
        assertTrue(granted.granted)
        assertEquals(MockBackend.CONSENT_VERSION_ASSISTANT, granted.version)
        assertFalse(granted.needsRenewal)

        api.revokeAssistantConsent()
        val revoked = api.getProfile().assistantConsent!!
        assertFalse(revoked.granted)
        assertNull(revoked.version)
    }

    @Test
    fun `deleting the account wipes the data and ends the session`() = runBlocking<Unit> {
        backend.load(statement("2026-09-01", "2026-09-30", trn("a", "2026-09-05T12:00", 100, "Krabsbir", "Рестораны")))
        api.grantAssistantConsent(AssistantConsentGrant(MockBackend.CONSENT_VERSION_ASSISTANT))
        MockAssistantApi(backend).ask(IdempotencyKey("q"), ru.finassist.pf.core.api.model.AskRequest("Погода"))
        backend.dismissedPayments += "rp_x"

        api.deleteAccount()
        assertTrue(backend.ledger.all.isEmpty())
        assertTrue(backend.uploads.isEmpty())
        assertTrue(backend.assistant.questions.isEmpty())
        assertTrue(backend.dismissedPayments.isEmpty())
        assertTrue(backend.idempotency.isEmpty())
        assertEquals(ErrorCodes.INVALID_TOKEN, assertFailsWith<AppError.Unauthorized> { api.getProfile() }.code)
        assertFailsWith<AppError.Unauthorized> { MockStatementsApi(backend).listStatements() }
    }

    @Test
    fun `profile requests need a session and the network`() = runBlocking<Unit> {
        val signedOut = MockProfileApi(testBackend(userId = ""))
        assertFailsWith<AppError.Unauthorized> { signedOut.getProfile() }
        assertFailsWith<AppError.Unauthorized> { signedOut.updateProfile(ProfileUpdate(theme = Theme.DARK)) }
        assertFailsWith<AppError.Unauthorized> { signedOut.grantAssistantConsent(AssistantConsentGrant(MockBackend.CONSENT_VERSION_ASSISTANT)) }
        assertFailsWith<AppError.Unauthorized> { signedOut.revokeAssistantConsent() }

        backend.config = backend.config.copy(offline = true)
        assertFailsWith<AppError.Offline> { api.getProfile() }
        assertFailsWith<AppError.Offline> { api.deleteAccount() }
    }
}
