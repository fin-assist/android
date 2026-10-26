package ru.finassist.pf.mock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import ru.finassist.pf.core.api.model.*
import ru.finassist.pf.core.common.error.*
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.mock.api.MockAssistantApi
import ru.finassist.pf.mock.api.MockOperationsApi
import ru.finassist.pf.mock.api.MockProfileApi
import kotlin.test.*

class MockErrorCatalogTest {
    @Test fun `diagnostics and validation details never become presentation`() {
        val backend = testBackend()
        try {
            val detail = AppError.FieldError("id", "INVALID_FORMAT", "secret diagnostic")
            val error = backend.apiError("VALIDATION_ERROR", 400, listOf(detail), message = "secret database diagnostic")
            assertEquals(listOf(detail), error.response?.details)
            assertEquals("Проверьте введённые данные", assertIs<UserMessage.Safe>(error.presentation.userMessage).text)
            assertEquals(false, error.requestRetryable)
            assertFalse(error.presentation.toString().contains("secret"))
        } finally { backend.scope.cancel() }
    }

    @Test fun `unknown severity keeps valid canonical text and invalid canonical cannot use diagnostic`() {
        val backend = testBackend()
        try {
            val unknown = backend.injectedError(MockErrorScenario.UNKNOWN_CODE)
            assertEquals("FUTURE_OPERATION_FAILURE", unknown.rawCode)
            assertEquals(ErrorSeverity.WARNING, unknown.presentation.severity)
            val severity = backend.injectedError(MockErrorScenario.UNKNOWN_SEVERITY)
            assertEquals(unknown.presentation.userMessage, severity.presentation.userMessage)
            assertEquals(ErrorSeverity.ERROR, severity.presentation.severity)
            val invalid = backend.injectedError(MockErrorScenario.INVALID_MESSAGE)
            assertEquals(UserMessage.Invalid, invalid.presentation.userMessage)
            assertEquals("fallback", invalid.presentation.textOrFallback("fallback", "legacy diagnostic"))
            assertEquals(UserMessage.Absent, backend.injectedError(MockErrorScenario.LEGACY_RESPONSE).presentation.userMessage)
        } finally { backend.scope.cancel() }
    }

    @Test fun `targeting leaves profile reads and unrelated operations available and counts only injected failures`() = runBlocking<Unit> {
        val backend = testBackend(config = FAST.copy(errorOperation = MockErrorOperation.PROFILE_UPDATE))
        try {
            val profile = MockProfileApi(backend)
            profile.getProfile()
            MockOperationsApi(backend).listOperations(OperationsQuery())
            assertEquals(0, backend.injectedErrorCount.value)
            repeat(2) { assertFailsWith<AppError.Api> { profile.updateProfile(ProfileUpdate(theme = Theme.DARK)) } }
            assertEquals(2, backend.injectedErrorCount.value)
            assertEquals(Theme.SYSTEM, profile.getProfile().theme)
            backend.config = backend.config.copy(errorOperation = null)
            assertEquals(Theme.DARK, profile.updateProfile(ProfileUpdate(theme = Theme.DARK)).theme)
            assertEquals(2, backend.injectedErrorCount.value)
        } finally { backend.scope.cancel() }
    }

    @Test fun `availability and authentication run before injected failures`() = runBlocking<Unit> {
        val backend = testBackend(config = FAST.copy(errorOperation = MockErrorOperation.OPERATIONS_LIST), userId = "")
        try {
            val api = MockOperationsApi(backend)
            assertFailsWith<AppError.Unauthorized> { api.listOperations(OperationsQuery()) }
            backend.config = backend.config.copy(maintenance = true)
            assertFailsWith<AppError.Maintenance> { api.listOperations(OperationsQuery()) }
            assertEquals(0, backend.injectedErrorCount.value)
        } finally { backend.scope.cancel() }
    }

    @Test fun `request permission stays separate from answer permission and elapsed timing survives wall jumps`() = runBlocking<Unit> {
        var elapsed = 10_000L
        val wall = TestClock(NOW)
        val backend = MockBackend(FAST, wall, Dispatchers.Unconfined, elapsedClock = ElapsedClock { elapsed })
        backend.ready.complete(Unit)
        backend.profile = backend.emptyProfile("+79160000000", "u1")
        try {
            val error = assertIs<AppError.RateLimited>(backend.injectedError(MockErrorScenario.RETRY_FORBIDDEN))
            assertEquals(false, error.requestRetryable)
            val deadline = assertNotNull(error.response).timing.deadline(error.retryAfterSeconds)
            wall.at = wall.at.plusYears(4)
            elapsed += 1_000
            assertEquals(2_000, assertNotNull(deadline).remainingMillis(ElapsedClock { elapsed }))
            elapsed = 0
            assertNull(deadline.remainingMillis(ElapsedClock { elapsed }))
        } finally { backend.scope.cancel() }
    }

    @Test fun `concurrent replay completes one off-topic terminal once`() = runBlocking<Unit> {
        val backend = testBackend(assistantConsent = true)
        try {
            val api = MockAssistantApi(backend)
            val response = api.ask(IdempotencyKey("one"), AskRequest("What is the weather?"))
            withTimeout(10_000) { api.streamAnswer(response.answerId).toList() }
            val replays = (1..20).map { async(Dispatchers.Default) { api.ask(IdempotencyKey("one"), AskRequest("What is the weather?")) } }.awaitAll()
            assertTrue(replays.all { it == response })
            assertEquals(2, api.listMessages().messages.size)
            repeat(4) { index ->
                val next = api.ask(IdempotencyKey("next-$index"), AskRequest("What is the weather?"))
                withTimeout(10_000) { api.streamAnswer(next.answerId).toList() }
            }
            assertFailsWith<AppError.RateLimited> { api.ask(IdempotencyKey("blocked"), AskRequest("Сколько я потратил?")) }
            val failed = api.listMessages().messages.first { it.id == response.answerId }
            assertEquals(ErrorSeverity.WARNING, failed.errorSeverity)
            assertIs<UserMessage.Safe>(failed.errorUserMessage)
            assertEquals(false, failed.retryable)
        } finally { backend.scope.cancel() }
    }
}
