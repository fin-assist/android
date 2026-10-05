package ru.finassist.pf.core.mockbackend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.finassist.pf.core.mockbackend.engine.*
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId

/** Runs the mock engine over the committed anonymized fixture; numbers come from the analysis in tbank-ofx-format.md. */
class MockEngineTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val now = OffsetDateTime.parse("2026-10-05T18:00:00+03:00")
    private val fixture = File("../../app/src/mock/assets/fixture/statement.ofx")

    private fun ledger(): Ledger {
        val parsed = (OfxParser.parse(fixture.readBytes()) as OfxParser.Result.Ok).statement
        return Ledger(zone).also { it.import(parsed, "statement.ofx", now.minusDays(1), Ids.next()) }
    }

    @Test fun parsesAllOperationsAndAccounts() {
        val parsed = (OfxParser.parse(fixture.readBytes()) as OfxParser.Result.Ok).statement
        assertEquals(1969, parsed.transactions.size)
        assertEquals(3, parsed.accounts.size)
        assertEquals(0, parsed.unreadLines.size)
        assertEquals(setOf(AccountType.OtherBank, AccountType.Savings, AccountType.Current), parsed.accounts.map { it.type }.toSet())
    }

    @Test fun rejectsCsvAndHtml() {
        assertEquals(OfxParser.Result.CsvNotAccepted, OfxParser.parse("Дата операции;Дата платежа;Номер карты;Статус\n".toByteArray()))
        assertEquals(OfxParser.Result.WrongFormat, OfxParser.parse("<html><body>login</body></html>".toByteArray()))
        val other = fixture.readText().replace("<BANKID>T-BANK</BANKID>", "<BANKID>SBER</BANKID>")
        assertTrue(OfxParser.parse(other.toByteArray()) is OfxParser.Result.WrongBank)
    }

    @Test fun emptyStatementIsValid() {
        val empty = """<?xml version="1.0" encoding="utf-8" ?>
            <?OFX OFXHEADER="200" VERSION="202" SECURITY="NONE" OLDFILEUID="NONE" NEWFILEUID="NONE"?>
            <OFX><SIGNONMSGSRSV1><SONRS><STATUS><CODE>0</CODE><SEVERITY>INFO</SEVERITY></STATUS></SONRS></SIGNONMSGSRSV1><BANKMSGSRSV1></BANKMSGSRSV1></OFX>"""
        val r = OfxParser.parse(empty.toByteArray()) as OfxParser.Result.Ok
        assertEquals(0, r.statement.transactions.size)
    }

    @Test fun pairsOwnTransfersAndFindsRefunds() {
        val l = ledger()
        assertEquals(119, l.pairs.size)
        val refunds = l.operations.values.filter { it.accounting is Accounting.Refund }
        assertEquals(11, refunds.size)
        assertTrue(refunds.all { it.categoryId == Categories.REFUND })
    }

    @Test fun reimportIsIdempotent() {
        val l = ledger()
        val parsed = (OfxParser.parse(fixture.readBytes()) as OfxParser.Result.Ok).statement
        val out = l.import(parsed, "again.ofx", now, Ids.next())
        assertEquals(0, out.newOperations.size)
        assertEquals(1969, out.duplicates)
        assertEquals(1969, l.operations.size)
    }

    @Test fun deletingTheOnlyUploadClearsOperations() {
        val l = ledger()
        assertTrue(l.deleteUpload(l.uploads.first().id, now))
        assertEquals(0, l.operations.size)
        assertEquals(0, l.pairs.size)
    }

    @Test fun manualCategorySplitsAPair() {
        val l = ledger()
        val pair = l.pairs.values.first()
        val r = l.changeCategory(pair.id, "cat_supermarkets", now) as Ledger.ChangeResult.Changed
        assertEquals(pair.id, r.replacedId)
        assertNull(r.pair)
        assertEquals(2, r.items.size)
        assertEquals(118, l.pairs.size)
        assertTrue(l.operations.getValue(pair.debit.id).isCategoryManual)
    }

    @Test fun analyticsDefaultsToLastMonthWithData() {
        val l = ledger()
        val a = AnalyticsEngine(l, zone, Wire(zone)).analytics("month", null, "with", now)
        assertTrue(a.hasData)
        assertEquals("2026-09", a.params?.date)
        assertEquals("complete", a.period?.coverage)
        assertEquals("ready", a.tiles?.expense?.status)
        assertEquals("ready", a.monthlyChart?.status)
        assertEquals(6, a.monthlyChart?.points?.size)
        assertNotNull(a.insights)
        assertEquals(12, a.state?.fullMonths)
    }

    @Test fun withoutTransfersDropsTransfersCategory() {
        val l = ledger()
        val engine = AnalyticsEngine(l, zone, Wire(zone))
        val with = engine.analytics("month", "2026-09", "with", now)
        val without = engine.analytics("month", "2026-09", "without", now)
        assertTrue(with.tiles!!.expense.value!! > without.tiles!!.expense.value!!)
        assertTrue(without.expenseCategories!!.items.none { it.categoryId == Categories.TRANSFERS })
    }

    @Test fun feedPagesByThreeMonths() {
        val l = ledger()
        val q = OperationsQuery(l, zone, Wire(zone))
        val first = q.query(OperationsQuery.Params(null, null, false, null, null, null, null, null, null, null, null), now) as OperationsQuery.Result.Ok
        assertEquals(3, first.body.summary?.size)
        assertNotNull(first.body.nextBefore)
        assertTrue(first.body.items.all { it.kind != "own_transfer" || it.fromAccount != null })
    }

    @Test fun searchDefaultsToTwelveMonths() {
        val l = ledger()
        val q = OperationsQuery(l, zone, Wire(zone))
        val r = q.query(OperationsQuery.Params(null, null, false, "самокат", null, null, null, null, null, null, null), now) as OperationsQuery.Result.Ok
        assertTrue(r.body.totalCount!! > 0)
        assertEquals(true, r.body.hasOlderData)
        val bad = q.query(OperationsQuery.Params(null, null, true, null, null, null, null, null, null, null, now), now)
        assertTrue(bad is OperationsQuery.Result.Error)
    }

    @Test fun assistantAnswersAboutMerchantsAndRefuses() {
        val l = ledger()
        val a = AssistantEngine(l, zone, Wire(zone))
        val ok = a.answer("Сколько я трачу на Самокат?", "with", now) as AssistantEngine.Outcome.Ok
        assertEquals(listOf("text", "chart", "rows"), ok.answer.blocks.map { it.type })
        assertEquals(6, ok.answer.blocks[1].points?.size)
        assertTrue(a.answer("Полёт на Марс", "with", now) is AssistantEngine.Outcome.CannotAnswer)
    }

    @Test fun importResultReportsCoverage() {
        val parsed = (OfxParser.parse(fixture.readBytes()) as OfxParser.Result.Ok).statement
        val l = Ledger(zone)
        val before = Coverage(l.uploads, zone, now.toLocalDate())
        val out = l.import(parsed, "statement.ofx", now, Ids.next())
        val r = StatementsService(l, zone, Wire(zone)).importResult(out, before, now)
        assertTrue(r.isFirstImport)
        assertEquals("all", r.totals?.scope)
        assertEquals(12, r.coverage.fullMonths)
        assertEquals(0, r.coverage.fullMonthsBefore)
        assertTrue(r.coverage.features.first { it.feature == "comparison" }.openedNow)
    }
}
