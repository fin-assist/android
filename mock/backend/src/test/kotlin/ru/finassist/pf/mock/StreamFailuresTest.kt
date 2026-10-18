package ru.finassist.pf.mock

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.UploadFile
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.VerificationStatus
import ru.finassist.pf.core.api.reopeningStream
import ru.finassist.pf.mock.api.MockAssistantApi
import ru.finassist.pf.mock.api.MockAuthApi
import ru.finassist.pf.mock.api.MockStatementsApi
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Failures of the fake server's streams (FIN-170): the 3-minute limits of a parse (api.md 3.3) and of an answer
 * (7.2), and a stream closed without a terminal event (api.md «SSE», [MockConfig.streamDrops]). Virtual time.
 */
class StreamFailuresTest {
    private val clock = TestClock(NOW)

    private val ofx = """
        <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>RUB</CURDEF>
        <BANKACCTFROM><BANKID>T-BANK</BANKID><ACCTID>40817810000000001111</ACCTID><ACCTTYPE>CHECKING</ACCTTYPE></BANKACCTFROM>
        <BANKTRANLIST><DTSTART>20260901000000</DTSTART><DTEND>20260930000000</DTEND>
        <STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20260905120000</DTPOSTED><TRNAMT>-100.00</TRNAMT><FITID>1</FITID><NAME>Магазин</NAME><MEMO>Супермаркеты</MEMO></STMTTRN>
        </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
    """.trimIndent()

    private fun file() = UploadFile("sept.ofx", ofx.toByteArray().size.toLong()) { ofx.byteInputStream() }

    private val doc = statement(
        "2026-04-01", "2026-09-30",
        *(4..9).map { m -> trn("k$m", "2026-%02d-15T12:00".format(m), 100L * m, "Krabsbir", "Рестораны") }.toTypedArray(),
    )

    // ---- 3.3: the parse limit ----

    @Test
    fun `a parse not finished in 3 minutes ends with PROCESSING_FAILED and leaves nothing behind`() = runTest {
        // Three stages of 100 s each: the parse would end at 300 s.
        val backend = testBackend(clock, config = FAST.copy(importStepDelayMs = 100_000), dispatcher = StandardTestDispatcher(testScheduler))
        val api = MockStatementsApi(backend)
        val accepted = api.uploadStatement(IdempotencyKey("k1"), file())

        val events = api.streamProgress(accepted.uploadId).toList()

        assertEquals(listOf(20, 55), events.dropLast(1).map { assertIs<ImportEvent.Progress>(it).progress.percent }.takeLast(2), "parsing and dedup, no rules")
        assertEquals(ErrorCodes.PROCESSING_FAILED, assertIs<ImportEvent.Error>(events.last()).code)
        assertEquals(180_000, testScheduler.currentTime, "failed at the 3-minute mark, not when the work would end")
        assertTrue(backend.ledger.all.isEmpty(), "the import is atomic")
        assertTrue(api.listStatements().uploads.isEmpty(), "a failed upload is not in the history")
        // A re-opened stream gets the same outcome at once (api.md 3.3).
        assertEquals(listOf(events.last()), api.streamProgress(accepted.uploadId).toList())
    }

    @Test
    fun `a parse within the limit is not cut`() = runTest {
        val backend = testBackend(clock, config = FAST.copy(importStepDelayMs = 50_000), dispatcher = StandardTestDispatcher(testScheduler))
        val api = MockStatementsApi(backend)
        val accepted = api.uploadStatement(IdempotencyKey("k1"), file())

        val events = api.streamProgress(accepted.uploadId).toList()

        assertIs<ImportEvent.Complete>(events.last())
        assertEquals(1, backend.ledger.all.size)
    }

    @Test
    fun `the parse limit is a failure, not a cancel by the user`() = runTest {
        val backend = testBackend(clock, config = FAST.copy(importStepDelayMs = 100_000, importTimeoutMs = 1_000), dispatcher = StandardTestDispatcher(testScheduler))
        val api = MockStatementsApi(backend)
        val accepted = api.uploadStatement(IdempotencyKey("k1"), file())

        val error = assertIs<ImportEvent.Error>(api.streamProgress(accepted.uploadId).toList().last())

        assertEquals(ErrorCodes.PROCESSING_FAILED, error.code)
        assertEquals(1_000, testScheduler.currentTime)
    }

    // ---- 7.2: the answer limit ----

    @Test
    fun `an answer not finished in 3 minutes fails with MODEL_ERROR, retryable and refunded`() = runTest {
        val backend = testBackend(
            clock, config = FAST.copy(assistantChunkDelayMs = 100_000), assistantConsent = true,
            dispatcher = StandardTestDispatcher(testScheduler),
        ).apply { load(doc) }
        val api = MockAssistantApi(backend)
        val asked = api.ask(IdempotencyKey("q1"), AskRequest("Сколько я трачу на Krabsbir?"))
        assertEquals(4, asked.remainingLimit)

        val events = api.streamAnswer(asked.answerId).toList()

        val error = assertIs<AnswerEvent.Error>(events.last()).event
        assertEquals(AnswerErrorCode.MODEL_ERROR, error.code)
        assertEquals(true, error.retryable)
        assertEquals(MockAssistantApi.ERROR_TEXTS.getValue(AnswerErrorCode.MODEL_ERROR), error.text)
        assertEquals(5, error.remainingLimit, "the attempt is refunded")
        assertEquals(1, events.count { it is AnswerEvent.Done || it is AnswerEvent.Error }, "one terminal event")
        assertEquals(180_000, testScheduler.currentTime)
        assertEquals(5, api.getLimit().remaining)
        val history = api.listMessages().messages.last()
        assertEquals(AnswerStatus.FAILED, history.status)
        assertEquals(AnswerErrorCode.MODEL_ERROR, history.errorCode)
    }

    @Test
    fun `an answer within the limit is not cut`() = runTest {
        val backend = testBackend(
            clock, config = FAST.copy(assistantChunkDelayMs = 10), assistantConsent = true,
            dispatcher = StandardTestDispatcher(testScheduler),
        ).apply { load(doc) }
        val api = MockAssistantApi(backend)
        val asked = api.ask(IdempotencyKey("q1"), AskRequest("Сколько я трачу на Krabsbir?"))

        val events = api.streamAnswer(asked.answerId).toList()

        assertIs<AnswerEvent.Done>(events.last())
        assertEquals(4, api.getLimit().remaining)
    }

    // ---- api.md «SSE»: a stream closed without a terminal event ----

    @Test
    fun `a dropped progress stream ends after its first event, the next opening goes to the end`() = runTest {
        val backend = testBackend(clock, config = FAST.copy(importStepDelayMs = 1_000), dispatcher = StandardTestDispatcher(testScheduler))
        val api = MockStatementsApi(backend)
        val accepted = api.uploadStatement(IdempotencyKey("k1"), file())
        backend.config = backend.config.copy(streamDrops = 1)

        val dropped = api.streamProgress(accepted.uploadId).toList()

        assertIs<ImportEvent.Progress>(dropped.single())
        assertEquals(0, backend.config.streamDrops, "the drop is counted down")
        assertIs<ImportEvent.Complete>(api.streamProgress(accepted.uploadId).toList().last())
    }

    @Test
    fun `an opening whose first event is terminal is not dropped and keeps the counter`() = runTest {
        val backend = testBackend(clock, dispatcher = StandardTestDispatcher(testScheduler))
        val api = MockStatementsApi(backend)
        val accepted = api.uploadStatement(IdempotencyKey("k1"), file())
        assertIs<ImportEvent.Complete>(api.streamProgress(accepted.uploadId).toList().last())
        backend.config = backend.config.copy(streamDrops = 1)

        assertIs<ImportEvent.Complete>(api.streamProgress(accepted.uploadId).toList().single())
        assertEquals(1, backend.config.streamDrops)
    }

    @Test
    fun `the client loop re-opens a dropped progress stream and gets the result`() = runTest {
        val backend = testBackend(clock, config = FAST.copy(importStepDelayMs = 1_000, streamDrops = 2), dispatcher = StandardTestDispatcher(testScheduler))
        val api = MockStatementsApi(backend)
        val accepted = api.uploadStatement(IdempotencyKey("k1"), file())
        var opens = 0

        val events = reopeningStream(isTerminal = { it !is ImportEvent.Progress }) { opens++; api.streamProgress(accepted.uploadId) }.toList()

        assertEquals(3, opens, "two drops, then the full stream")
        assertIs<ImportEvent.Complete>(events.last())
        assertTrue(events.none { it is ImportEvent.Error })
    }

    @Test
    fun `a dropped verification stream ends after pending, the next opening brings verified`() = runTest {
        val backend = testBackend(clock, config = FAST.copy(callVerifyDelayMs = 5_000), userId = "", dispatcher = StandardTestDispatcher(testScheduler))
        val api = MockAuthApi(backend)
        val token = api.startPhoneVerification(IdempotencyKey("p1"), "+79990001122").verificationToken
        backend.config = backend.config.copy(streamDrops = 1)

        assertEquals(listOf(VerificationStatus.PENDING), api.streamPhoneVerification(token).toList().map { it.status })
        clock.at = clock.at.plusSeconds(10)
        assertEquals(VerificationStatus.VERIFIED, api.streamPhoneVerification(token).toList().last().status)
    }

    @Test
    fun `a dropped answer stream ends after its snapshot, the next opening goes to done`() = runTest {
        val backend = testBackend(
            clock, config = FAST.copy(assistantChunkDelayMs = 10), assistantConsent = true,
            dispatcher = StandardTestDispatcher(testScheduler),
        ).apply { load(doc) }
        val api = MockAssistantApi(backend)
        val asked = api.ask(IdempotencyKey("q1"), AskRequest("Сколько я трачу на Krabsbir?"))
        backend.config = backend.config.copy(streamDrops = 1)

        val dropped = api.streamAnswer(asked.answerId).toList()

        assertEquals(AnswerStatus.GENERATING, assertIs<AnswerEvent.Snapshot>(dropped.single()).event.status)
        assertIs<AnswerEvent.Done>(api.streamAnswer(asked.answerId).toList().last())
    }
}
