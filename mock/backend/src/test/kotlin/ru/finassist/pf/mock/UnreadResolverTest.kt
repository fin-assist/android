package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.mock.api.MockStatementsApi
import ru.finassist.pf.mock.ofx.OfxDocument
import ru.finassist.pf.mock.ofx.StatementPeriod
import ru.finassist.pf.mock.ofx.UnreadLine
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unread lines closed by a later upload (api.md 3.6, «Закрытие строк»; FIN-79). */
class UnreadResolverTest {
    private val backend = testBackend()
    private val api = MockStatementsApi(backend)

    private fun at(s: String) = OffsetDateTime.parse("${s}:00+03:00")
    private fun period(from: String, to: String) = StatementPeriod(at("${from}T00:00"), at("${to}T23:59"))

    /** A statement of the current account over [from]..[to] with [transactions] and [unread] lines. */
    private fun doc(from: String, to: String, transactions: List<ru.finassist.pf.mock.ofx.OfxTransaction> = emptyList(), unread: List<UnreadLine> = emptyList()) =
        statement(from, to, *transactions.toTypedArray()).copy(
            unreadLines = unread,
            accountPeriods = mapOf(Acc.CURRENT.id to period(from, to)),
        )

    private fun line(n: Int, date: String?, reason: String, fitId: String? = null, account: String? = Acc.CURRENT.id) =
        UnreadLine(n, date?.let(::at), reason, account, fitId)

    private fun upload(name: String, doc: OfxDocument): MockBackend.Upload = backend.load(doc, name)

    private fun row(u: MockBackend.Upload) = runBlocking { api.listStatements().uploads.single { it.uploadId == u.id } }
    private fun openLines(u: MockBackend.Upload) = runBlocking { api.listUnreadLines(u.id).lines.map { it.lineNumber } }

    @Test fun `a line with FITID is closed once a later upload has the operation`() {
        val old = upload("old.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(line(10, "2026-09-05T10:00", "bad_amount", fitId = "f1"))))
        assertEquals(1, row(old).unreadCount)

        upload("new.ofx", doc("2026-09-01", "2026-09-30", listOf(trn("f1", "2026-09-05T10:00", 300, "OZON", "Маркетплейсы"))))
        assertEquals(0, row(old).unreadCount)
        assertEquals(1, row(old).resolvedUnreadCount)
        assertTrue(openLines(old).isEmpty())
    }

    @Test fun `a line with FITID is not closed by an upload that does not have the operation, even if another one has`() {
        // The operation came with `first`, before the line was unread in `old`; `unrelated` has nothing to do with it.
        upload("first.ofx", doc("2026-09-01", "2026-09-30", listOf(trn("f1", "2026-09-05T10:00", 300, "OZON", "Маркетплейсы"))))
        val old = upload("old.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(line(10, "2026-09-05T10:00", "bad_amount", fitId = "f1"))))
        upload("unrelated.ofx", doc("2026-09-01", "2026-09-30", listOf(trn("f7", "2026-09-07T10:00", 100, "OZON", "Маркетплейсы"))))
        assertEquals(1, row(old).unreadCount)
    }

    @Test fun `a line on the last day of the period is covered by a DTEND without time`() {
        val old = upload("old.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(line(10, "2026-09-30T14:00", "truncated"))))
        val dateOnly = StatementPeriod(at("2026-09-01T00:00"), at("2026-09-30T00:00"))
        upload("new.ofx", doc("2026-09-01", "2026-09-30").copy(accountPeriods = mapOf(Acc.CURRENT.id to dateOnly)))
        assertEquals(0, row(old).unreadCount)
        assertEquals(1, row(old).resolvedUnreadCount)
    }

    @Test fun `a line with FITID stays open when the later upload does not have it`() {
        val old = upload("old.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(line(10, "2026-09-05T10:00", "bad_amount", fitId = "f1"))))
        upload("new.ofx", doc("2026-09-01", "2026-09-30", listOf(trn("f2", "2026-09-05T10:00", 300, "OZON", "Маркетплейсы"))))
        assertEquals(1, row(old).unreadCount)
        assertEquals(0, row(old).resolvedUnreadCount)
        assertNull(row(old).resolvedByUploadedAt)
    }

    @Test fun `a line without FITID is closed by a statement of the account covering its day without own unread lines`() {
        val old = upload("old.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(line(10, "2026-09-05T10:00", "truncated"))))
        upload("new.ofx", doc("2026-09-03", "2026-09-07"))
        assertEquals(0, row(old).unreadCount)
    }

    @Test fun `a line without FITID stays open when the later statement does not cover its day`() {
        val old = upload("old.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(line(10, "2026-09-05T10:00", "truncated"))))
        upload("new.ofx", doc("2026-09-06", "2026-09-30"))
        assertEquals(1, row(old).unreadCount)
    }

    @Test fun `the same day unread again in the later upload replaces the old line - only the new one shows`() {
        val old = upload("old.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(line(10, "2026-09-05T10:00", "truncated"))))
        val new = upload("new.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(line(12, "2026-09-05T18:00", "truncated"))))
        assertEquals(0, row(old).unreadCount)
        assertEquals(0, row(old).resolvedUnreadCount, "replaced, not read")
        assertEquals(listOf(12), openLines(new))
    }

    @Test fun `re-uploading the same broken file does not grow the counters`() {
        val broken = doc("2026-09-01", "2026-09-30", unread = listOf(
            line(10, "2026-09-05T10:00", "bad_amount", fitId = "f1"),
            line(11, null, "no_date"),
        ))
        upload("first.ofx", broken)
        upload("second.ofx", broken)
        upload("third.ofx", broken)
        assertEquals(2, backend.unreadLines().size)
        val counts = runBlocking { api.listStatements().uploads.map { it.unreadCount } }
        assertEquals(listOf(2, 0, 0), counts, "newest first: only the last upload keeps the lines")
    }

    @Test fun `a statement of another account does not close the line`() {
        val old = upload("old.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(line(10, "2026-09-05T10:00", "truncated"))))
        upload("new.ofx", doc("2026-09-01", "2026-09-30").copy(accountPeriods = mapOf(Acc.SAVINGS.id to period("2026-09-01", "2026-09-30"))))
        assertEquals(1, row(old).unreadCount)
    }

    @Test fun `a line without date and FITID is closed only by a statement covering the whole old period`() {
        val old = upload("old.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(line(10, null, "no_date"))))
        upload("part.ofx", doc("2026-09-10", "2026-09-30"))
        assertEquals(1, row(old).unreadCount)

        upload("full.ofx", doc("2026-08-25", "2026-10-02"))
        assertEquals(0, row(old).unreadCount)
    }

    @Test fun `a later upload with its own undated unread line replaces an undated line, not reads it`() {
        val old = upload("old.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(line(10, null, "no_date"))))
        upload("new.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(line(3, null, "no_date"))))
        assertEquals(0, row(old).unreadCount)
        assertEquals(0, row(old).resolvedUnreadCount)
    }

    @Test fun `only open lines count on analytics and in the period sheet`() {
        upload("old.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(
            line(10, "2026-09-05T10:00", "bad_amount", fitId = "f1"),
            line(11, "2026-09-06T10:00", "bad_amount", fitId = "f9"),
        )))
        upload("new.ofx", doc("2026-09-01", "2026-09-30", listOf(trn("f1", "2026-09-05T10:00", 300, "OZON", "Маркетплейсы"))))
        val lines = runBlocking { api.listUnreadLines(at("2026-09-01T00:00"), at("2026-10-01T00:00")).lines }
        assertEquals(listOf(11), lines.map { it.lineNumber })
        assertEquals(1, backend.unreadLines().size)
    }

    @Test fun `deleting the upload that closed a line opens it again unless another later upload closes it`() {
        val old = upload("old.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(line(10, "2026-09-05T10:00", "truncated"))))
        val first = upload("first.ofx", doc("2026-09-01", "2026-09-30"))
        val second = upload("second.ofx", doc("2026-09-04", "2026-09-06"))
        assertEquals(0, row(old).unreadCount)

        // The line was closed by `first`; `second` also covers the day and takes over.
        runBlocking { api.deleteStatement(first.id) }
        assertEquals(0, row(old).unreadCount)
        assertEquals(second.uploadedAt, row(old).resolvedByUploadedAt)

        runBlocking { api.deleteStatement(second.id) }
        assertEquals(1, row(old).unreadCount)
        assertEquals(0, row(old).resolvedUnreadCount)
    }

    @Test fun `deleting the upload with the operation opens a FITID line again`() {
        val old = upload("old.ofx", doc("2026-09-01", "2026-09-30", unread = listOf(line(10, "2026-09-05T10:00", "bad_amount", fitId = "f1"))))
        val new = upload("new.ofx", doc("2026-09-01", "2026-09-30", listOf(trn("f1", "2026-09-05T10:00", 300, "OZON", "Маркетплейсы"))))
        assertEquals(0, row(old).unreadCount)
        runBlocking { api.deleteStatement(new.id) }
        assertEquals(1, row(old).unreadCount)
    }
}
