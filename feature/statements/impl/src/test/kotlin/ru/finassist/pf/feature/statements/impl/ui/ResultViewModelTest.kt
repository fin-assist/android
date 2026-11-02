package ru.finassist.pf.feature.statements.impl.ui

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.SourceTimeZone
import ru.finassist.pf.core.toggles.StaticFlags
import ru.finassist.pf.feature.statements.api.StatementsEvent
import ru.finassist.pf.feature.statements.api.StatementsRepository
import ru.finassist.pf.feature.statements.impl.FakeStatementsApi
import ru.finassist.pf.feature.statements.impl.importResult
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class ResultViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val api = FakeStatementsApi()
    @Before fun setup() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    @After fun teardown() = Dispatchers.resetMain()
    private fun vm(result: ImportResult) = ResultViewModel(SavedStateHandle(mapOf("uploadId" to "u1")), object : StatementsRepository {
        override val events = emptyFlow<StatementsEvent>()
        override fun lastResult(uploadId: String) = result
        override suspend fun pendingAnalyticsImport(): Long? = null
        override suspend fun analyticsImportReady(revision: Long) = Unit
        override suspend fun consumeFirstImportHints() = false
    }, StaticFlags(), api, ElapsedClock { scheduler.currentTime })

    @Test fun `unsupported initial result shows exact warning and only explicit retry opens a stream`() {
        val vm = vm(importResult().copy(timeZone = "Area/Future_City"))
        assertNull(vm.state.value.result)
        assertEquals(SourceTimeZone.MESSAGE, vm.state.value.error!!.textOrFallback("fallback"))
        scheduler.advanceTimeBy(60_000)
        assertTrue(api.streamOpens.isEmpty())
        api.script("u1", flow { throw AppError.Offline() }, flowOf(ImportEvent.Complete(importResult().copy(timeZone = "Asia/Vladivostok"))))
        vm.retry()
        assertEquals(SourceTimeZone.MESSAGE, vm.state.value.error!!.textOrFallback("fallback"))
        vm.retry()
        assertEquals("Asia/Vladivostok", vm.state.value.result!!.timeZone)
        assertNull(vm.state.value.error)
    }
    @Test fun `bad refreshed result preserves the old verified cache and successful retry clears the notice`() {
        val original = importResult()
        val vm = vm(original)
        api.script("u1", flowOf(ImportEvent.Complete(original.copy(timeZone = "Area/Future_City"))), flowOf(ImportEvent.Complete(original)))
        vm.retry()
        assertEquals(original, vm.state.value.result)
        assertNotNull(vm.state.value.error)
        vm.retry()
        assertNull(vm.state.value.error)
        assertEquals(2, api.streamOpens.size)
    }
    @Test fun `a progress stream without a terminal reply times out without reopening itself`() {
        val vm = vm(importResult())
        vm.retry()
        assertTrue(vm.state.value.retrying)
        scheduler.advanceTimeBy(30_001); scheduler.runCurrent()
        assertFalse(vm.state.value.retrying)
        assertNotNull(vm.state.value.error)
        assertEquals(listOf("u1"), api.streamOpens)
    }

    @Test fun `empty stream returns a retryable failure and wait permission blocks repeated taps`() {
        val vm = vm(importResult())
        api.script("u1", emptyFlow(), flow { throw AppError.RateLimited(null, 3) }, flowOf(ImportEvent.Complete(importResult())))
        vm.retry()
        assertFalse(vm.state.value.retrying)
        assertNotNull(vm.state.value.error)
        vm.retry()
        assertFalse(vm.state.value.retryReady)
        vm.retry()
        assertEquals(2, api.streamOpens.size)
        scheduler.advanceTimeBy(3_001); scheduler.runCurrent()
        vm.retry()
        assertNull(vm.state.value.error)
    }
}
