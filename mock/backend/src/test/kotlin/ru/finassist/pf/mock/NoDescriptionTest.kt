package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.api.MockOperationsApi
import ru.finassist.pf.mock.api.MockStatementsApi
import ru.finassist.pf.mock.ofx.OfxDocument
import ru.finassist.pf.mock.ofx.OfxParser
import ru.finassist.pf.mock.ofx.OfxTransaction
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FIN-67: a statement line without a description is an operation with an empty title, not an unread line. */
class NoDescriptionTest {
    private val fixture = File("src/main/assets/statements/fixture.ofx").readText()

    private class FixedClock(private val at: OffsetDateTime) : Clock {
        override fun now() = at
        override val zone: ZoneId = Clock.MOSCOW
    }

    private fun ofx(transactions: String) = """
        <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>RUB</CURDEF>
        <BANKACCTFROM><BANKID>T-BANK</BANKID><ACCTID>acc1</ACCTID><ACCTTYPE>CHECKING</ACCTTYPE></BANKACCTFROM>
        <BANKTRANLIST><DTSTART>20260901000000</DTSTART><DTEND>20260930000000</DTEND>
        $transactions
        </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
    """.trimIndent()

    @Test
    fun `a line without NAME, or with an empty one, is an operation with an empty name`() {
        val doc = OfxParser.parse(
            ofx(
                """
                <STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20260905120000</DTPOSTED><TRNAMT>-120.00</TRNAMT><FITID>n1</FITID><MEMO>Супермаркеты</MEMO></STMTTRN>
                <STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20260906120000</DTPOSTED><TRNAMT>-80.00</TRNAMT><FITID>n2</FITID><NAME></NAME><MEMO>Фастфуд</MEMO></STMTTRN>
                """.trimIndent(),
            ),
        )
        assertEquals(emptyList(), doc.unreadLines)
        assertEquals(listOf("", ""), doc.transactions.map { it.name })
        assertEquals(listOf(12_000L, 8_000L), doc.transactions.map { it.amountMinor })
    }

    /** The fixture plus operations without a description: September debits and a credit after them. */
    private fun backendWithBlanks(): MockBackend {
        val base = OfxParser.parse(fixture)
        val account = base.transactions.first().accountId
        fun trn(id: String, at: String, debit: Boolean, minor: Long, memo: String) = OfxTransaction(
            accountId = account, fitId = id, isDebit = debit, postedAt = OffsetDateTime.parse(at), amountMinor = minor,
            name = "", memo = memo, currency = "RUB", lineNumber = 0,
        )
        val blanks = buildList {
            // Small and frequent: enough for a group if they shared a merchant name.
            repeat(6) { add(trn("small-$it", "2026-09-${10 + it}T12:00:00+03:00", true, 30_000, "Фастфуд")) }
            // Monthly and of the same size: a regular payment if they shared a merchant name.
            listOf("06", "07", "08", "09").forEach { add(trn("regular-$it", "2026-$it-03T12:00:00+03:00", true, 49_900, "Мобильная связь")) }
            // A credit in an income-only bank category: a refund only if it were matched by description.
            add(trn("credit", "2026-09-20T12:00:00+03:00", false, 77_700, "Пополнения"))
        }
        val doc: OfxDocument = base.copy(transactions = base.transactions + blanks)
        val backend = MockBackend(MockConfig(networkDelayMs = 0), FixedClock(OffsetDateTime.parse("2026-10-06T12:00:00+03:00")))
        backend.ready.complete(Unit)
        backend.profile = backend.emptyProfile("+79161234567", "u1")
        runBlocking { MockStatementsApi(backend).startUpload("fixture.ofx", doc, backend.now().minusDays(1), instant = true) }
        return backend
    }

    @Test
    fun `an operation without a description is not matched as a refund by description`() {
        val backend = backendWithBlanks()
        val credit = backend.ledger.all.single { !it.isDebit && it.name.isEmpty() }
        assertFalse(credit.isRefund)
        assertTrue(backend.ledger.all.filter { it.isRefund }.none { it.refundTarget != null && it.name.isEmpty() })
    }

    @Test
    fun `operations without a description form no merchant in the insights`() {
        val backend = backendWithBlanks()
        val insights = assertNotNull(backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH).insights)
        assertTrue(insights.smallFrequent.items.orEmpty().none { it.title.isBlank() })
        assertTrue(insights.regularPayments.items.orEmpty().none { it.title.isBlank() })
    }

    @Test
    fun `an operation without a description comes with an empty title and no description`() = runBlocking {
        val backend = backendWithBlanks()
        val api = MockOperationsApi(backend)
        val op = backend.ledger.all.first { it.isDebit && it.name.isEmpty() }
        val details = api.getOperation(op.id)
        assertEquals("", details.title)
        assertNull(details.description)
        assertEquals("Фастфуд", details.bankCategory)

        val feed = api.listOperations(OperationsQuery(filter = OperationsFilter(from = OffsetDateTime.parse("2026-09-01T00:00:00+03:00"), to = OffsetDateTime.parse("2026-10-01T00:00:00+03:00"))))
        assertTrue(feed.items.any { it.title.isEmpty() })
    }
}
