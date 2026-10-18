package ru.finassist.pf.mock

import org.junit.Test
import ru.finassist.pf.core.api.model.AnswerBlockEvent
import ru.finassist.pf.core.api.model.AnswerChunkEvent
import ru.finassist.pf.core.api.model.AnswerDoneEvent
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.AnswerErrorEvent
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.Chip
import ru.finassist.pf.core.api.model.ChipScreen
import ru.finassist.pf.core.api.model.Message
import ru.finassist.pf.core.api.model.MessageRole
import ru.finassist.pf.core.api.model.TransferMode
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Snapshot of a streamed answer (api.md 7.2) and the dialogue history built from the event log. */
class AssistantStoreTest {
    private val at = OffsetDateTime.parse(NOW)
    private val source = AnswerSource(calculatedAt = at, transferMode = TransferMode.WITH, changedSince = emptyList())
    private val chip = Chip("Операции", ChipScreen.OPERATIONS)
    private val rows = Block(type = BlockType.ROWS, altText = "rows")

    private fun chunk(index: Int, text: String) = AnswerEvent.Chunk(AnswerChunkEvent(index, text))

    @Test
    fun `chunks of one index grow one text block, a new index starts the next one`() {
        val answer = AssistantStore.Answer("a", "q", at)
        listOf(chunk(0, "Ушло"), chunk(0, " 100 ₽"), AnswerEvent.BlockAdded(AnswerBlockEvent(1, rows)), chunk(2, "Итого"))
            .forEach(answer::append)
        val (snapshot, seen) = answer.snapshot()
        assertEquals(4, seen)
        assertEquals(4, answer.version.value)
        assertEquals(AnswerStatus.GENERATING, snapshot.status)
        assertEquals(listOf(Block.text("Ушло 100 ₽"), rows, Block.text("Итого")), snapshot.blocks)
    }

    @Test
    fun `an answer still generating shows its blocks but no charge, chips or source`() {
        val answer = AssistantStore.Answer("a", "q", at)
        listOf(chunk(0, "Ушло"), AnswerEvent.Source(source), AnswerEvent.ChipAdded(chip)).forEach(answer::append)
        assertEquals(listOf(chip), answer.snapshot().first.chips)
        assertEquals(source, answer.snapshot().first.source)
        val message = answer.toMessage()
        assertEquals(AnswerStatus.GENERATING, message.status)
        assertEquals(listOf(Block.text("Ушло")), message.blocks)
        assertNull(message.charged)
        assertNull(message.chips)
        assertNull(message.source)

        answer.append(AnswerEvent.Done(AnswerDoneEvent(charged = false, remainingLimit = 5)))
        val done = answer.toMessage()
        assertEquals(AnswerStatus.COMPLETE, done.status)
        assertEquals(false, done.charged)
        assertEquals(listOf(chip), done.chips)
        assertEquals(source, done.source)
    }

    @Test
    fun `a failed answer keeps its error and drops the partial text`() {
        val answer = AssistantStore.Answer("a", "q", at)
        answer.append(chunk(0, "Ушло"))
        answer.append(AnswerEvent.Error(AnswerErrorEvent(AnswerErrorCode.MODEL_ERROR, "boom", 4, text = "Текст", retryable = true)))
        val message = answer.toMessage()
        assertEquals(AnswerStatus.FAILED, message.status)
        assertEquals(AnswerErrorCode.MODEL_ERROR, message.errorCode)
        assertEquals("Текст", message.errorText)
        assertEquals(true, message.retryable)
        assertEquals(emptyList(), message.blocks)
        assertEquals(AnswerStatus.FAILED, answer.snapshot().first.status)
    }

    @Test
    fun `history pairs each question with its latest not replaced answer`() {
        val store = AssistantStore()
        val q1 = Message("q1", MessageRole.USER, text = "1", createdAt = at)
        val q2 = Message("q2", MessageRole.USER, text = "2", createdAt = at)
        store.questions += listOf(q1, q2)
        store.answers["old"] = AssistantStore.Answer("old", "q1", at).apply { replaced = true }
        store.answers["new"] = AssistantStore.Answer("new", "q1", at)
        // q2 has no answer yet: only the question is shown.
        assertEquals(listOf("q1", "new", "q2"), store.history().map { it.id })

        store.usage[at.toLocalDate()] = 3
        store.clear()
        assertTrue(store.history().isEmpty())
        assertTrue(store.usage.isEmpty())
        assertTrue(store.answers.isEmpty())
    }
}
