package ru.finassist.pf.mock

import org.junit.Test
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.mock.domain.AssistantEngine
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** What the canned model makes of a question: an answer, OFF_TOPIC or CANNOT_ANSWER (FIN-159, FIN-160). */
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
            "Сколько я трачу на Krabsbir? И расскажи анекдот",
            "Расходы на рестораны, а не рецепт",
        )) {
            assertIs<AssistantEngine.Outcome.Answered>(outcome(q), q)
        }
    }

    @Test
    fun `a question on another subject is off-topic, before the nonsense check`() {
        for (q in listOf("Дай рецепт борща", "Какая погода на Марсе?", "What is the weather today?", "Расскажи анекдот про траты")) {
            assertEquals(AssistantEngine.Outcome.OffTopic, outcome(q), q)
        }
    }

    @Test
    fun `a question the data cannot answer stays CANNOT_ANSWER`() {
        for (q in listOf("Сколько я потрачу в следующем году?", "hello")) {
            assertEquals(AssistantEngine.Outcome.CannotAnswer, outcome(q), q)
        }
    }
}
