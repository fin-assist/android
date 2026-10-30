package ru.finassist.pf.e2e

import org.junit.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

class StageHistoryStatementFixtureTest {
    private fun fixture(name: String) = File("src/mockE2e/assets/e2e/statements/$name").readBytes()
    private fun prepared(name: String) = StageHistoryStatementFixture.prepare("e2e/statements/$name", fixture(name))
    private fun xml(bytes: ByteArray): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(ByteArrayInputStream(bytes))
    private fun Document.values(tag: String) = getElementsByTagName(tag).let { nodes ->
        (0 until nodes.length).map { nodes.item(it).textContent }
    }
    private fun section(text: String, tag: String) = text.substringAfter("<$tag>").substringBefore("</$tag>")

    @Test(timeout = 5_000)
    fun `all history examples receive valid XML OFX processing headers and successful signon`() {
        for (name in listOf("zz-history-period.ofx", "zz-history-fallback.ofx", "zz-history-empty.ofx")) {
            val bytes = prepared(name)
            val text = bytes.decodeToString()
            assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<?OFX OFXHEADER=\"200\" VERSION=\"202\""))
            val document = xml(bytes)
            assertEquals("OFX", document.documentElement.tagName)
            assertEquals(listOf("0", "0"), document.values("CODE"))
            assertEquals(listOf("RUS"), document.values("LANGUAGE"))
            assertEquals(listOf("0"), document.values("TRNUID"))
            assertEquals(listOf("T-BANK"), document.values("BANKID"))
            for (tag in listOf("BANKACCTFROM", "BANKTRANLIST")) {
                assertEquals(section(fixture(name).decodeToString(), tag), section(text, tag), "$name/$tag")
            }
            assertContentEquals(bytes, StageHistoryStatementFixture.prepare("e2e/statements/$name", bytes))
            val copied = ByteArrayOutputStream()
            StageHistoryStatementFixture.copyTo("e2e/statements/$name", ByteArrayInputStream(fixture(name)), copied)
            assertContentEquals(bytes, copied.toByteArray())
        }
    }

    @Test(timeout = 5_000)
    fun `explicit period transaction fields and FITID remain unchanged`() {
        val document = xml(prepared("zz-history-period.ofx"))
        assertEquals(listOf("20260901000000[+3:MSK]"), document.values("DTSTART"))
        assertEquals(listOf("20260930000000[+3:MSK]"), document.values("DTEND"))
        assertEquals(listOf("20260925120000[+3:MSK]"), document.values("DTPOSTED"))
        assertEquals(listOf("history-period.ofx"), document.values("FITID"))
        assertEquals(listOf("-100"), document.values("TRNAMT"))
        assertEquals(listOf("Магазин"), document.values("NAME"))
        assertEquals(listOf("Супермаркеты"), document.values("MEMO"))
        assertEquals(1, document.getElementsByTagName("STMTTRN").length)
    }

    @Test(timeout = 5_000)
    fun `fallback retains no source period and keeps only its actual transaction date`() {
        val document = xml(prepared("zz-history-fallback.ofx"))
        assertTrue(document.values("DTSTART").isEmpty())
        assertTrue(document.values("DTEND").isEmpty())
        assertEquals(listOf("20260925120000[+3:MSK]"), document.values("DTPOSTED"))
        assertEquals(listOf("history-fallback.ofx"), document.values("FITID"))
        assertEquals(1, document.getElementsByTagName("STMTTRN").length)
    }

    @Test(timeout = 5_000)
    fun `empty export gains no transactions or artificial calendar period`() {
        val document = xml(prepared("zz-history-empty.ofx"))
        for (tag in listOf("STMTTRN", "DTSTART", "DTEND", "DTPOSTED", "FITID")) assertTrue(document.values(tag).isEmpty(), tag)
    }

    @Test(timeout = 5_000)
    fun `other fixture paths and unexpected history contents remain byte identical`() {
        val original = fixture("zz-history-period.ofx")
        for (path in listOf("e2e/statements/zz-unread.ofx", "statements/fixture.ofx", "zz-history-period.ofx", "other/zz-history-period.ofx")) {
            assertSame(original, StageHistoryStatementFixture.prepare(path, original))
            val copied = ByteArrayOutputStream()
            StageHistoryStatementFixture.copyTo(path, ByteArrayInputStream(original), copied)
            assertContentEquals(original, copied.toByteArray())
        }
        for (bytes in listOf(byteArrayOf(-1, -2, 0), "broken".encodeToByteArray(), "<?xml version=\"1.0\"?><OFX/>".encodeToByteArray())) {
            assertSame(bytes, StageHistoryStatementFixture.prepare("e2e/statements/zz-history-period.ofx", bytes))
        }
    }
}
