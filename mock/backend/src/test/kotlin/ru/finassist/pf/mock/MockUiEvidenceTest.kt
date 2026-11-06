package ru.finassist.pf.mock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.mock.MockUiEvidence.Metric
import ru.finassist.pf.mock.MockUiEvidence.Predicate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** These witnesses must reject false acceptance, including missing observations and earlier violations. */
class MockUiEvidenceTest {
    private fun MockUiEvidence.snapshot() = snapshot(questions = 0, answers = 0, offTopics = 0)
    private fun MockUiEvidence.predicate(value: Predicate) = snapshot().predicates.getValue(value)

    @Test fun `no observation is never evidence of a successful predicate`() {
        val evidence = MockUiEvidence()
        assertTrue(evidence.snapshot().predicates.values.none { it })
        evidence.detailRequest()
        evidence.categoryRequest()
        evidence.personalDataRead(10_000)
        evidence.streamOpening(10_000)
        evidence.retainedUploadKey("unseeded-key")
        assertTrue(evidence.snapshot().predicates.values.none { it })
        assertEquals(0, evidence.snapshot().counts.getValue(Metric.MAINTENANCE_PROBES))
    }

    @Test fun `attempt count differs from distinct keys and targets and actual history`() {
        val evidence = MockUiEvidence()
        evidence.ask("ask-a"); evidence.ask("ask-a"); evidence.ask("ask-b")
        evidence.retry("retry-a", "answer-a"); evidence.retry("retry-a", "answer-a")
        evidence.retry("retry-b", "answer-a"); evidence.retry("retry-b", "answer-b")
        evidence.upload("upload-a"); evidence.upload("upload-a"); evidence.upload("upload-b")
        evidence.delete(); evidence.delete(); evidence.dismiss()
        val snapshot = evidence.snapshot(questions = 1, answers = 1, offTopics = 0)
        assertEquals(3, snapshot.counts.getValue(Metric.ASKS))
        assertEquals(2, snapshot.counts.getValue(Metric.ASK_KEYS))
        assertEquals(4, snapshot.counts.getValue(Metric.RETRIES))
        assertEquals(2, snapshot.counts.getValue(Metric.RETRY_KEYS))
        assertEquals(2, snapshot.counts.getValue(Metric.RETRY_TARGETS))
        assertEquals(3, snapshot.counts.getValue(Metric.UPLOADS))
        assertEquals(2, snapshot.counts.getValue(Metric.UPLOAD_KEYS))
        assertEquals(2, snapshot.counts.getValue(Metric.DELETES))
        assertEquals(1, snapshot.counts.getValue(Metric.DISMISSALS))
        assertEquals(1, snapshot.counts.getValue(Metric.QUESTIONS))
        assertEquals(1, snapshot.counts.getValue(Metric.ANSWERS))
        assertEquals(0, snapshot.counts.getValue(Metric.OFF_TOPICS))
        assertFalse(snapshot.overflow)
    }

    @Test fun `parallel attempts do not lose updates or mistake shared keys for separate keys`() = runBlocking {
        val evidence = MockUiEvidence()
        List(64) { index -> launch(Dispatchers.Default) {
            repeat(2) { evidence.ask("key-$index"); evidence.retry("retry-$index", "one-target") }
        } }.joinAll()
        val snapshot = evidence.snapshot()
        assertEquals(128, snapshot.counts.getValue(Metric.ASKS))
        assertEquals(64, snapshot.counts.getValue(Metric.ASK_KEYS))
        assertEquals(128, snapshot.counts.getValue(Metric.RETRIES))
        assertEquals(64, snapshot.counts.getValue(Metric.RETRY_KEYS))
        assertEquals(1, snapshot.counts.getValue(Metric.RETRY_TARGETS))
        assertFalse(snapshot.overflow)
    }

    @Test fun `128 entries are allowed but excess evidence is bounded and permanently invalid`() {
        val evidence = MockUiEvidence()
        repeat(128) { evidence.ask("ask-$it"); evidence.retry("retry-$it", "answer-$it"); evidence.upload("upload-$it") }
        assertFalse(evidence.snapshot().overflow)
        repeat(500) { evidence.ask("excess-$it"); evidence.retry("excess-$it", "target-$it"); evidence.upload("excess-$it") }
        val snapshot = evidence.snapshot()
        for (metric in listOf(Metric.ASKS, Metric.RETRIES, Metric.UPLOADS)) assertEquals(129, snapshot.counts.getValue(metric))
        for (metric in listOf(Metric.ASK_KEYS, Metric.RETRY_KEYS, Metric.RETRY_TARGETS, Metric.UPLOAD_KEYS)) {
            assertEquals(128, snapshot.counts.getValue(metric))
        }
        assertTrue(snapshot.overflow)
        assertTrue(evidence.snapshot().overflow, "Taking another snapshot must not clear overflow")
        assertTrue(MockUiEvidence().snapshot(129, 0, 0).overflow)
        assertTrue(MockUiEvidence().snapshot(0, 129, 0).overflow)
        assertTrue(MockUiEvidence().snapshot(0, 0, 129).overflow)
    }

    @Test fun `oversized opaque keys or targets fail closed at the independent 256 character boundary`() {
        val allowed = "a".repeat(256)
        val oversized = "a".repeat(257)
        val valid = MockUiEvidence().apply { ask(allowed); retry(allowed, allowed); upload(allowed) }
        assertFalse(valid.snapshot().overflow)
        for (record in listOf<(MockUiEvidence) -> Unit>(
            { it.ask(oversized) }, { it.retry(oversized, "target") },
            { it.retry("key", oversized) }, { it.upload(oversized) },
        )) {
            assertTrue(MockUiEvidence().apply(record).snapshot().overflow)
        }
    }

    @Test fun `timestamp equality preserves offset and fractional seconds rather than only the instant`() {
        val exact = "2026-09-15T12:34:56.123456789+03:00"
        for (altered in listOf("2026-09-15T09:34:56.123456789Z", "2026-09-15T12:34:56+03:00")) {
            val evidence = MockUiEvidence()
            evidence.detailRequest(); evidence.details("operation", altered, exact)
            assertFalse(evidence.predicate(Predicate.DETAILS_EXACT), altered)
            evidence.detailRequest(); evidence.details("operation", exact, exact)
            assertFalse(evidence.predicate(Predicate.DETAILS_EXACT), "A later exact request cannot erase an earlier mismatch")
        }
        val valid = MockUiEvidence().apply { detailRequest(); details("operation", exact, exact) }
        assertTrue(valid.predicate(Predicate.DETAILS_EXACT))
        valid.detailRequest()
        assertFalse(valid.predicate(Predicate.DETAILS_EXACT), "An unsuccessful details request cannot inherit earlier evidence")
    }

    @Test fun `category timestamp must come from latest details of the same operation`() {
        val timestamp = "2026-09-15T12:34:56.123+03:00"
        val valid = MockUiEvidence().apply {
            detailRequest(); details("first", timestamp, timestamp)
            categoryRequest(); category("first", timestamp)
        }
        assertTrue(valid.predicate(Predicate.CATEGORY_EXACT))
        valid.categoryRequest()
        assertFalse(valid.predicate(Predicate.CATEGORY_EXACT), "A request without a checked target is not evidence")
        for ((id, requested) in listOf("other" to timestamp, "first" to "2026-09-15T09:34:56.123Z")) {
            val evidence = MockUiEvidence().apply {
                detailRequest(); details("first", timestamp, timestamp)
                categoryRequest(); category(id, requested)
            }
            assertFalse(evidence.predicate(Predicate.CATEGORY_EXACT))
            evidence.categoryRequest(); evidence.category("first", timestamp)
            assertFalse(evidence.predicate(Predicate.CATEGORY_EXACT), "Later valid PATCH must not erase a mismatch")
        }
        val refreshed = MockUiEvidence().apply {
            detailRequest(); details("first", timestamp, timestamp)
            detailRequest(); details("first", "2026-09-16T12:00:00+03:00", "2026-09-16T12:00:00+03:00")
            categoryRequest(); category("first", timestamp)
        }
        assertFalse(refreshed.predicate(Predicate.CATEGORY_EXACT), "Older details for the same ID must not authorize the timestamp")
    }

    @Test fun `timestamp observations are bounded even for repeated IDs`() {
        for (record in listOf<(MockUiEvidence) -> Unit>(
            { it.detailRequest(); it.details("same-id", "time", "time") },
            { it.categoryRequest(); it.category("same-id", "time") },
        )) {
            val evidence = MockUiEvidence()
            repeat(128) { record(evidence) }
            assertFalse(evidence.snapshot().overflow)
            record(evidence)
            assertTrue(evidence.snapshot().overflow)
        }
        for ((id, returned) in listOf("i".repeat(257) to "time", "id" to "t".repeat(257))) {
            assertTrue(MockUiEvidence().apply { detailRequest(); details(id, returned, returned) }.snapshot().overflow)
        }
    }

    @Test fun `phone evidence distinguishes a fresh deadline from replay and preserves the actual TTL`() {
        val evidence = MockUiEvidence()
        evidence.phoneResult(300, 500_000)
        assertEquals(300, evidence.snapshot().counts.getValue(Metric.PHONE_TTL))
        assertFalse(evidence.predicate(Predicate.PHONE_FRESH), "A first response is not a renewed deadline")
        evidence.phoneResult(300, 500_000)
        assertFalse(evidence.predicate(Predicate.PHONE_FRESH), "Idempotent replay cannot pass renewal")
        evidence.phoneResult(300, 500_001)
        assertTrue(evidence.predicate(Predicate.PHONE_FRESH), "A strictly newer deadline is required")
        evidence.phoneResult(120, 500_000)
        assertFalse(evidence.predicate(Predicate.PHONE_FRESH), "A stale response must fail again")
        assertEquals(120, evidence.snapshot().counts.getValue(Metric.PHONE_TTL), "The witness must not hard-code the expected 300")
        val bounded = MockUiEvidence()
        repeat(128) { bounded.phoneResult(300, it.toLong()) }
        assertFalse(bounded.snapshot().overflow)
        bounded.phoneResult(300, 129)
        assertTrue(bounded.snapshot().overflow)
    }

    @Test fun `phone wait labels are strict bounded and replaced by the latest actual response`() {
        val evidence = MockUiEvidence()
        assertFalse(evidence.phoneWaitMatches("23:59"), "No phone response has been observed")
        evidence.phoneResult(300, 500_000, setOf("23:59", "00:00"))
        assertTrue(evidence.phoneWaitMatches("23:59"))
        assertTrue(evidence.phoneWaitMatches("00:00"))
        assertFalse(evidence.phoneWaitMatches("00:01"), "An arbitrary plausible clock label is not evidence")
        for (invalid in listOf("1:00", "24:00", "23:60", " 23:59", "23:59 ", "23:59\n")) {
            assertFalse(evidence.phoneWaitMatches(invalid), invalid)
        }
        evidence.phoneResult(300, 600_000, setOf("12:00", "12:01", "12:02", "invalid"))
        assertTrue(evidence.phoneWaitMatches("12:00"))
        assertTrue(evidence.phoneWaitMatches("12:01"))
        assertFalse(evidence.phoneWaitMatches("12:02"), "Only the bounded two-label handoff interval is allowed")
        assertFalse(evidence.phoneWaitMatches("23:59"), "Earlier response labels cannot authorize the current screen")
        evidence.phoneResult(300, 700_000)
        assertFalse(evidence.phoneWaitMatches("12:00"), "Missing labels must not retain old evidence")
    }

    @Test fun `maintenance probe requires an actual read at or after the latest refusal deadline`() {
        val evidence = MockUiEvidence()
        evidence.maintenanceRefusal(1_000, 3)
        assertFalse(evidence.predicate(Predicate.MAINTENANCE_NOT_EARLY), "A refusal alone proves no probe")
        evidence.maintenanceRefusal(3_000, 3)
        evidence.personalDataRead(6_000)
        assertTrue(evidence.predicate(Predicate.MAINTENANCE_NOT_EARLY), "Inclusive latest deadline must pass")
        assertEquals(1, evidence.snapshot().counts.getValue(Metric.MAINTENANCE_PROBES))
        val early = MockUiEvidence().apply {
            maintenanceRefusal(1_000, 3); maintenanceRefusal(3_000, 3); personalDataRead(5_999)
        }
        assertFalse(early.predicate(Predicate.MAINTENANCE_NOT_EARLY))
        early.personalDataRead(6_000)
        assertFalse(early.predicate(Predicate.MAINTENANCE_NOT_EARLY), "Later eligible reads must not hide an early probe")
        for (missingReceipt in listOf(true, false)) {
            val missingClock = MockUiEvidence().apply {
                maintenanceRefusal(if (missingReceipt) null else 1_000, 3)
                personalDataRead(if (missingReceipt) 5_000 else null)
            }
            assertFalse(missingClock.predicate(Predicate.MAINTENANCE_NOT_EARLY))
        }
    }

    @Test fun `stream reopening observes latest deadline and early violations cannot be reset by another refusal`() {
        val valid = MockUiEvidence().apply { streamRefusal(1_000, 3) }
        assertFalse(valid.predicate(Predicate.STREAM_NOT_EARLY), "No observed reopen")
        valid.streamOpening(4_000)
        assertTrue(valid.predicate(Predicate.STREAM_NOT_EARLY))
        valid.streamRefusal(4_000, 3)
        valid.streamOpening(6_999)
        assertFalse(valid.predicate(Predicate.STREAM_NOT_EARLY), "Original deadline is insufficient after another 429")
        valid.streamRefusal(7_000, 3); valid.streamOpening(10_000)
        assertFalse(valid.predicate(Predicate.STREAM_NOT_EARLY), "A new refusal cannot reset earlier misconduct")
        for (missingReceipt in listOf(true, false)) {
            val missingClock = MockUiEvidence().apply {
                streamRefusal(if (missingReceipt) null else 1_000, 3)
                streamOpening(if (missingReceipt) 5_000 else null)
            }
            assertFalse(missingClock.predicate(Predicate.STREAM_NOT_EARLY))
        }
        val bounded = MockUiEvidence().apply { streamRefusal(0, 1) }
        repeat(128) { bounded.streamOpening(1_000) }
        assertFalse(bounded.snapshot().overflow)
        bounded.streamOpening(1_000)
        assertTrue(bounded.snapshot().overflow)
    }

    @Test fun `restored upload key requires independent retention observation and a distinct posted key`() {
        val evidence = MockUiEvidence()
        evidence.restoredUploadKey("restored")
        assertFalse(evidence.predicate(Predicate.UPLOAD_KEY_RETAINED))
        assertFalse(evidence.predicate(Predicate.UPLOAD_KEY_FRESH))
        evidence.retainedUploadKey(null)
        assertFalse(evidence.predicate(Predicate.UPLOAD_KEY_RETAINED))
        evidence.retainedUploadKey("different")
        assertFalse(evidence.predicate(Predicate.UPLOAD_KEY_RETAINED))
        evidence.retainedUploadKey("restored")
        assertTrue(evidence.predicate(Predicate.UPLOAD_KEY_RETAINED))
        evidence.upload("new")
        assertTrue(evidence.predicate(Predicate.UPLOAD_KEY_FRESH))
        evidence.upload("restored")
        assertFalse(evidence.predicate(Predicate.UPLOAD_KEY_FRESH))
        evidence.upload("even-newer")
        assertFalse(evidence.predicate(Predicate.UPLOAD_KEY_FRESH), "A later fresh key cannot hide replaying the abandoned key")
        evidence.retainedUploadKey(null)
        assertFalse(evidence.predicate(Predicate.UPLOAD_KEY_RETAINED), "Current missing persisted state must not inherit old success")
        assertFalse(MockUiEvidence().apply { upload("new") }.predicate(Predicate.UPLOAD_KEY_FRESH), "Seed missing")
        assertTrue(MockUiEvidence().apply { restoredUploadKey("x".repeat(257)) }.snapshot().overflow)
        assertTrue(MockUiEvidence().apply { restoredUploadKey("one"); restoredUploadKey("two") }.snapshot().overflow)
    }
}
