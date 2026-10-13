package ru.finassist.pf.feature.statements.impl.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
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

    private fun newVm(flags: StaticFlags = StaticFlags()) = HistoryViewModel(api, repository, tracker, flags)

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
        // The progress event reset the backoff to one second.
        advance(1_000)
        assertEquals(3, api.streamOpens.size)
        advance(1_999)
        assertEquals(3, api.streamOpens.size)
        advance(1)
        assertEquals(4, api.streamOpens.size)
        assertEquals(2, api.listCalls)
        assertEquals(done, vm.state.value.list)
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
        assertEquals("Загрузка удалена, аналитика пересчитана", s.snackbar)
        assertEquals(StatementsList(uploads = emptyList()), s.list)
        assertEquals(listOf<StatementsEvent>(StatementsEvent.UploadDeleted("u1")), repoEvents)
        assertTrue(Events.STATEMENTS_UPLOAD_DELETED in tracker.names())

        vm.consumeSnackbar()
        assertNull(vm.state.value.snackbar)
    }

    @Test fun `deleting offline keeps the upload and says why`() {
        api.listOutcomes += done
        api.deleteFailure = AppError.Offline()
        val vm = newVm()
        vm.load()
        vm.askDelete(upload("u1"))
        vm.delete()
        assertEquals("Нет сети — загрузка не удалена", vm.state.value.snackbar)
        assertNull(vm.state.value.confirmDelete)
        assertFalse(vm.state.value.deleting)
        assertEquals(1, api.listCalls)
        assertTrue(repoEvents.isEmpty())
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
}
