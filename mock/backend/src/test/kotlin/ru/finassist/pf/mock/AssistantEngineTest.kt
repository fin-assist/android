package ru.finassist.pf.mock

import org.junit.Test
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.mock.domain.AssistantEngine
import ru.finassist.pf.mock.ofx.OfxTransaction
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the canned model makes of a question: an answer, OFF_TOPIC or CANNOT_ANSWER (FIN-159, FIN-160), and whether
 * the answer spends the attempt (FIN-136).
 */
class AssistantEngineTest {
    private val backend = testBackend(assistantConsent = true).apply {
        load(
            statement(
                "2026-04-01", "2026-09-30",
                *(4..9).map { m -> trn("k$m", "2026-%02d-15T12:00".format(m), 100L * m, "Krabsbir", "Рестораны") }.toTypedArray(),
                trn("p", "2026-09-10T12:00", 500, "Аптека 36,6", "Аптеки"),
            ),
        )
    }
    private val engine = AssistantEngine(backend.ledger, backend.catalog, backend.operationScope, backend::coverage)

    private fun outcome(question: String) = engine.answer(question, TransferMode.WITH, LocalDate.parse("2026-10-06"))

    @Test
    fun `a question about finances is answered even with an off-topic word in it`() {
        for (q in listOf(
            "Сколько я трачу на лекарства по рецепту?",
            "Сколько ушло на аптеки по рецепту?",
            "Сколько я трачу на лекарства по рецептам?",
            "Сколько уходит на рецептурные лекарства?",
            "Сколько я трачу на Krabsbir? И расскажи анекдот",
            "Расходы на рестораны, а не рецепт",
        )) {
            assertIs<AssistantEngine.Outcome.Answered>(outcome(q), q)
        }
    }

    @Test
    fun `a question on another subject is off-topic, before the nonsense check`() {
        for (q in listOf("Дай рецепт борща", "Сколько стоит рецепт борща?", "Какая погода на Марсе?", "What is the weather today?", "Расскажи анекдот про траты")) {
            assertEquals(AssistantEngine.Outcome.OffTopic, outcome(q), q)
        }
    }

    @Test
    fun `a question the data cannot answer stays CANNOT_ANSWER`() {
        for (q in listOf("Сколько я потрачу в следующем году?", "hello")) {
            assertEquals(AssistantEngine.Outcome.CannotAnswer, outcome(q), q)
        }
    }

    private fun answered(question: String, engine: AssistantEngine = this.engine) =
        assertIs<AssistantEngine.Outcome.Answered>(engine.answer(question, TransferMode.WITH, LocalDate.parse("2026-10-06")), question).answer

    private fun engineFor(from: String, to: String, vararg transactions: OfxTransaction): AssistantEngine {
        val backend = testBackend(assistantConsent = true).apply { load(statement(from, to, *transactions)) }
        return AssistantEngine(backend.ledger, backend.catalog, backend.operationScope, backend::coverage)
    }

    @Test
    fun `an answer with operations over a covered period is charged`() {
        val answer = answered("Сколько я трачу на Krabsbir?")
        assertTrue(answer.charged)
        assertTrue(answer.chips.isNotEmpty())
    }

    @Test
    fun `nothing found over a covered period is an answer and is charged, not a free question`() {
        // FIN-136: «Kaspersky» is a merchant of the user, but its only operation is before the six months of the answer;
        // «Такси» is a category with no operations at all.
        val engine = engineFor(
            "2026-01-01", "2026-09-30",
            trn("old", "2026-02-10T12:00", 1_000, "Kaspersky", "Сервис"),
            trn("k", "2026-09-15T12:00", 100, "Krabsbir", "Рестораны"),
        )
        for (q in listOf("Kaspersky", "Расходы на такси")) {
            val answer = answered(q, engine)
            assertTrue(answer.text.contains("не нашли"), answer.text)
            assertTrue(answer.charged, q)
            assertEquals(emptyList(), answer.chips)
        }
    }

    @Test
    fun `a partly covered period is not empty coverage, the answer is charged`() {
        // September is covered only up to the 20th: `partial`, not `no_data`.
        val engine = engineFor("2026-04-01", "2026-09-20", trn("k", "2026-09-15T12:00", 100, "Krabsbir", "Рестораны"))
        assertTrue(answered("Расходы на такси", engine).charged)
        assertTrue(answered("Krabsbir", engine).charged)
    }

    @Test
    fun `the attempt is refunded only for a lock or no data over the requested period`() {
        fun charged(vararg results: AssistantEngine.FunctionResult) = AssistantEngine.isCharged(results.toList())
        val complete = AssistantEngine.FunctionResult("complete")
        val partial = AssistantEngine.FunctionResult("partial")
        val noData = AssistantEngine.FunctionResult("no_data")
        // Refunds: empty coverage, a locked metric (even over a covered period), either one among several functions.
        assertFalse(charged(noData))
        assertFalse(charged(AssistantEngine.FunctionResult("complete", locked = true)))
        assertFalse(charged(AssistantEngine.FunctionResult("partial", locked = true)))
        assertFalse(charged(complete, noData))
        assertFalse(charged(partial, AssistantEngine.FunctionResult("complete", locked = true)))
        // Charged: whatever the functions found over a complete or partial period, nothing included.
        assertTrue(charged(complete))
        assertTrue(charged(partial))
        assertTrue(charged(complete, partial))
        assertTrue(charged())
    }
}
