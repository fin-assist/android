package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.api.model.ImportNotice
import ru.finassist.pf.mock.api.MockStatementsApi
import ru.finassist.pf.mock.ofx.OfxCheck
import ru.finassist.pf.mock.ofx.OfxParser
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The files the UI tests pick in the e2e build (app/src/mockE2e/assets/e2e/statements, docs/e2e.md) give the
 * results the Maestro flows expect — checked here on the JVM instead of first failing on an emulator.
 */
class E2eStatementsTest {
    private val dir = File("../../app/src/mockE2e/assets/e2e/statements")
    private fun read(name: String) = File(dir, name).readText()
    private val fixture = File("src/main/assets/statements/fixture.ofx").readText()

    private class FixedClock(private val at: OffsetDateTime) : Clock {
        override fun now() = at
        override val zone: ZoneId = Clock.MOSCOW
    }

    @Test
    fun `error files are rejected with the codes the flows wait for`() {
        assertEquals(OfxCheck.Csv, OfxParser.check(read("statement.csv")))
        assertEquals(OfxCheck.NotOfx, OfxParser.check(read("notes.txt")))
        assertTrue(OfxParser.check(read("other-bank.ofx")) is OfxCheck.WrongBank)
        assertEquals(OfxCheck.Ok, OfxParser.check(read("october.ofx")))
    }

    @Test
    fun `october adds three operations on top of the fixture, a second time adds none`() {
        // The e2e build pins "now" to 2026-10-08 12:00 MSK.
        val backend = MockBackend(MockConfig(networkDelayMs = 0), FixedClock(OffsetDateTime.parse("2026-10-08T12:00:00+03:00")))
        backend.ready.complete(Unit)
        backend.profile = backend.emptyProfile("+79161234567", "u1")
        val statements = MockStatementsApi(backend)
        runBlocking {
            statements.startUpload("fixture.ofx", OfxParser.parse(fixture), backend.now().minusDays(1), instant = true)
            val october = OfxParser.parse(read("october.ofx"))
            assertEquals(3, october.transactions.size)
            assertEquals(0, october.unreadLines.size)

            val first = statements.startUpload("october.ofx", october, backend.now(), instant = true)
            assertEquals(3, first.result?.newCount)
            val again = statements.startUpload("october.ofx", october, backend.now(), instant = true)
            assertEquals(0, again.result?.newCount)
        }
    }

    @Test
    fun `unreadable has no readable operation but two unread lines, and the result says so`() {
        val backend = MockBackend(MockConfig(networkDelayMs = 0), FixedClock(OffsetDateTime.parse("2026-10-08T12:00:00+03:00")))
        backend.ready.complete(Unit)
        backend.profile = backend.emptyProfile("+79161234567", "u1")
        val statements = MockStatementsApi(backend)
        runBlocking {
            statements.startUpload("fixture.ofx", OfxParser.parse(fixture), backend.now().minusDays(1), instant = true)
            assertEquals(OfxCheck.Ok, OfxParser.check(read("unreadable.ofx")))
            val doc = OfxParser.parse(read("unreadable.ofx"))
            assertEquals(0, doc.transactions.size)
            assertEquals(listOf("no_date", "bad_amount"), doc.unreadLines.map { it.reason })
            // The UI flow upload_unreadable checks these line numbers on «Непрочитанные строки».
            assertEquals(listOf(31, 38), doc.unreadLines.map { it.lineNumber })
            // Kept to close the lines later (FIN-79): the account of the block and the FITID when it was read.
            val account = "OB$1$779fd587938ea35b46f83b06"
            assertEquals(listOf(account, account), doc.unreadLines.map { it.accountId })
            assertEquals(listOf("e2e-bad-0001", "e2e-bad-0002"), doc.unreadLines.map { it.fitId })
            assertEquals(OffsetDateTime.parse("2026-10-01T00:00:00+03:00"), doc.accountPeriods.getValue(account).from)

            val result = assertNotNull(statements.startUpload("unreadable.ofx", doc, backend.now(), instant = true).result)
            assertEquals(0, result.operationCount)
            assertEquals(2, result.unreadCount)
            assertTrue(ImportNotice.EMPTY_STATEMENT in result.notices)
        }
    }

    @Test
    fun `unreadable-fixed reads both lines of unreadable and closes them (FIN-79)`() {
        val backend = MockBackend(MockConfig(networkDelayMs = 0), FixedClock(OffsetDateTime.parse("2026-10-08T12:00:00+03:00")))
        backend.ready.complete(Unit)
        backend.profile = backend.emptyProfile("+79161234567", "u1")
        val statements = MockStatementsApi(backend)
        runBlocking {
            statements.startUpload("fixture.ofx", OfxParser.parse(fixture), backend.now().minusDays(1), instant = true)
            val broken = statements.startUpload("unreadable.ofx", OfxParser.parse(read("unreadable.ofx")), backend.now(), instant = true)
            val fixed = OfxParser.parse(read("unreadable-fixed.ofx"))
            assertEquals(2, fixed.transactions.size)
            assertEquals(0, fixed.unreadLines.size)
            statements.startUpload("unreadable-fixed.ofx", fixed, backend.now(), instant = true)

            // The UI flow upload_unreadable_fixed checks the history row of the broken upload.
            val row = statements.listStatements().uploads.single { it.uploadId == broken.id }
            assertEquals(0, row.unreadCount)
            assertEquals(2, row.resolvedUnreadCount)
            assertEquals(backend.now(), row.resolvedByUploadedAt)
            assertTrue(statements.listUnreadLines(broken.id).lines.isEmpty())
        }
    }
}
