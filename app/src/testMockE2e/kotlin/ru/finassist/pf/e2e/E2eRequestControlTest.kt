package ru.finassist.pf.e2e

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import ru.finassist.pf.core.api.UploadFile
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.Consent
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.StartPhoneVerificationRequest
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.MockConfig
import ru.finassist.pf.mock.MockUiEvidence
import ru.finassist.pf.mock.api.MockAssistantApi
import ru.finassist.pf.mock.api.MockAuthApi
import ru.finassist.pf.mock.api.MockStatementsApi
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class E2eRequestControlTest {
    private val clock = object : Clock {
        override fun now(): OffsetDateTime = OffsetDateTime.parse("2026-10-08T12:00:00+03:00")
        override val zone = Clock.MOSCOW
    }

    private fun backend(config: MockConfig = MockConfig(networkDelayMs = 0, assistantChunkDelayMs = 0)) =
        MockBackend(config, clock).also { it.ready.complete(Unit) }

    private suspend fun withControl(backend: MockBackend = backend(), test: suspend (E2eRequestControl, MockBackend) -> Unit) {
        try { test(E2eRequestControl(backend), backend) } finally { backend.scope.cancel() }
    }

    private fun command(vararg values: Pair<String, String>) = values.associate { it.first to listOf(it.second) }

    @Test fun `closed routing includes only the new stream predicate and preserves the legacy stream namespace`() {
        for (name in listOf("expect_assistant_asks", "expect_statement_upload_requests", "expect_operation_details_requests",
            "expect_category_requests", "expect_phone_ttl_seconds", "expect_maintenance_probes", "expect_analytics_dismiss_requests",
            "expect_stream_reopen_after_deadline")) assertTrue(E2eRequestControl.isParameter(name), name)
        for (name in listOf("expect_stream_rate_limited", "expect_stream_rate_limits", "stream_rate_limits", "assistant_model_errors", "consent_type")) {
            assertEquals(false, E2eRequestControl.isParameter(name), name)
        }
        // An unknown name in an owned namespace must be routed here, then rejected by apply.
        assertTrue(E2eRequestControl.isParameter("expect_assistant_typo"))
    }

    @Test fun `empty checks do nothing and unknown names or duplicate values cannot pass`() = runTest {
        withControl { control, backend ->
            assertNull(control.apply(emptyMap()))
            assertNull(control.apply(command("expect_assistant_asks" to "0", "expect_assistant_questions" to "0")))
            for (parameters in listOf(
                command("expect_assistant_typo" to "0"), command("expect_stream_rate_limited" to "0"),
                command("expect_assistant_asks" to "0", "offline" to "true"),
                mapOf("expect_assistant_asks" to emptyList()),
                mapOf("expect_assistant_asks" to listOf("0", "0")),
                mapOf("expect_assistant_asks" to listOf("0", "1")),
            )) assertNotNull(control.apply(parameters), parameters.toString())
            assertEquals(false, backend.config.offline)
            assertTrue(backend.assistant.questions.isEmpty())
            assertNull(control.apply(command("expect_assistant_asks" to "0")), "Invalid checks must not manufacture evidence")
        }
    }

    @Test fun `count parser rejects malformed negative excessive and oversized values`() = runTest {
        withControl { control, _ ->
            for (value in listOf("", " 0", "0 ", "0.0", "1e0", "true", "-1", "129")) {
                assertEquals("Invalid request count", control.apply(command("expect_assistant_asks" to value)), value)
            }
            for (value in listOf("2147483648", "000000000")) {
                assertEquals("Invalid request evidence", control.apply(command("expect_assistant_asks" to value)), value)
            }
            // PHONE_TTL has a different bound; 300 must parse even though a fresh backend has no phone response.
            assertEquals("Request count mismatch: expect_phone_ttl_seconds", control.apply(command("expect_phone_ttl_seconds" to "300")))
            assertEquals("Request count mismatch: expect_phone_ttl_seconds", control.apply(command("expect_phone_ttl_seconds" to "3600")))
            assertEquals("Invalid request count", control.apply(command("expect_phone_ttl_seconds" to "3601")))
        }
    }

    @Test fun `predicates accept only true and an unobserved condition fails closed`() = runTest {
        withControl { control, _ ->
            for (predicate in MockUiEvidence.Predicate.entries) {
                for (value in listOf("false", "TRUE", "1", "", " true")) {
                    assertEquals("Invalid request predicate", control.apply(command(predicate.control to value)))
                }
                assertEquals("Request predicate unmet: ${predicate.control}", control.apply(command(predicate.control to "true")))
            }
        }
    }

    @Test fun `actual rejected attempts are observed repeatedly and checks never reset evidence`() = runTest {
        withControl(backend(MockConfig(networkDelayMs = 0, offline = true))) { control, backend ->
            val api = MockAssistantApi(backend)
            repeat(2) { assertFailsWith<AppError.Offline> { api.ask(IdempotencyKey("same"), AskRequest("Krabsbir")) } }
            val expected = command("expect_assistant_asks" to "2", "expect_assistant_ask_keys" to "1",
                "expect_assistant_questions" to "0", "expect_assistant_answers" to "0")
            assertNull(control.apply(expected))
            assertNull(control.apply(expected), "Read-only probes must not clear attempts")
            assertEquals("Request count mismatch: expect_assistant_asks", control.apply(command("expect_assistant_asks" to "1")))
            assertNull(control.apply(expected), "A mismatching check must not change observations")
        }
    }

    @Test fun `control counts actual accepted questions current replacement answers and off topics rather than API calls`() = runTest {
        val backend = MockBackend(MockConfig(networkDelayMs = 0, assistantChunkDelayMs = 0, assistantModelErrors = 1), clock,
            dispatcher = StandardTestDispatcher(testScheduler))
        backend.ready.complete(Unit)
        backend.profile = backend.emptyProfile("+79990001122", "synthetic-user").copy(consents = listOf(
            Consent(ConsentType.ASSISTANT, true, MockBackend.CONSENT_VERSION_ASSISTANT, MockBackend.CONSENT_VERSION_ASSISTANT),
        ))
        withControl(backend) { control, _ ->
            val api = MockAssistantApi(backend)
            val first = api.ask(IdempotencyKey("first"), AskRequest("Krabsbir"))
            val failed = withTimeout(10_000) { api.streamAnswer(first.answerId).toList() }.last()
            assertEquals(AnswerErrorCode.MODEL_ERROR, assertIs<AnswerEvent.Error>(failed).event.code)
            assertEquals(first, api.ask(IdempotencyKey("first"), AskRequest("Krabsbir")))
            val replacement = api.retry(IdempotencyKey("retry"), first.answerId)
            withTimeout(10_000) { api.streamAnswer(replacement.answerId).toList() }
            assertEquals(replacement, api.retry(IdempotencyKey("retry"), first.answerId))
            val weather = api.ask(IdempotencyKey("weather"), AskRequest("What is the weather today?"))
            val offTopic = withTimeout(10_000) { api.streamAnswer(weather.answerId).toList() }.last()
            assertEquals(AnswerErrorCode.OFF_TOPIC, assertIs<AnswerEvent.Error>(offTopic).event.code)
            assertEquals(3, backend.assistant.answers.size, "Replaced failed answer still exists in the store")
            val expected = command("expect_assistant_asks" to "3", "expect_assistant_ask_keys" to "2",
                "expect_assistant_retries" to "2", "expect_assistant_retry_keys" to "1", "expect_assistant_retry_targets" to "1",
                "expect_assistant_questions" to "2", "expect_assistant_answers" to "2", "expect_assistant_off_topics" to "1")
            assertNull(control.apply(expected))
            assertNotNull(control.apply(command("expect_assistant_answers" to "3")), "Replaced answers are not current answers")
            assertNotNull(control.apply(command("expect_assistant_questions" to "3")), "An idempotent replay is not a new question")
            assertNotNull(control.apply(command("expect_assistant_off_topics" to "0")))
            assertNull(control.apply(expected))
        }
    }

    @Test fun `bounded attempts accept 128 but overflow invalidates even an unrelated otherwise matching check`() = runTest {
        withControl(backend(MockConfig(networkDelayMs = 0, offline = true))) { control, backend ->
            val api = MockAssistantApi(backend)
            repeat(128) { assertFailsWith<AppError.Offline> { api.ask(IdempotencyKey("same"), AskRequest("Krabsbir")) } }
            assertNull(control.apply(command("expect_assistant_asks" to "128", "expect_assistant_ask_keys" to "1")))
            assertFailsWith<AppError.Offline> { api.ask(IdempotencyKey("same"), AskRequest("Krabsbir")) }
            assertEquals("Request evidence limit exceeded", control.apply(command("expect_assistant_questions" to "0")))
            assertEquals("Request evidence limit exceeded", control.apply(command("expect_assistant_asks" to "128")))
        }
    }

    @Test fun `phone control checks real configured TTL independently of the expected scenario constant`() = runTest {
        withControl(backend(MockConfig(networkDelayMs = 0, verificationTtlMs = 120_000))) { control, backend ->
            MockAuthApi(backend).startPhoneVerification(IdempotencyKey("phone"),
                StartPhoneVerificationRequest("+79990001122", true, MockBackend.CONSENT_VERSION_PD, true))
            assertNull(control.apply(command("expect_phone_ttl_seconds" to "120")))
            assertEquals("Request count mismatch: expect_phone_ttl_seconds", control.apply(command("expect_phone_ttl_seconds" to "300")))
        }
    }

    @Test fun `upload predicates require a seed a persisted-key observation and a distinct actual upload attempt`() = runTest {
        withControl(backend(MockConfig(networkDelayMs = 0, offline = true))) { control, backend ->
            val retained = command("expect_statement_pending_key_retained" to "true")
            val fresh = command("expect_statement_upload_key_fresh" to "true")
            backend.uiEvidence.restoredUploadKey("restored-synthetic-key")
            assertNotNull(control.apply(retained), "Seeding alone cannot claim the persisted key is still present")
            assertNotNull(control.apply(fresh), "Seeding alone proves no new upload key")
            backend.uiEvidence.retainedUploadKey("restored-synthetic-key")
            assertNull(control.apply(retained))
            val api = MockStatementsApi(backend)
            val file = UploadFile("synthetic.ofx", 1) { error("Offline upload must not open content") }
            assertFailsWith<AppError.Offline> { api.uploadStatement(IdempotencyKey("fresh-synthetic-key"), file) }
            assertNull(control.apply(fresh))
            backend.uiEvidence.retainedUploadKey(null)
            assertNotNull(control.apply(retained), "A later missing record must fail instead of keeping the old observation")
            assertFailsWith<AppError.Offline> { api.uploadStatement(IdempotencyKey("restored-synthetic-key"), file) }
            assertNotNull(control.apply(fresh), "Posting the abandoned key invalidates freshness even after an earlier fresh key")
        }
    }

    @Test fun `malformed evidence failures do not echo opaque input or values`() = runTest {
        withControl { control, _ ->
            val sentinel = "synthetic-secret-sentinel"
            assertEquals("Unknown request evidence", control.apply(command("expect_assistant_$sentinel" to "0")))
            assertEquals("Invalid request evidence", control.apply(command("expect_assistant_asks" to sentinel)))
            assertEquals("Invalid request predicate", control.apply(command("expect_phone_fresh_deadline" to "secret")))
        }
    }

    @Test fun `phone wait control rejects unseen malformed and wrong labels and accepts only latest API response time`() = runTest {
        withControl { control, backend ->
            assertTrue(E2eRequestControl.isParameter("expect_phone_wait_hhmm"))
            for (invalid in listOf("", "1:00", "24:00", "23:60", "12:00 ", "true", "12:00\n")) {
                assertEquals("Invalid phone wait label", control.apply(command("expect_phone_wait_hhmm" to invalid)), invalid)
            }
            assertEquals("Phone wait label mismatch", control.apply(command("expect_phone_wait_hhmm" to "12:00")))
            val response = MockAuthApi(backend).startPhoneVerification(IdempotencyKey("phone-time"),
                StartPhoneVerificationRequest("+79990001122", true, MockBackend.CONSENT_VERSION_PD, true))
            val expiry = response.expiresAt.atZoneSameInstant(ZoneId.systemDefault())
            val format = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
            assertNull(control.apply(command("expect_phone_wait_hhmm" to expiry.format(format))))
            assertEquals("Phone wait label mismatch", control.apply(command("expect_phone_wait_hhmm" to expiry.plusMinutes(2).format(format))))
            assertNotNull(control.apply(mapOf("expect_phone_wait_hhmm" to listOf(expiry.format(format), expiry.format(format)))))
        }
    }
}
