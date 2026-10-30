package ru.finassist.pf.e2e

import org.junit.Test
import ru.finassist.pf.core.api.model.*
import java.time.OffsetDateTime
import kotlin.test.assertFailsWith

class StageProcessQuestionEvidenceTest {
    private val run = "11111111-2222-4333-8444-555555555555"
    private val now = OffsetDateTime.parse("2026-10-30T12:00:00Z")
    private val question = Message("q", MessageRole.USER, "#FIN362_TOOLS FIN386 process-death $run", createdAt = now)
    private val history = MessagesList(listOf(question), false)
    private val quota = AssistantLimit(4, 5, now.plusDays(1))
    private val counters = mapOf("real_ask_accepted" to 1, "ask_response_held" to 1, "ask_response_released" to 0)

    @Test
    fun `one exact persisted question and charge confirms the closed uncertain scenario`() {
        StageProcessQuestionEvidence.verify(run, history, quota) { counters[it] ?: 0 }
    }

    @Test
    fun `duplicate wrong or truncated server history cannot count as process death proof`() {
        for (messages in listOf(history.copy(hasOlder = true), history.copy(messages = listOf(question, question)), history.copy(messages = listOf(question.copy(text = "different"))))) {
            assertFailsWith<IllegalStateException> { StageProcessQuestionEvidence.verify(run, messages, quota) { counters[it] ?: 0 } }
        }
        assertFailsWith<IllegalStateException> { StageProcessQuestionEvidence.verify(run, history, quota.copy(remaining = 3)) { counters[it] ?: 0 } }
    }

    @Test
    fun `released response duplicate post or absent hold never proves an uncertain outcome`() {
        for ((name, value) in listOf("real_ask_accepted" to 2, "ask_response_held" to 0, "ask_response_released" to 1)) {
            val changed = counters + (name to value)
            assertFailsWith<IllegalStateException> { StageProcessQuestionEvidence.verify(run, history, quota) { changed[it] ?: 0 } }
        }
    }
}
