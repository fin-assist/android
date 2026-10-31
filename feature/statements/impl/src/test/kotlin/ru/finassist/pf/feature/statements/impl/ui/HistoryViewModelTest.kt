package ru.finassist.pf.feature.statements.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.ImportProgress
import ru.finassist.pf.core.api.model.ImportStage
import ru.finassist.pf.core.api.model.StatementsList
import ru.finassist.pf.core.api.model.UploadStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.toggles.StaticFlags
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.feature.statements.api.StatementsEvent
import ru.finassist.pf.feature.statements.impl.FakeStatementsApi
import ru.finassist.pf.feature.statements.impl.FakeTokens
import ru.finassist.pf.feature.statements.impl.RecordingTracker
import ru.finassist.pf.feature.statements.impl.data.StatementsRepositoryImpl
import ru.finassist.pf.feature.statements.impl.importResult
import ru.finassist.pf.feature.statements.impl.testDataStore
import ru.finassist.pf.feature.statements.impl.upload
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Profile → «История загрузок»: the list, watching uploads still parsed, deleting an upload. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val api = FakeStatementsApi()
    private val repository = StatementsRepositoryImpl(testDataStore(), FakeTokens(null))
    private val tracker = RecordingTracker()
    private val repoEvents = mutableListOf<StatementsEvent>()
    private lateinit var collector: Job

    private val done = StatementsList(uploads = listOf(upload("u1")))
    private val processing = StatementsList(uploads = listOf(upload("u1", UploadStatus.PROCESSING)))

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        collector = CoroutineScope(dispatcher).launch { repository.events.toList(repoEvents) }
    }

    @After fun tearDown() {
        collector.cancel()
        Dispatchers.resetMain()
    }

    private fun newVm(flags: StaticFlags = StaticFlags(), source: StatementsApi = api) = HistoryViewModel(source, repository, tracker, flags, ElapsedClock { dispatcher.scheduler.currentTime })

    private fun advance(ms: Long) {
        dispatcher.scheduler.advanceTimeBy(ms)
        dispatcher.scheduler.runCurrent()
    }

    private val progress = ImportEvent.Progress(ImportProgress(ImportStage.PARSING, 40))

    @Test fun `the upload button follows the statements upload toggle`() {
        assertTrue(newVm().state.value.uploadEnabled)
        assertFalse(newVm(StaticFlags(mapOf(Flag.STATEMENTS_UPLOAD to false))).state.value.uploadEnabled)
    }

    @Test fun `the list is shown once loaded`() {
        api.listOutcomes += done
        val vm = newVm()
        assertTrue(vm.state.value.loading)
        vm.load()
        assertFalse(vm.state.value.loading)
        assertEquals(done, vm.state.value.list)
        assertTrue(api.streamOpens.isEmpty())
    }

    @Test fun `no network before the first list shows the offline screen`() {
        api.listOutcomes += AppError.Offline()
        val vm = newVm()
        vm.load()
        assertTrue(vm.state.value.offline)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `a failed refresh keeps the shown list`() {
        api.listOutcomes += done
        api.listOutcomes += AppError.Offline()
        val vm = newVm()
        vm.load()
        vm.load()
        assertFalse(vm.state.value.offline)
        assertEquals(done, vm.state.value.list)
    }

    private fun forbiddenListRead() = AppError.Api("FUTURE_READ_REFUSAL", 409,
        response = ru.finassist.pf.core.common.error.ErrorResponseInfo("FUTURE_READ_REFUSAL", 409, requestRetryable = false))

    @Test fun `a successful delete starts a new list context while same-context retries stay forbidden`() {
        val empty = StatementsList(uploads = emptyList())
        api.listOutcomes += done
        api.listOutcomes += forbiddenListRead()
        api.listOutcomes += empty
        val vm = newVm()
        vm.load()
        vm.load()
        assertFalse(vm.state.value.retryReady)
        vm.load()
        assertEquals(2, api.listCalls, "ordinary resume must respect the refusal")

        vm.askDelete(upload("u1"))
        vm.delete()
        assertEquals(listOf("u1"), api.deleted)
        assertEquals(3, api.listCalls, "actual deletion makes the previous list context obsolete")
        assertEquals(empty, vm.state.value.list)
        assertNull(vm.state.value.requestRetryable)
        assertTrue(vm.state.value.retryReady)
    }

    @Test fun `a terminal import outcome refreshes a previously forbidden list in its new data context`() {
        for (success in listOf(true, false)) {
            val finish = CompletableDeferred<Unit>()
            val result = importResult()
            val after = if (success) done else StatementsList(uploads = emptyList())
            api.listOutcomes.clear()
            api.listOutcomes += processing
            api.listOutcomes += forbiddenListRead()
            api.listOutcomes += after
            api.script("u1", flow {
                emit(progress)
                finish.await()
                if (success) emit(ImportEvent.Complete(result))
                else emit(ImportEvent.Error(ErrorCodes.PROCESSING_FAILED, "private diagnostic"))
            })
            val callsBefore = api.listCalls
            val vm = newVm()
            vm.load()
            vm.load()
            vm.load()
            assertEquals(callsBefore + 2, api.listCalls)
            assertFalse(vm.state.value.retryReady)
            finish.complete(Unit)
            assertEquals(callsBefore + 3, api.listCalls)
            assertEquals(after, vm.state.value.list)
            assertTrue(vm.state.value.retryReady)
            assertNull(vm.state.value.requestRetryable)
            vm.pause()
        }
    }

    @Test fun `an old read refusal cannot poison the successful post-delete list context`() {
        val releaseOld = CompletableDeferred<Unit>()
        val empty = StatementsList(uploads = emptyList())
        var calls = 0
        val source = object : StatementsApi by api {
            override suspend fun listStatements(): StatementsList = when (++calls) {
                1 -> done
                2 -> { releaseOld.await(); throw forbiddenListRead() }
                else -> empty
            }
        }
        val vm = newVm(source = source)
        vm.load()
        vm.load() // old context still in flight
        vm.askDelete(upload("u1"))
        vm.delete()
        assertEquals(empty, vm.state.value.list)
        releaseOld.complete(Unit)
        assertEquals(empty, vm.state.value.list)
        assertNull(vm.state.value.errorPresentation)
        assertNull(vm.state.value.requestRetryable)
        assertTrue(vm.state.value.retryReady)
        vm.load()
        assertEquals(4, calls, "the stale refusal must not close the new context's retry gate")
    }

    @Test fun `an old successful read cannot watch deleted uploads or reopen a newer forbidden context`() {
        val releaseOld = CompletableDeferred<Unit>()
        var calls = 0
        val source = object : StatementsApi by api {
            override suspend fun listStatements(): StatementsList = when (++calls) {
                1 -> done
                2 -> { releaseOld.await(); processing }
                else -> throw forbiddenListRead()
            }
        }
        api.script("u1", flowOf(ImportEvent.Complete(importResult())))
        val vm = newVm(source = source)
        vm.load()
        vm.load()
        vm.askDelete(upload("u1"))
        vm.delete()
        assertEquals(3, calls)
        assertFalse(vm.state.value.retryReady)
        releaseOld.complete(Unit)
        assertTrue(api.streamOpens.isEmpty(), "a stale list must not start watchers for deleted data")
        assertEquals(false, vm.state.value.requestRetryable)
        assertFalse(vm.state.value.retryReady)
        vm.load()
        assertEquals(3, calls, "the new response's false still controls the current request")
    }

    @Test fun `an upload being parsed is watched and the list reloads when it completes`() {
        val result = importResult()
        api.listOutcomes += processing
        api.listOutcomes += done
        api.script("u1", flowOf(progress, ImportEvent.Complete(result)))
        val vm = newVm()
        vm.load()
        assertEquals(listOf("u1"), api.streamOpens)
        assertEquals(2, api.listCalls)
        assertEquals(done, vm.state.value.list)
        // Feed and analytics learn about the new data.
        assertEquals(result, repository.lastResult("u1"))
        assertEquals(listOf<StatementsEvent>(StatementsEvent.ImportCompleted("u1", result)), repoEvents)
    }

    @Test fun `a failed parse reloads the list without a result`() {
        api.listOutcomes += processing
        api.listOutcomes += done
        api.script("u1", flowOf(ImportEvent.Error(ErrorCodes.PROCESSING_FAILED, "failed")))
        val vm = newVm()
        vm.load()
        assertEquals(2, api.listCalls)
        assertNull(repository.lastResult("u1"))
        assertTrue(repoEvents.isEmpty())
    }

    @Test fun `terminal import warning survives the list refresh without REST action dispatch`() {
        val presentation = ru.finassist.pf.core.common.error.ErrorPresentation(
            ru.finassist.pf.core.common.error.UserMessage.from("Проверьте выбранную выписку"),
            ru.finassist.pf.core.common.error.ErrorSeverity.WARNING,
        )
        api.listOutcomes += processing
        api.listOutcomes += done
        api.script("u1", flowOf(ImportEvent.Error("MAINTENANCE", "diagnostic", presentation)))
        val vm = newVm()
        vm.load()
        assertEquals(done, vm.state.value.list)
        assertEquals(presentation, vm.state.value.resultErrorPresentation)
        assertNull(vm.state.value.requestRetryable)
        assertTrue(repoEvents.isEmpty())
    }

    /**
     * The pauses are random in [d/2; d] with d = 1, 2, 4 s (api.md «SSE», schedule tested in `SseReopenTest`): the n-th
     * re-open comes after 2^n − 1.5 s at the earliest and 2^n − 1 s at the latest, so these checkpoints hold for any jitter.
     */
    @Test fun `a dropped stream is re-opened with backoff`() {
        api.listOutcomes += processing
        api.listOutcomes += done
        api.script(
            "u1",
            flow { throw AppError.Offline() },
            // Closed without `complete` / `error`: a drop as well.
            flowOf(progress),
            flow { throw AppError.Server(502) },
            flowOf(ImportEvent.Complete(importResult())),
        )
        val vm = newVm()
        vm.load()
        assertEquals(1, api.streamOpens.size)
        advance(1_000)
        assertEquals(2, api.streamOpens.size)
        advance(2_000)
        assertEquals(3, api.streamOpens.size)
        advance(4_000)
        assertEquals(4, api.streamOpens.size)
        assertEquals(2, api.listCalls)
        assertEquals(done, vm.state.value.list)
    }

    @Test fun `coming back during a 429 pause waits again and keeps watching, one stream at a time`() {
        var active = 0
        var maxActive = 0
        fun tracked(f: kotlinx.coroutines.flow.Flow<ImportEvent>) = f
            .onStart { active++; maxActive = maxOf(maxActive, active) }
            .onCompletion { active-- }
        val limitedAt = mutableListOf<Long>()
        val limited = { tracked(flow { limitedAt += dispatcher.scheduler.currentTime; throw AppError.RateLimited(null, retryAfterSeconds = 60) }) }
        api.listOutcomes += processing
        api.listOutcomes += processing
        api.listOutcomes += done
        val result = importResult()
        api.script(
            "u1",
            // Closed after progress: a drop.
            tracked(flowOf(progress)),
            limited(),
            // The next opening after the retained wait gets a fresh 429 with its own wait.
            limited(),
            tracked(flowOf(ImportEvent.Complete(result))),
        )
        val vm = newVm()
        vm.load()
        advance(1_000)
        assertEquals(2, api.streamOpens.size, "the drop was re-opened and got 429")
        advance(4_000) // consume part of the original server wait before leaving
        vm.pause()
        vm.load()
        assertEquals(2, api.streamOpens.size, "resume must not bypass the retained Retry-After")
        assertEquals(2, api.listCalls, "no reload before the upload ends")
        val firstRefusal = limitedAt.single()
        advance(firstRefusal + 59_999 - dispatcher.scheduler.currentTime)
        assertEquals(2, api.streamOpens.size, "not before the original Retry-After")
        advance(firstRefusal + 62_001 - dispatcher.scheduler.currentTime)
        assertEquals(3, api.streamOpens.size, "the original wait is not restarted on resume")
        val secondRefusal = limitedAt.last()
        advance(secondRefusal + 59_999 - dispatcher.scheduler.currentTime)
        assertEquals(3, api.streamOpens.size, "a fresh 429 gets its own Retry-After")
        advance(secondRefusal + 62_001 - dispatcher.scheduler.currentTime)
        assertEquals(4, api.streamOpens.size)
        assertEquals(3, api.listCalls, "the list is reloaded once the upload ended")
        assertEquals(done, vm.state.value.list)
        assertEquals(result, repository.lastResult("u1"))
        assertEquals(1, maxActive, "never two streams at once")
    }

    @Test fun `known false maintenance resumes only the existing history stream after its wait`() {
        api.listOutcomes += processing
        api.listOutcomes += processing
        api.listOutcomes += done
        val result = importResult()
        api.script("u1", flow { throw AppError.Maintenance(null, 15,
            ru.finassist.pf.core.common.error.ErrorResponseInfo("MAINTENANCE", 503, requestRetryable = false,
                timing = ru.finassist.pf.core.common.time.ResponseTiming(receivedElapsedMillis = 0))) },
            flowOf(ImportEvent.Complete(result)))
        val vm = newVm()
        vm.load()
        assertEquals(1, api.streamOpens.size)
        vm.pause()
        vm.load() // a stream denial must not poison the distinct list request's retry gate
        assertEquals(2, api.listCalls)
        advance(14_999)
        assertEquals(1, api.streamOpens.size)
        advance(1)
        assertEquals(2, api.streamOpens.size)
        assertEquals(done, vm.state.value.list)
        assertEquals(result, repository.lastResult("u1"))
    }

    @Test fun `a stream error other than the network ends watching without a reload`() {
        api.listOutcomes += processing
        api.script("u1", flow { throw AppError.Api(ErrorCodes.NOT_FOUND, 404) })
        val vm = newVm()
        vm.load()
        advance(60_000)
        assertEquals(1, api.streamOpens.size)
        assertEquals(1, api.listCalls)
    }

    @Test fun `streams stop on pause and are re-opened on the next load, once`() {
        api.listOutcomes += processing
        val vm = newVm()
        vm.load()
        // Already watched: another load does not open a second stream.
        vm.load()
        assertEquals(1, api.streamOpens.size)
        vm.pause()
        vm.load()
        assertEquals(2, api.streamOpens.size)
    }

    @Test fun `deleting an upload asks first, then reloads and announces the change`() {
        api.listOutcomes += done
        api.listOutcomes += StatementsList(uploads = emptyList())
        val vm = newVm()
        vm.load()
        vm.askDelete(upload("u1"))
        assertEquals(upload("u1"), vm.state.value.confirmDelete)

        vm.delete()
        assertEquals(listOf("u1"), api.deleted)
        val s = vm.state.value
        assertNull(s.confirmDelete)
        assertFalse(s.deleting)
        assertEquals("Загрузка удалена, обновляем аналитику", s.snackbar)
        assertEquals(StatementsList(uploads = emptyList()), s.list)
        assertEquals(listOf<StatementsEvent>(StatementsEvent.UploadDeleted("u1")), repoEvents)
        assertTrue(Events.STATEMENTS_UPLOAD_DELETED in tracker.names())

        vm.consumeSnackbar()
        assertNull(vm.state.value.snackbar)
    }

    @Test fun `a confirmed delete stays visible when the following list read fails`() {
        val deleted = upload("u1")
        for (remaining in listOf(emptyList(), listOf(upload("u2")))) {
            for (failure in listOf(AppError.Offline(), AppError.Server(503))) {
                val source = FakeStatementsApi()
                source.listOutcomes += StatementsList(
                    summary = ru.finassist.pf.core.api.model.StatementsSummary(1 + remaining.size,
                        10 * (1 + remaining.size), deleted.uploadedAt, deleted.uploadedAt, emptyList()),
                    uploads = listOf(deleted) + remaining,
                )
                source.listOutcomes += failure
                val vm = newVm(source = source)
                vm.load()
                vm.askDelete(deleted); vm.delete()
                assertEquals(listOf("u1"), source.deleted)
                assertEquals(remaining, vm.state.value.list?.uploads,
                    "the failed GET must not resurrect a source removed by a confirmed DELETE")
                assertNull(vm.state.value.list?.summary, "old aggregate coverage is no longer authoritative")
                assertEquals(HistoryErrorOrigin.READ, vm.state.value.errorOrigin)
                assertEquals(failure is AppError.Offline, vm.state.value.errorPresentation?.connectionFailure)
                assertNull(vm.state.value.snackbar, "the read failure uses its own fallback and retry")
                assertTrue(vm.state.value.retryReady)
                source.listOutcomes.clear(); source.listOutcomes += StatementsList(uploads = remaining)
                vm.load()
                assertNull(vm.state.value.errorOrigin)
                assertNull(vm.state.value.errorPresentation)
                assertEquals(remaining, vm.state.value.list?.uploads)
            }
        }
    }

    @Test fun `deleting offline keeps the upload and says why`() {
        api.listOutcomes += done
        api.deleteFailure = AppError.Offline()
        val vm = newVm()
        vm.load()
        vm.askDelete(upload("u1"))
        vm.delete()
        assertEquals("Загрузка не удалена", vm.state.value.snackbar)
        assertTrue(vm.state.value.errorPresentation?.connectionFailure == true)
        assertNull(vm.state.value.confirmDelete)
        assertFalse(vm.state.value.deleting)
        assertEquals(1, api.listCalls)
        assertTrue(repoEvents.isEmpty())
    }

    @Test fun `a failed delete outcome cannot replace a later list failure and its retry`() {
        api.listOutcomes += done
        api.deleteFailure = AppError.Offline()
        val vm = newVm()
        vm.load()
        vm.askDelete(upload("u1")); vm.delete()
        assertEquals(HistoryErrorOrigin.DELETE, vm.state.value.errorOrigin)
        assertEquals("Загрузка не удалена", vm.state.value.snackbar)
        api.listOutcomes.clear(); api.listOutcomes += AppError.Offline()
        vm.load()
        assertEquals(HistoryErrorOrigin.READ, vm.state.value.errorOrigin)
        assertTrue(vm.state.value.errorPresentation?.connectionFailure == true)
        assertNull(vm.state.value.snackbar, "the read error retains its own retry action")
        api.deleteFailure = null
        api.listOutcomes.clear(); api.listOutcomes += StatementsList(uploads = emptyList())
        vm.askDelete(upload("u1")); vm.delete()
        assertNull(vm.state.value.errorOrigin)
        assertNull(vm.state.value.errorPresentation)
        assertEquals("Загрузка удалена, обновляем аналитику", vm.state.value.snackbar)
        vm.load()
        assertEquals("Загрузка удалена, обновляем аналитику", vm.state.value.snackbar,
            "ordinary successful notifications survive a later successful refresh")
    }

    @Test fun `clearing every history error removes its outcome without deleting success notices`() {
        val notification = HistoryUiState(snackbar = "Загрузка удалена")
        assertEquals(notification, notification.clearRequestError())
        for (origin in HistoryErrorOrigin.entries) {
            val cleared = notification.copy(errorOrigin = origin, errorPresentation = AppError.Offline().presentation,
                snackbar = "Старый исход ошибки").clearRequestError()
            assertNull(cleared.errorOrigin)
            assertNull(cleared.errorPresentation)
            assertNull(cleared.snackbar)
        }
    }

    @Test fun `retrying a deletion clears its old failed outcome while pending`() {
        api.listOutcomes += done
        api.deleteFailure = AppError.Offline()
        val vm = newVm()
        vm.load()
        vm.askDelete(upload("u1")); vm.delete()
        assertEquals("Загрузка не удалена", vm.state.value.snackbar)
        val pending = CompletableDeferred<Unit>()
        api.deleteFailure = null
        api.deleteGate = pending
        vm.askDelete(upload("u1")); vm.delete()
        assertTrue(vm.state.value.deleting)
        assertNull(vm.state.value.snackbar)
        assertNull(vm.state.value.errorPresentation)
        api.listOutcomes.clear()
        api.listOutcomes += StatementsList(uploads = emptyList())
        pending.complete(Unit)
        assertFalse(vm.state.value.deleting)
        assertEquals("Загрузка удалена, обновляем аналитику", vm.state.value.snackbar)
        assertEquals(emptyList(), vm.state.value.list?.uploads)
    }

    @Test fun `a failed delete offers to try again`() {
        api.listOutcomes += done
        api.deleteFailure = AppError.Server(500)
        val vm = newVm()
        vm.askDelete(upload("u1"))
        vm.delete()
        assertEquals("Не получилось удалить. Попробуйте ещё раз", vm.state.value.snackbar)
    }

    @Test fun `delete without a confirmed upload does nothing`() {
        val vm = newVm()
        vm.askDelete(upload("u1"))
        vm.askDelete(null)
        vm.delete()
        assertTrue(api.deleted.isEmpty())
    }

    @Test fun `an empty stream before a terminal event is not the end`() {
        api.listOutcomes += processing
        api.script("u1", emptyFlow())
        val vm = newVm()
        vm.load()
        advance(1_000)
        assertEquals(2, api.streamOpens.size)
        assertEquals(1, api.listCalls)
    }
    @Test fun `pull refresh retains uploads and delete confirmation and never repeats deletion`() {
        val pending = CompletableDeferred<StatementsList>()
        var calls = 0
        val source = object : StatementsApi by api {
            override suspend fun listStatements(): StatementsList {
                calls++
                return if (calls == 1) done else pending.await()
            }
        }
        val vm = newVm(source = source)
        vm.load()
        vm.askDelete(done.uploads.first())
        vm.refresh()
        vm.refresh()
        assertEquals(2, calls)
        assertTrue(vm.state.value.refreshing)
        assertEquals(done, vm.state.value.list)
        assertEquals(done.uploads.first(), vm.state.value.confirmDelete)
        pending.completeExceptionally(AppError.Offline())
        assertFalse(vm.state.value.refreshing)
        assertEquals(done, vm.state.value.list)
        assertTrue(vm.state.value.errorPresentation?.connectionFailure == true)
        assertTrue(api.deleted.isEmpty())
    }

}
