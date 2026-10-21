package ru.finassist.pf.mock

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.UploadFile
import ru.finassist.pf.core.api.model.AnalyticsFeature
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.ImportNoticeCode
import ru.finassist.pf.core.api.model.ImportProgress
import ru.finassist.pf.core.api.model.ImportStage
import ru.finassist.pf.core.api.model.ImportTotalsScope
import ru.finassist.pf.core.api.model.UnreadReason
import ru.finassist.pf.core.api.model.UploadStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.mock.api.MockStatementsApi
import ru.finassist.pf.mock.domain.AnalyticsEngine
import ru.finassist.pf.mock.ofx.UnreadLine
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Statements service (api.md 3): upload checks, background import, progress, history, deletion, unread lines. */
class MockStatementsApiTest {
    private val clock = TestClock(NOW)

    private fun file(name: String, text: String, size: Long = text.toByteArray().size.toLong()) =
        UploadFile(name, size) { text.byteInputStream() }

    private fun key(k: String) = IdempotencyKey(k)
    private fun dt(s: String): OffsetDateTime = OffsetDateTime.parse(s)

    /** A small valid OFX on one account. */
    private fun ofx(vararg trn: String, from: String = "20260901000000", to: String = "20260930000000") = """
        <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>RUB</CURDEF>
        <BANKACCTFROM><BANKID>T-BANK</BANKID><ACCTID>40817810000000001111</ACCTID><ACCTTYPE>CHECKING</ACCTTYPE></BANKACCTFROM>
        <BANKTRANLIST><DTSTART>$from</DTSTART><DTEND>$to</DTEND>
        ${trn.joinToString("\n")}
        </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
    """.trimIndent()

    private fun line(id: String, date: String, amount: String, name: String = "Магазин", memo: String = "Супермаркеты") =
        "<STMTTRN><TRNTYPE>${if (amount.startsWith("-")) "DEBIT" else "CREDIT"}</TRNTYPE><DTPOSTED>$date</DTPOSTED>" +
            "<TRNAMT>$amount</TRNAMT><FITID>$id</FITID><NAME>$name</NAME><MEMO>$memo</MEMO></STMTTRN>"

    private suspend fun MockStatementsApi.await(uploadId: String): List<ImportEvent> =
        withTimeout(10_000) { streamProgress(uploadId).toList() }

    @Test
    fun `a valid file is imported in the background and the progress stream ends with the result`() = runBlocking<Unit> {
        val backend = testBackend(clock, config = FAST.copy(importStepDelayMs = 20))
        val api = MockStatementsApi(backend)
        val accepted = api.uploadStatement(key("k1"), file("sept.ofx", ofx(line("1", "20260905120000", "-100.50"), line("2", "20260906120000", "2000.00", "ООО", "Зарплата"))))
        assertEquals("sept.ofx", accepted.fileName)

        val events = api.await(accepted.uploadId)
        val progress = events.dropLast(1).map { assertIs<ImportEvent.Progress>(it).progress }
        assertTrue(progress.isNotEmpty())
        assertEquals(progress.sortedBy { it.percent }, progress, "progress only goes forward")
        assertTrue(progress.all { it.stage in setOf(ImportStage.PARSING, ImportStage.DEDUP, ImportStage.RULES) })
        val result = assertIs<ImportEvent.Complete>(events.last()).result
        assertEquals(2, result.operationCount)
        assertEquals(2, result.newCount)
        assertTrue(result.isFirstImport)
        assertEquals(ImportTotalsScope.ALL, result.totals?.scope)
        assertEquals(10_050L, result.totals?.expense?.minor)
        assertEquals(200_000L, result.totals?.income?.minor)

        // A late subscriber gets the final event right away.
        assertEquals(events.last(), api.streamProgress(accepted.uploadId).first())
        val upload = api.listStatements().uploads.single()
        assertEquals(UploadStatus.DONE, upload.status)
        assertEquals(MockBackend.SOURCE_NAME, upload.sourceName)
        assertEquals("2026-09-01", upload.periodFrom)
        assertEquals("2026-09-30", upload.periodTo)
    }

    @Test
    fun `history uses complete statement periods and otherwise operation days in the current profile zone`() = runBlocking<Unit> {
        val backend = testBackend(clock)
        val api = MockStatementsApi(backend)
        suspend fun period(text: String): Pair<String?, String?> {
            val upload = api.uploadStatement(key(text), file("period.ofx", text))
            api.await(upload.uploadId)
            return api.listStatements().uploads.first().let { it.periodFrom to it.periodTo }
        }
        assertEquals("2026-09-01" to "2026-09-30", period(ofx(line("1", "20260925120000", "-100"))))
        assertEquals("2026-09-01" to "2026-09-30", period(ofx()))
        val noPeriod = ofx(line("2", "20260905233000[+0:UTC]", "-100"), line("3", "20260906233000[+0:UTC]", "-100"))
            .replace(Regex("<DTSTART>.*?</DTSTART>|<DTEND>.*?</DTEND>"), "")
        assertEquals("2026-09-06" to "2026-09-07", period(noPeriod))
        backend.profile = backend.profile.copy(timezone = "America/Los_Angeles")
        assertEquals("2026-09-05" to "2026-09-06", api.listStatements().uploads.first().let { it.periodFrom to it.periodTo })
        assertEquals(null to null, period(ofx().replace(Regex("<DTSTART>.*?</DTSTART>|<DTEND>.*?</DTEND>"), "")))
        // A lone boundary cannot be combined with another account's incomplete period.
        assertEquals("2026-09-05" to "2026-09-05", period(ofx(line("4", "20260905120000", "-100")).replace(Regex("<DTEND>.*?</DTEND>"), "")))
    }

    @Test
    fun `history combines only complete account periods across offsets`() = runBlocking<Unit> {
        val backend = testBackend(clock)
        val api = MockStatementsApi(backend)
        val text = ofx(from = "20260901003000[+0:UTC]", to = "20260930233000[+0:UTC]")
            .replace("</BANKMSGSRSV1>", """
                <STMTTRNRS><STMTRS><BANKACCTFROM><BANKID>T-BANK</BANKID><ACCTID>2222</ACCTID><ACCTTYPE>CHECKING</ACCTTYPE></BANKACCTFROM>
                <BANKTRANLIST><DTSTART>20260801000000</DTSTART></BANKTRANLIST></STMTRS></STMTTRNRS>
                <STMTTRNRS><STMTRS><BANKACCTFROM><BANKID>T-BANK</BANKID><ACCTID>3333</ACCTID><ACCTTYPE>CHECKING</ACCTTYPE></BANKACCTFROM>
                <BANKTRANLIST><DTSTART>20260905120000</DTSTART><DTEND>20261002120000</DTEND></BANKTRANLIST></STMTRS></STMTTRNRS>
                </BANKMSGSRSV1>
            """.trimIndent())
        val accepted = api.uploadStatement(key("multi"), file("multi.ofx", text))
        api.await(accepted.uploadId)
        val upload = api.listStatements().uploads.single()
        assertEquals("2026-09-01", upload.periodFrom)
        assertEquals("2026-10-02", upload.periodTo)
    }

    @Test
    fun `a repeated upload with the same key is the same upload`() = runBlocking<Unit> {
        val backend = testBackend(clock)
        val api = MockStatementsApi(backend)
        val text = ofx(line("1", "20260905120000", "-100.00"))
        val first = api.uploadStatement(key("same"), file("a.ofx", text))
        val again = api.uploadStatement(key("same"), file("a.ofx", text))
        assertEquals(first, again)
        api.await(first.uploadId)
        assertEquals(1, api.listStatements().uploads.size)
    }

    @Test
    fun `wrong files are rejected before import with the codes of api md`() = runBlocking<Unit> {
        val backend = testBackend(clock, config = FAST.copy(maxFileSizeBytes = 1_000))
        val api = MockStatementsApi(backend)
        val cases = listOf(
            file("big.ofx", ofx(), size = 1_001) to (ErrorCodes.FILE_TOO_LARGE to 413),
            file("s.csv", "Дата операции;Дата платежа;Сумма\n01.09.2026;...") to (ErrorCodes.CSV_NOT_ACCEPTED to 422),
            file("s.pdf", "%PDF-1.4") to (ErrorCodes.WRONG_FORMAT to 422),
            file("sber.ofx", ofx().replace("T-BANK", "SBER")) to (ErrorCodes.WRONG_BANK to 422),
        )
        for ((f, expected) in cases) {
            val e = assertFailsWith<AppError.Api>(f.name) { api.uploadStatement(key(f.name), f) }
            assertEquals(expected, e.code to e.httpStatus, f.name)
        }
        assertTrue(api.listStatements().uploads.isEmpty())
        assertTrue(backend.ledger.all.isEmpty())
    }

    @Test
    fun `statements need a session and the network`() = runBlocking<Unit> {
        val signedOut = MockStatementsApi(testBackend(clock, userId = ""))
        assertFailsWith<AppError.Unauthorized> { signedOut.getImportConfig() }
        assertFailsWith<AppError.Unauthorized> { signedOut.uploadStatement(key("k"), file("a.ofx", ofx())) }
        assertFailsWith<AppError.Unauthorized> { signedOut.listStatements() }
        assertFailsWith<AppError.Unauthorized> { signedOut.deleteStatement("x") }
        assertFailsWith<AppError.Unauthorized> { signedOut.listUnreadLines("x") }

        val offline = MockStatementsApi(testBackend(clock, config = FAST.copy(offline = true)))
        assertFailsWith<AppError.Offline> { offline.listStatements() }
    }

    @Test
    fun `progress of an unknown upload is NOT_FOUND`() = runBlocking<Unit> {
        val api = MockStatementsApi(testBackend(clock))
        val e = assertFailsWith<AppError.Api> { api.streamProgress("missing").toList() }
        assertEquals(ErrorCodes.NOT_FOUND to 404, e.code to e.httpStatus)
    }

    @Test
    fun `deleting an upload still processing cancels it and leaves nothing behind`() = runBlocking<Unit> {
        val backend = testBackend(clock, config = FAST.copy(importStepDelayMs = 60_000))
        val api = MockStatementsApi(backend)
        val accepted = api.uploadStatement(key("k"), file("a.ofx", ofx(line("1", "20260905120000", "-100.00"))))
        assertEquals(UploadStatus.PROCESSING, api.listStatements().uploads.single().status)
        assertNull(api.listStatements().uploads.single().periodFrom)
        assertNull(api.listStatements().uploads.single().periodTo)
        // Wait until the import job has started: it is now pausing in the parsing stage.
        withTimeout(10_000) { api.streamProgress(accepted.uploadId).first { (it as? ImportEvent.Progress)?.progress?.percent == 20 } }

        // Subscribed before the deletion: once discarded, the upload is unknown to a new subscriber.
        val stream = api.streamProgress(accepted.uploadId)
        api.deleteStatement(accepted.uploadId)
        val error = assertIs<ImportEvent.Error>(withTimeout(10_000) { stream.toList() }.last())
        assertEquals(ErrorCodes.CANCELLED, error.code)
        assertTrue(api.listStatements().uploads.isEmpty())
        assertTrue(backend.ledger.all.isEmpty())
        // The data is as it was: earlier answers of the assistant are not marked «загружали выписки» (FIN-135).
        assertEquals(0, backend.dataChanges.version)
    }

    @Test
    fun `an import cancelled after its operations are in leaves the unread lines of earlier uploads open (FIN-79)`() = runBlocking<Unit> {
        // Each stage pauses 5 s: long enough to cancel inside the rules stage, short enough to get there.
        val backend = testBackend(clock, config = FAST.copy(importStepDelayMs = 5_000))
        val api = MockStatementsApi(backend)
        val old = backend.load(
            statement("2026-09-01", "2026-09-30").copy(
                unreadLines = listOf(ru.finassist.pf.mock.ofx.UnreadLine(7, dt("2026-09-05T12:00:00+03:00"), "bad_amount", "40817810000000001111", "1")),
            ),
        )
        val accepted = api.uploadStatement(key("k"), file("fixed.ofx", ofx(line("1", "20260905120000", "-100.00"))))
        // The operations are in the ledger; the import pauses in the rules stage.
        withTimeout(30_000) { api.streamProgress(accepted.uploadId).first { (it as? ImportEvent.Progress)?.progress?.percent == 85 } }
        val stream = api.streamProgress(accepted.uploadId)
        api.deleteStatement(accepted.uploadId)
        assertEquals(ErrorCodes.CANCELLED, assertIs<ImportEvent.Error>(withTimeout(10_000) { stream.toList() }.last()).code)

        val row = api.listStatements().uploads.single { it.uploadId == old.id }
        assertEquals(1, row.unreadCount)
        assertEquals(0, row.resolvedUnreadCount)
    }

    @Test
    fun `deleting an upload whose import job has not started yet still cancels and discards it`() = runBlocking<Unit> {
        // FIN-76: with zero latency the deletion can come before the background job ran a single line.
        val jobs = ManualDispatcher()
        val backend = testBackend(clock, dispatcher = jobs)
        val api = MockStatementsApi(backend)
        val accepted = api.uploadStatement(key("k"), file("a.ofx", ofx(line("1", "20260905120000", "-100.00"))))
        val upload = backend.uploads.single()
        val stream = api.streamProgress(accepted.uploadId)
        assertEquals(ImportEvent.Progress(ImportProgress(ImportStage.PARSING, 0)), upload.events.value)

        api.deleteStatement(accepted.uploadId)
        jobs.runAll()

        assertEquals(ErrorCodes.CANCELLED, assertIs<ImportEvent.Error>(upload.events.value).code)
        assertEquals(ErrorCodes.CANCELLED, assertIs<ImportEvent.Error>(withTimeout(10_000) { stream.toList() }.last()).code)
        assertTrue(api.listStatements().uploads.isEmpty())
        assertTrue(backend.ledger.all.isEmpty())
    }

    @Test
    fun `a failing import is atomic - PROCESSING_FAILED and no operation of it stays`() = runBlocking<Unit> {
        val backend = testBackend(clock)
        val api = MockStatementsApi(backend)
        val good = trn("ok", "2026-09-05T12:00", 100, "Магазин", "Супермаркеты")
        // The second line points at an account the statement does not declare: the import throws midway.
        val broken = statement("2026-09-01", "2026-09-30", good, good.copy(fitId = "bad", accountId = "unknown"))
        val upload = api.startUpload("broken.ofx", broken, clock.at, instant = true)
        val error = assertIs<ImportEvent.Error>(upload.events.value)
        assertEquals(ErrorCodes.PROCESSING_FAILED, error.code)
        assertTrue(backend.ledger.all.isEmpty())
        assertTrue(api.listStatements().uploads.isEmpty())
        assertNull(api.listStatements().summary)
    }

    @Test
    fun `deleting a done upload removes only operations no other upload has`() = runBlocking<Unit> {
        val backend = testBackend(clock)
        val api = MockStatementsApi(backend)
        val a = trn("a", "2026-08-05T12:00", 100, "A", "Супермаркеты")
        val b = trn("b", "2026-08-20T12:00", 200, "B", "Супермаркеты")
        val c = trn("c", "2026-09-05T12:00", 300, "C", "Супермаркеты")
        val first = backend.load(statement("2026-08-01", "2026-08-31", a, b), "aug.ofx")
        val second = backend.load(statement("2026-08-15", "2026-09-30", b, c), "sep.ofx")
        assertEquals(1, second.result?.duplicateCount)
        assertEquals(ImportTotalsScope.NEW, second.result?.totals?.scope)
        assertEquals(30_000L, second.result?.totals?.expense?.minor)

        api.deleteStatement(first.id)
        assertEquals(setOf("b", "c"), backend.ledger.all.map { it.fitId }.toSet())
        assertEquals(listOf("sep.ofx"), api.listStatements().uploads.map { it.fileName })
        // Unknown id: nothing to delete, no error.
        api.deleteStatement("missing")
        assertEquals(1, api.listStatements().uploads.size)
    }

    @Test
    fun `the history summary counts operations and reports gaps between statements`() = runBlocking<Unit> {
        val backend = testBackend(clock)
        val api = MockStatementsApi(backend)
        assertNull(api.listStatements().summary)
        backend.load(statement("2026-06-01", "2026-06-30", trn("a", "2026-06-10T12:00", 100, "A", "Супермаркеты")), "june.ofx")
        backend.load(statement("2026-08-01", "2026-08-31", trn("b", "2026-08-10T12:00", 100, "B", "Супермаркеты")), "aug.ofx")
        val list = api.listStatements()
        val summary = assertNotNull(list.summary)
        assertEquals(2, summary.uploadCount)
        assertEquals(2, summary.operationCount)
        assertEquals(dt("2026-06-10T12:00:00+03:00"), summary.firstOperationAt)
        assertEquals(dt("2026-08-10T12:00:00+03:00"), summary.lastOperationAt)
        assertEquals(listOf(DateRange(dt("2026-07-01T00:00:00+03:00"), dt("2026-08-01T00:00:00+03:00"))), summary.gaps)
        // Newest first.
        assertEquals(listOf("aug.ofx", "june.ofx"), list.uploads.map { it.fileName })
    }

    @Test
    fun `an empty statement is done but gives no summary and says it is empty`() = runBlocking<Unit> {
        val backend = testBackend(clock)
        val api = MockStatementsApi(backend)
        val result = assertNotNull(backend.load(statement("2026-09-01", "2026-09-30")).result)
        assertEquals(listOf(ImportNoticeCode.EMPTY_STATEMENT), result.notices.map { it.code })
        assertNull(result.totals)
        assertNull(api.listStatements().summary)
        assertEquals(UploadStatus.DONE, api.listStatements().uploads.single().status)
    }

    @Test
    fun `a statement with credits only notes that it has no expenses`() {
        val backend = testBackend(clock)
        val result = assertNotNull(backend.load(statement("2026-09-01", "2026-09-30", trn("s", "2026-09-05T10:00", 1_000, "ООО", "Зарплата", debit = false))).result)
        assertEquals(listOf(ImportNoticeCode.NO_EXPENSES), result.notices.map { it.code })
        assertEquals(0L, result.totals?.expense?.minor)
    }

    @Test
    fun `the import result counts own transfers and uncategorised refunds and leaves pairs out of the totals`() {
        val backend = testBackend(clock)
        val result = assertNotNull(
            backend.load(
                statement(
                    "2026-09-01", "2026-09-30",
                    trn("d", "2026-09-10T10:00", 5_000, "Между своими счетами", "Переводы"),
                    trn("c", "2026-09-10T11:00", 5_000, "Пополнение", "Переводы", debit = false, account = Acc.SAVINGS),
                    trn("buy", "2026-09-11T10:00", 1_000, "Магазин", "Супермаркеты"),
                    trn("back", "2026-09-12T10:00", 300, "Возврат", "Переводы", debit = false),
                ),
            ).result,
        )
        assertEquals(4, result.newCount)
        assertEquals(2, result.ownTransferCount)
        assertEquals(1, result.uncategorizedCount)
        assertEquals(3, result.categorizedCount)
        assertEquals(AnalyticsEngine.SELECTION_UNCATEGORIZED, result.uncategorizedFilters?.selection)
        // Expense: the purchase minus the refund; the pair is neither expense nor income.
        assertEquals(70_000L, result.totals?.expense?.minor)
        assertEquals(0L, result.totals?.income?.minor)
        assertEquals(listOf("Текущий", "Накопительный", "Счёт другого банка"), result.accounts.map { it.typeName })
        assertEquals(listOf(true), result.accounts.filter { it.isOtherBank }.map { it.mask == null })
    }

    @Test
    fun `coverage of the result opens features as full months arrive`() {
        val backend = testBackend(clock)
        val first = assertNotNull(backend.load(statement("2026-09-01", "2026-09-30", trn("a", "2026-09-10T12:00", 100, "A", "Супермаркеты"))).result).coverage
        assertEquals(1, first.fullMonths)
        assertEquals(0, first.fullMonthsBefore)
        assertEquals(listOf("2026-09"), first.newlyFullMonths)
        val comparison = first.features.single { it.feature == AnalyticsFeature.COMPARISON }
        assertTrue(comparison.open && comparison.openedNow)
        assertNull(comparison.requiredFullMonths)
        val typical = first.features.single { it.feature == AnalyticsFeature.TYPICAL }
        assertFalse(typical.open)
        assertEquals(2, typical.requiredFullMonths)
        assertEquals(2, first.features.single { it.feature == AnalyticsFeature.MONTHLY_CHART }.requiredMonthsWithData)

        // August plus the current month so far: August is new and full, October is incomplete and in progress.
        val second = assertNotNull(
            backend.load(statement("2026-08-01", "2026-10-05", trn("b", "2026-10-03T12:00", 100, "B", "Супермаркеты"))).result,
        ).coverage
        assertEquals(2, second.fullMonths)
        assertEquals(1, second.fullMonthsBefore)
        assertEquals(listOf("2026-08"), second.newlyFullMonths)
        val opened = second.features.filter { it.openedNow }.map { it.feature }.toSet()
        assertEquals(setOf(AnalyticsFeature.MONTHLY_CHART, AnalyticsFeature.TYPICAL, AnalyticsFeature.REGULAR_PAYMENTS), opened)
        val october = second.incompleteMonths.single()
        assertEquals("2026-10", october.month.toString())
        assertTrue(october.inProgress)
        assertEquals(dt("2026-10-01T00:00:00+03:00"), october.dataFrom)
        // The last operation of the month is not on its last covered day: the end of that day (docs/calc.md 4).
        assertEquals(dt("2026-10-05T23:59:59+03:00"), october.dataTo)
    }

    @Test
    fun `an incomplete month reports the gap inside it`() {
        val backend = testBackend(clock)
        backend.load(statement("2026-09-01", "2026-09-10", trn("a", "2026-09-05T12:00", 100, "A", "Супермаркеты")))
        val result = assertNotNull(backend.load(statement("2026-09-20", "2026-09-30", trn("b", "2026-09-25T12:00", 100, "B", "Супермаркеты"))).result)
        val september = result.coverage.incompleteMonths.single()
        assertEquals("2026-09", september.month)
        assertFalse(september.inProgress)
        assertEquals(listOf(DateRange(dt("2026-09-11T00:00:00+03:00"), dt("2026-09-20T00:00:00+03:00"))), september.gaps)
        assertEquals(0, result.coverage.fullMonths)
    }

    @Test
    fun `import config suggests the period since the last operation and reports what is loaded`() = runBlocking<Unit> {
        val backend = testBackend(clock)
        val api = MockStatementsApi(backend)
        val empty = api.getImportConfig()
        assertNull(empty.loaded)
        assertEquals(dt("2025-10-06T00:00:00+03:00"), empty.suggestedPeriod.from)
        assertEquals(dt("2026-10-07T00:00:00+03:00"), empty.suggestedPeriod.to)
        assertEquals(listOf("OFX"), empty.acceptedFormats)
        assertEquals(FAST.maxFileSizeBytes, empty.maxFileSizeBytes)

        backend.load(statement("2026-08-01", "2026-09-30", trn("a", "2026-08-10T12:00", 100, "A", "Супермаркеты"), trn("b", "2026-09-20T12:00", 100, "B", "Супермаркеты")))
        val config = api.getImportConfig()
        val loaded = assertNotNull(config.loaded)
        assertEquals(2, loaded.operationCount)
        assertEquals(dt("2026-08-10T12:00:00+03:00"), loaded.firstOperationAt)
        assertEquals(dt("2026-09-20T12:00:00+03:00"), loaded.lastOperationAt)
        assertEquals(dt("2026-09-20T00:00:00+03:00"), config.suggestedPeriod.from)
        val start = config.suggestedPeriod.from.toInstant().toEpochMilli()
        val end = config.suggestedPeriod.to.toInstant().toEpochMilli()
        assertTrue(config.downloadUrl.contains("start=$start&end=$end&format=ofx"), config.downloadUrl)
    }

    @Test
    fun `unread lines are listed per upload and per period with readable reasons`() = runBlocking<Unit> {
        val backend = testBackend(clock)
        val api = MockStatementsApi(backend)
        val lines = listOf(
            UnreadLine(10, dt("2026-09-05T12:00:00+03:00"), "bad_amount"),
            UnreadLine(20, null, "no_date"),
            UnreadLine(30, null, "bad_date"),
            UnreadLine(40, dt("2026-09-25T12:00:00+03:00"), "truncated"),
            UnreadLine(50, dt("2026-08-25T12:00:00+03:00"), "something_new"),
        )
        val upload = backend.load(statement("2026-08-01", "2026-09-30").copy(unreadLines = lines), "u.ofx")
        assertEquals(5, upload.result?.unreadCount)

        val all = api.listUnreadLines(upload.id).lines
        assertEquals(listOf(10, 20, 30, 40, 50), all.map { it.lineNumber })
        assertEquals(
            listOf("не распознали сумму", "нет даты", "не распознали дату", "строка оборвана", "строка оборвана"),
            all.map { it.reasonName },
        )
        assertEquals(UnreadReason.UNKNOWN, all.last().reason)
        assertTrue(all.all { it.uploadId == upload.id && it.fileName == "u.ofx" })

        // By period: only dated lines within [from, to).
        val september = api.listUnreadLines(dt("2026-09-01T00:00:00+03:00"), dt("2026-09-25T12:00:00+03:00")).lines
        assertEquals(listOf(10), september.map { it.lineNumber })

        val e = assertFailsWith<AppError.Api> { api.listUnreadLines("missing") }
        assertEquals(ErrorCodes.NOT_FOUND to 404, e.code to e.httpStatus)
    }

    @Test
    fun `a real file from the e2e set goes through upload end to end`() = runBlocking<Unit> {
        val backend = testBackend(clock)
        val api = MockStatementsApi(backend)
        val text = File("../../app/src/mockE2e/assets/e2e/statements/october.ofx").readText()
        val accepted = api.uploadStatement(key("oct"), file("october.ofx", text))
        val result = assertIs<ImportEvent.Complete>(api.await(accepted.uploadId).last()).result
        assertEquals(3, result.newCount)
        assertEquals(3, backend.ledger.all.size)
    }

    @Test
    fun `with unknown codes on, the import result carries a notice and a feature the client does not know (FIN-83)`() = runBlocking<Unit> {
        val backend = testBackend(clock, config = FAST.copy(unknownCodes = true))
        val api = MockStatementsApi(backend)
        val upload = api.startUpload("a.ofx", statement("2026-09-01", "2026-09-30", trn("1", "2026-09-05T12:00", 100, "Магазин", "Супермаркеты")), clock.at, instant = true)
        val result = assertNotNull(upload.result)
        assertEquals(MockCodes.NOTICE_UNKNOWN_TEXT, result.notices.single { it.code == ru.finassist.pf.core.api.model.ImportNoticeCode.UNKNOWN }.text)
        val feature = result.features().single { it.feature == AnalyticsFeature.UNKNOWN }
        assertEquals(MockCodes.FEATURE_UNKNOWN_TEXT, feature.text)
        // Known features come with the server's texts as well.
        assertEquals("Сравнение с прошлым месяцем", result.features().single { it.feature == AnalyticsFeature.COMPARISON }.featureName)
        val forecast = result.features().single { it.feature == AnalyticsFeature.YEAR_FORECAST }
        assertEquals("Прогноз на год — нужно 12 полных месяцев, есть 1", forecast.text)
    }

    private fun ru.finassist.pf.core.api.model.ImportResult.features() = coverage.features
}
