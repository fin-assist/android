package ru.finassist.pf.mock

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import ru.finassist.pf.core.api.UploadFile
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.AnswerErrorEvent
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.Message
import ru.finassist.pf.core.api.model.MessageRole
import ru.finassist.pf.core.api.model.StartPhoneVerificationRequest
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.MockUiEvidence.Metric
import ru.finassist.pf.mock.MockUiEvidence.Predicate
import ru.finassist.pf.mock.api.MockAnalyticsApi
import ru.finassist.pf.mock.api.MockAssistantApi
import ru.finassist.pf.mock.api.MockAuthApi
import ru.finassist.pf.mock.api.MockOperationsApi
import ru.finassist.pf.mock.api.MockStatementsApi
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real mock API entry points must observe rejected intents, not just committed results. */
class MockUiEvidenceApiTest {
    private suspend fun withBackend(backend: MockBackend = testBackend(), test: suspend MockBackend.() -> Unit) {
        try { backend.test() } finally { backend.scope.cancel() }
    }

    private fun MockBackend.snapshot() = uiEvidence.snapshot(
        assistant.questions.size, assistant.answers.values.count { !it.replaced },
        assistant.answers.values.count { !it.replaced && it.errorCode == AnswerErrorCode.OFF_TOPIC },
    )

    @Test fun `offline method attempts are counted before networking without committing user data`() = runTest {
        withBackend(testBackend(config = FAST.copy(offline = true))) {
            val assistantApi = MockAssistantApi(this)
            val statementsApi = MockStatementsApi(this)
            val operationsApi = MockOperationsApi(this)
            val file = UploadFile("synthetic.ofx", 1) { error("Offline upload must not read the file") }
            assertFailsWith<AppError.Offline> { assistantApi.ask(IdempotencyKey("ask"), AskRequest("Krabsbir")) }
            assertFailsWith<AppError.Offline> { assistantApi.retry(IdempotencyKey("retry"), "answer") }
            assertFailsWith<AppError.Offline> { statementsApi.uploadStatement(IdempotencyKey("upload"), file) }
            assertFailsWith<AppError.Offline> { statementsApi.deleteStatement("upload") }
            assertFailsWith<AppError.Offline> { operationsApi.getOperation("operation", "2026-09-01T12:00:00+03:00") }
            assertFailsWith<AppError.Offline> { operationsApi.changeCategory("operation", ChangeCategoryRequest("cat_taxi", "2026-09-01T12:00:00+03:00")) }
            assertFailsWith<AppError.Offline> { MockAnalyticsApi(this).dismissRegularPayment("payment", PeriodKey("2026-09"), TransferMode.WITH) }
            val snapshot = snapshot()
            for (metric in listOf(Metric.ASKS, Metric.ASK_KEYS, Metric.RETRIES, Metric.RETRY_KEYS, Metric.RETRY_TARGETS,
                Metric.UPLOADS, Metric.UPLOAD_KEYS, Metric.DELETES, Metric.DETAILS, Metric.CATEGORIES, Metric.DISMISSALS)) {
                assertEquals(1, snapshot.counts.getValue(metric), metric.name)
            }
            assertEquals(0, snapshot.counts.getValue(Metric.QUESTIONS))
            assertEquals(0, snapshot.counts.getValue(Metric.ANSWERS))
            assertFalse(snapshot.predicates.getValue(Predicate.DETAILS_EXACT))
            assertFalse(snapshot.predicates.getValue(Predicate.CATEGORY_EXACT))
            assertTrue(uploads.isEmpty())
            assertTrue(ledger.all.isEmpty())
        }
    }

    @Test fun `consent denied Ask and Retry keep attempted route and key without new history`() = runTest {
        withBackend {
            val question = Message("question", MessageRole.USER, "Krabsbir", createdAt = now())
            assistant.questions += question
            assistant.answers["failed"] = AssistantStore.Answer("failed", question.id, now()).apply {
                append(AnswerEvent.Error(AnswerErrorEvent(AnswerErrorCode.MODEL_ERROR, "model failed", 5, retryable = true)))
            }
            val api = MockAssistantApi(this)
            repeat(2) {
                assertEquals(ErrorCodes.CONSENT_REQUIRED, assertFailsWith<AppError.Api> {
                    api.ask(IdempotencyKey("same-ask"), AskRequest("Krabsbir"))
                }.code)
                assertEquals(ErrorCodes.CONSENT_REQUIRED, assertFailsWith<AppError.Api> {
                    api.retry(IdempotencyKey("same-retry"), "failed")
                }.code)
            }
            val snapshot = snapshot()
            assertEquals(2, snapshot.counts.getValue(Metric.ASKS))
            assertEquals(2, snapshot.counts.getValue(Metric.RETRIES))
            assertEquals(1, snapshot.counts.getValue(Metric.ASK_KEYS))
            assertEquals(1, snapshot.counts.getValue(Metric.RETRY_KEYS))
            assertEquals(1, snapshot.counts.getValue(Metric.RETRY_TARGETS))
            assertEquals(listOf("question"), assistant.questions.map { it.id })
            assertEquals(listOf("failed"), assistant.answers.keys.toList())
            assertFalse(assistant.answers.getValue("failed").replaced)
        }
    }

    @Test fun `actual operation GET and PATCH preserve offset and subsecond evidence`() = runTest {
        withBackend {
            val timestamp = "2026-09-15T12:34:56.123456789+05:30"
            load(statement("2026-09-01", "2026-09-30",
                trn("precise", "2026-09-15T12:34", 100, "Synthetic shop", "Рестораны")
                    .copy(postedAt = OffsetDateTime.parse(timestamp))))
            val id = ledger.all.single().id
            val api = MockOperationsApi(this)
            val details = api.getOperation(id, timestamp)
            assertEquals(timestamp, details.occurredAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            api.changeCategory(id, ChangeCategoryRequest("cat_taxi", timestamp))
            assertTrue(snapshot().predicates.getValue(Predicate.DETAILS_EXACT))
            assertTrue(snapshot().predicates.getValue(Predicate.CATEGORY_EXACT))
            // Same instant is insufficient: the wire value must remain the one from details.
            api.changeCategory(id, ChangeCategoryRequest("cat_restaurants", "2026-09-15T07:04:56.123456789Z"))
            assertFalse(snapshot().predicates.getValue(Predicate.CATEGORY_EXACT))
        }
    }

    @Test fun `phone witness reports server TTL and rejects replay as a fresh deadline`() = runTest {
        val clock = TestClock(NOW)
        withBackend(testBackend(clock)) {
            val api = MockAuthApi(this)
            val body = StartPhoneVerificationRequest("+79990001122", true, MockBackend.CONSENT_VERSION_PD, true)
            val first = api.startPhoneVerification(IdempotencyKey("first"), body)
            assertEquals(300, first.expiresIn)
            assertEquals(300, snapshot().counts.getValue(Metric.PHONE_TTL))
            assertFalse(snapshot().predicates.getValue(Predicate.PHONE_FRESH))
            clock.at = clock.at.plusSeconds(1)
            val replay = api.startPhoneVerification(IdempotencyKey("first"), body)
            assertEquals(first.expiresAt, replay.expiresAt)
            assertEquals(299, replay.expiresIn)
            assertFalse(snapshot().predicates.getValue(Predicate.PHONE_FRESH))
            val fresh = api.startPhoneVerification(IdempotencyKey("second"), body)
            assertTrue(fresh.expiresAt.isAfter(first.expiresAt))
            assertEquals(300, fresh.expiresIn)
            assertEquals(300, snapshot().counts.getValue(Metric.PHONE_TTL))
            assertTrue(snapshot().predicates.getValue(Predicate.PHONE_FRESH))
        }
    }

    @Test fun `actual phone response permits only its device-zone minute and one-second handoff across a minute`() = runTest {
        val clock = TestClock("2026-10-06T12:00:59.500+03:00")
        withBackend(testBackend(clock)) {
            val body = StartPhoneVerificationRequest("+79990001122", true, MockBackend.CONSENT_VERSION_PD, true)
            val response = MockAuthApi(this).startPhoneVerification(IdempotencyKey("phone-label"), body)
            val expiry = response.expiresAt.atZoneSameInstant(ZoneId.systemDefault())
            val format = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
            assertTrue(uiEvidence.phoneWaitMatches(expiry.format(format)))
            assertTrue(uiEvidence.phoneWaitMatches(expiry.plusSeconds(1).format(format)))
            assertFalse(uiEvidence.phoneWaitMatches(expiry.minusMinutes(1).format(format)))
            assertFalse(uiEvidence.phoneWaitMatches(expiry.plusMinutes(2).format(format)))
        }
    }

    @Test fun `maintenance evidence is wired to actual document read and latest refusal receipt`() = runTest {
        var elapsed = 1_000L
        val backend = MockBackend(FAST.copy(maintenance = true, maintenanceRetryAfterSeconds = 3), TestClock(NOW),
            elapsedClock = ElapsedClock { elapsed })
        backend.ready.complete(Unit)
        withBackend(backend) {
            val api = MockAuthApi(this)
            assertFailsWith<AppError.Maintenance> { api.getConsentDocument(ConsentType.PERSONAL_DATA) }
            assertFalse(snapshot().predicates.getValue(Predicate.MAINTENANCE_NOT_EARLY))
            elapsed = 4_000
            // At the first deadline another actual 503 starts a new three-second wait.
            assertFailsWith<AppError.Maintenance> { api.getConsentDocument(ConsentType.PERSONAL_DATA) }
            config = config.copy(maintenance = false)
            elapsed = 6_999
            api.getConsentDocument(ConsentType.PERSONAL_DATA)
            assertFalse(snapshot().predicates.getValue(Predicate.MAINTENANCE_NOT_EARLY))
            elapsed = 7_000
            api.getConsentDocument(ConsentType.PERSONAL_DATA)
            assertEquals(3, snapshot().counts.getValue(Metric.MAINTENANCE_PROBES))
            assertFalse(snapshot().predicates.getValue(Predicate.MAINTENANCE_NOT_EARLY), "Later success must not erase an early read")
        }
    }

    @Test fun `actual stream collection observes early reopen whether it returns 429 or succeeds`() = runTest {
        for (rateLimits in listOf(1, 2)) {
            var elapsed = 1_000L
            val backend = MockBackend(FAST.copy(streamDrops = 1, streamRateLimits = rateLimits, streamRetrySeconds = 3),
                TestClock(NOW), elapsedClock = ElapsedClock { elapsed })
            backend.ready.complete(Unit)
            withBackend(backend) {
                val stream = droppable(flowOf(1, 2)) { it == 2 }
                assertFalse(snapshot().predicates.getValue(Predicate.STREAM_NOT_EARLY), "Constructing Flow is not a collection")
                assertEquals(listOf(1), withTimeout(10_000) { stream.toList() })
                assertFailsWith<AppError.RateLimited> { stream.toList() }
                elapsed = 3_999
                if (rateLimits == 2) assertFailsWith<AppError.RateLimited> { stream.toList() }
                else assertEquals(listOf(1, 2), stream.toList())
                assertFalse(snapshot().predicates.getValue(Predicate.STREAM_NOT_EARLY), "Early collection must be observed for outcome $rateLimits")
                elapsed = 7_000
                assertEquals(listOf(1, 2), stream.toList())
                assertFalse(snapshot().predicates.getValue(Predicate.STREAM_NOT_EARLY), "Removing 429s must not reset an early reopen")
            }
        }
    }
}
