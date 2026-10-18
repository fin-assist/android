package ru.finassist.pf.mock

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.Test
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.ChangedSince
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.mock.api.MockAssistantApi
import ru.finassist.pf.mock.api.MockOperationsApi
import ru.finassist.pf.mock.api.MockStatementsApi
import ru.finassist.pf.mock.domain.DataChanges
import ru.finassist.pf.core.api.model.SystemCategories
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FIN-135: `source.changed_since` / `changed_since_text` of an answer (api.md 7.2) — category changes and uploaded or
 * deleted statements after the answer was calculated; the history recounts them at the time of the request (7.3).
 */
class AssistantChangedSinceTest {
    private val clock = TestClock(NOW)

    private val doc = statement(
        "2026-04-01", "2026-09-30",
        *(4..9).map { m -> trn("k$m", "2026-%02d-15T12:00".format(m), 100L * m, "Krabsbir", "Рестораны") }.toTypedArray(),
        trn("self", "2026-09-10T10:00", 5_000, "Между своими счетами", "Переводы"),
        trn("self-in", "2026-09-10T11:00", 5_000, "Пополнение", "Переводы", debit = false, account = Acc.SAVINGS),
    )

    private val backend = testBackend(clock, assistantConsent = true)
    private val upload = backend.load(doc)
    private val assistant = MockAssistantApi(backend)
    private val operations = MockOperationsApi(backend)
    private val statements = MockStatementsApi(backend)

    private fun opId(fitId: String) = backend.ledger.all.single { it.fitId == fitId }.id

    /** Asks and waits for the answer to finish; returns its id and the `source` event of the live stream. */
    private suspend fun askAndFinish(key: String = "q1", text: String = "Сколько я трачу на Krabsbir?"): Pair<String, AnswerSource> {
        val answerId = assistant.ask(IdempotencyKey(key), AskRequest(text)).answerId
        withTimeout(10_000) { assistant.streamAnswer(answerId).toList() }
        return answerId to liveSource(backend, answerId)
    }

    /** The `source` event as generation emitted it, from the answer's log: a stream may fold it into a snapshot. */
    private fun liveSource(backend: MockBackend, answerId: String): AnswerSource =
        backend.assistant.answers.getValue(answerId).log.filterIsInstance<AnswerEvent.Source>().single().event

    private suspend fun historySource(answerId: String): AnswerSource =
        assertNotNull(assistant.listMessages().messages.single { it.id == answerId }.source)

    private suspend fun replaySource(answerId: String): AnswerSource =
        assertNotNull((withTimeout(10_000) { assistant.streamAnswer(answerId).toList() }.first() as AnswerEvent.Snapshot).event.source)

    private suspend fun changeCategory() {
        operations.changeCategory(opId("k5"), ChangeCategoryRequest("cat_clothes"))
    }

    @Test
    fun `nothing changed since the answer - the list is empty and there is no text`() = runBlocking<Unit> {
        val (id, live) = askAndFinish()
        assertEquals(emptyList(), live.changedSince)
        assertNull(live.changedSinceText)
        assertEquals(emptyList(), historySource(id).changedSince)
        assertNull(historySource(id).changedSinceText)
    }

    @Test
    fun `a category changed after the answer shows in the history and the replay, not in the live event`() = runBlocking<Unit> {
        val (id, live) = askAndFinish()
        changeCategory()

        assertEquals(emptyList(), live.changedSince)
        val history = historySource(id)
        assertEquals(listOf(ChangedSince.CATEGORIES), history.changedSince)
        assertEquals("с тех пор категории менялись", history.changedSinceText)
        // Everything else of the source stays as calculated.
        assertEquals(live.copy(changedSince = history.changedSince, changedSinceText = history.changedSinceText), history)
        assertEquals(history, replaySource(id))
    }

    @Test
    fun `a category changed before the question does not count`() = runBlocking<Unit> {
        changeCategory()
        val (id, live) = askAndFinish()
        assertEquals(emptyList(), live.changedSince)
        assertEquals(emptyList(), historySource(id).changedSince)
    }

    @Test
    fun `calculated_at is the time of calculation and stays when the history is recounted`() = runBlocking<Unit> {
        val (id, live) = askAndFinish()
        assertEquals(OffsetDateTime.parse(NOW), live.calculatedAt)
        // Same instant as the calculation: the order of changes decides, not the clock.
        changeCategory()
        clock.at = clock.at.plusHours(2)
        val history = historySource(id)
        assertEquals(OffsetDateTime.parse(NOW), history.calculatedAt)
        assertEquals(listOf(ChangedSince.CATEGORIES), history.changedSince)
    }

    @Test
    fun `a rejected category change is not a change`() = runBlocking<Unit> {
        val (id, _) = askAndFinish()
        assertFailsWith<AppError.Api> { operations.changeCategory(opId("k5"), ChangeCategoryRequest("cat_no_such")) }
        assertFailsWith<AppError.Api> { operations.changeCategory("no-such-operation", ChangeCategoryRequest("cat_clothes")) }
        assertEquals(emptyList(), historySource(id).changedSince)
    }

    @Test
    fun `the same category again is not a change`() = runBlocking<Unit> {
        changeCategory()
        val (id, _) = askAndFinish()
        changeCategory()
        // «Между своими счетами» for an operation already in a pair: the ledger keeps the pair as it is.
        val pair = assertNotNull(backend.ledger.pairOf(backend.ledger.byId(opId("self"))!!)).id
        operations.changeCategory(pair, ChangeCategoryRequest(SystemCategories.OWN_TRANSFER))
        assertEquals(emptyList(), historySource(id).changedSince)
    }

    @Test
    fun `a category changed while the answer is streaming is reported by its live source event`() = runBlocking<Unit> {
        // Virtual time: generation runs only when the scheduler lets it, and its chunk delays never elapse by themselves.
        val scheduler = TestCoroutineScheduler()
        val clock = TestClock(NOW)
        val backend = testBackend(clock, config = FAST.copy(assistantChunkDelayMs = 1), assistantConsent = true, dispatcher = StandardTestDispatcher(scheduler))
        backend.load(doc)
        val assistant = MockAssistantApi(backend)
        val answerId = assistant.ask(IdempotencyKey("q1"), AskRequest("Сколько я трачу на Krabsbir?")).answerId
        // Generation calculates the answer and stops at the first chunk delay: the data is changed after the calculation.
        scheduler.runCurrent()
        val answer = backend.assistant.answers.getValue(answerId)
        assertEquals(AnswerStatus.GENERATING, answer.status)
        assertTrue(answer.log.isNotEmpty())
        assertTrue(answer.log.none { it is AnswerEvent.Source })
        clock.at = clock.at.plusHours(1)
        MockOperationsApi(backend).changeCategory(backend.ledger.all.single { it.fitId == "k5" }.id, ChangeCategoryRequest("cat_clothes"))

        scheduler.advanceUntilIdle()
        assertEquals(AnswerStatus.COMPLETE, answer.status)
        val live = liveSource(backend, answerId)
        assertEquals(listOf(ChangedSince.CATEGORIES), live.changedSince)
        assertEquals("с тех пор категории менялись", live.changedSinceText)
        // calculated_at is the time of the calculation, not of the event.
        assertEquals(OffsetDateTime.parse(NOW), live.calculatedAt)
        assertEquals(live, assistant.listMessages().messages.single { it.id == answerId }.source)
    }

    @Test
    fun `a statement uploaded after the answer is reported as uploads`() = runBlocking<Unit> {
        val (id, _) = askAndFinish()
        backend.load(statement("2026-10-01", "2026-10-05", trn("k10", "2026-10-02T12:00", 500, "Krabsbir", "Рестораны")), "october.ofx")
        val history = historySource(id)
        assertEquals(listOf(ChangedSince.UPLOADS), history.changedSince)
        assertEquals("с тех пор загружали или удаляли выписки", history.changedSinceText)
    }

    @Test
    fun `a statement deleted after the answer is reported as uploads`() = runBlocking<Unit> {
        val (id, _) = askAndFinish()
        statements.deleteStatement(upload.id)
        assertEquals(listOf(ChangedSince.UPLOADS), historySource(id).changedSince)
    }

    @Test
    fun `deleting a statement that is not there changes nothing`() = runBlocking<Unit> {
        val (id, _) = askAndFinish()
        statements.deleteStatement("no-such-upload")
        assertEquals(emptyList(), historySource(id).changedSince)
    }

    @Test
    fun `both kinds of change give both codes in the order of api md and one text`() = runBlocking<Unit> {
        val (id, _) = askAndFinish()
        statements.deleteStatement(upload.id)
        backend.load(doc, "again.ofx")
        changeCategory()
        val history = historySource(id)
        assertEquals(listOf(ChangedSince.CATEGORIES, ChangedSince.UPLOADS), history.changedSince)
        assertEquals("с тех пор менялись категории и загружали или удаляли выписки", history.changedSinceText)
    }

    @Test
    fun `each answer of the history counts from its own calculation`() = runBlocking<Unit> {
        val (first, _) = askAndFinish("q1")
        changeCategory()
        val (second, live) = askAndFinish("q2")
        assertEquals(emptyList(), live.changedSince)
        assertEquals(listOf(ChangedSince.CATEGORIES), historySource(first).changedSince)
        assertEquals(emptyList(), historySource(second).changedSince)

        backend.load(statement("2026-10-01", "2026-10-05"), "empty.ofx")
        assertEquals(listOf(ChangedSince.CATEGORIES, ChangedSince.UPLOADS), historySource(first).changedSince)
        assertEquals(listOf(ChangedSince.UPLOADS), historySource(second).changedSince)
    }

    @Test
    fun `an answer store without a change log keeps the source it was given`() {
        val source = AnswerSource(calculatedAt = OffsetDateTime.parse(NOW), transferMode = ru.finassist.pf.core.api.model.TransferMode.WITH, changedSince = emptyList())
        val answer = AssistantStore.Answer("a", "q", OffsetDateTime.parse(NOW))
        answer.append(AnswerEvent.Source(source))
        assertEquals(source, answer.snapshot().first.source)
    }

    @Test
    fun `the change log counts in order and builds the ready text`() {
        val changes = DataChanges()
        assertEquals(0, changes.version)
        changes.uploadsChanged()
        val afterUpload = changes.version
        changes.categoriesChanged()
        assertEquals(listOf(ChangedSince.CATEGORIES, ChangedSince.UPLOADS), changes.since(0))
        assertEquals(listOf(ChangedSince.CATEGORIES), changes.since(afterUpload))
        assertEquals(emptyList(), changes.since(changes.version))
        assertNull(DataChanges.text(emptyList()))
        // An unknown code alone has no text of this server: it never sends one.
        assertNull(DataChanges.text(listOf(ChangedSince.UNKNOWN)))
    }
}
