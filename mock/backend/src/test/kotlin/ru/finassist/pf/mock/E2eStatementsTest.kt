package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.mock.api.MockStatementsApi
import ru.finassist.pf.mock.ofx.OfxCheck
import ru.finassist.pf.mock.ofx.OfxParser
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
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
}
