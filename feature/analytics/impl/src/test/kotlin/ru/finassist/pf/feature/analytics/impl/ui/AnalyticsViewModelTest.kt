package ru.finassist.pf.feature.analytics.impl.ui

import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.blocks.CatalogV1
import ru.finassist.pf.core.toggles.Experiment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.finassist.pf.core.api.AnalyticsApi
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.AnalyticsPeriodItem
import ru.finassist.pf.core.api.model.AnalyticsPeriodsList
import ru.finassist.pf.core.api.model.AnalyticsState
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AskResponse
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.MessagesList
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.ReportZone
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.operations.api.OperationsRepository
import ru.finassist.pf.feature.statements.api.StatementsEvent
import ru.finassist.pf.feature.statements.api.StatementsRepository
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The main dispatcher runs on [scheduler]: the 3 s re-request while the server recalculates is driven by hand. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModelTest {
    private data class Request(val period: PeriodTypeCode?, val date: PeriodKey?, val mode: TransferMode?)

    private class FakeApi : AnalyticsApi {
        val requests = mutableListOf<Request>()
        /** Answers in order; the last one repeats. An [AppError] in the list is thrown. */
        val answers = ArrayDeque<Any>()
        val periodRequests = mutableListOf<PeriodTypeCode?>()
        var reply: (suspend () -> Analytics)? = null
        var periodsFailure: AppError? = null
        var periodsGate: CompletableDeferred<Unit>? = null
        var periodsReply: (suspend (PeriodTypeCode?) -> AnalyticsPeriodsList)? = null
        val dismissed = mutableListOf<String>()
        var dismissFailure: AppError? = null
        var dismissReply: (suspend (String) -> Unit)? = null

        /** `supports` and `exp` of every request (FIN-4, FIN-9). */
        val extras = mutableListOf<Pair<String?, String?>>()

        override suspend fun getAnalytics(period: PeriodTypeCode?, date: PeriodKey?, transferMode: TransferMode?, supports: String?, exp: String?): Analytics {
            requests += Request(period, date, transferMode)
            extras += supports to exp
            reply?.let { return it() }
            val next = if (answers.size > 1) answers.removeFirst() else answers.first()
            if (next is AppError) throw next
            return next as Analytics
        }

        override suspend fun listPeriods(period: PeriodTypeCode?): AnalyticsPeriodsList {
            periodRequests += period
            periodsReply?.let { return it(period) }
            periodsGate?.await()
            periodsFailure?.let { throw it }
            return AnalyticsPeriodsList(listOf(AnalyticsPeriodItem(PeriodKey("2026-09"), DateRange(at(1), at(30)), Coverage.COMPLETE)))
        }

        override suspend fun dismissRegularPayment(id: String) {
            dismissFailure?.let { throw it }
            dismissed += id
            dismissReply?.invoke(id)
        }

        override suspend fun restoreRegularPayment(id: String) = Unit
    }

    private class FakeAssistant : AssistantApi {
        var limitCalls = 0
        var failure: AppError? = null
        var reply: (suspend () -> AssistantLimit)? = null
        var limit = AssistantLimit(3, 5, at(30))
        override suspend fun getLimit(): AssistantLimit {
            limitCalls++
            reply?.let { return it() }
            failure?.let { throw it }
            return limit
        }
        override suspend fun listMessages(limit: Int?, beforeId: String?): MessagesList = error("unused")
        override suspend fun ask(key: IdempotencyKey, request: AskRequest): AskResponse = error("unused")
        override fun streamAnswer(answerId: String): Flow<AnswerEvent> = error("unused")
        override suspend fun retry(key: IdempotencyKey, answerId: String): RetryResponse = error("unused")
    }

    private class FakeStatements : StatementsRepository {
        var firstImportHints = false
        var pendingImport: Long? = null
        val acknowledged = mutableListOf<Long>()
        override suspend fun pendingAnalyticsImport(): Long? = pendingImport
        override suspend fun analyticsImportReady(revision: Long) {
            acknowledged += revision
            if (pendingImport == revision) pendingImport = null
        }
        override val events = MutableSharedFlow<StatementsEvent>(extraBufferCapacity = 4)
        override fun lastResult(uploadId: String): ImportResult? = null
        override suspend fun consumeFirstImportHints(): Boolean = firstImportHints.also { firstImportHints = false }
    }

    private class FakeOperations : OperationsRepository {
        override val categoryChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    }

    private class FakeFlags(
        private val values: MutableMap<Flag, Boolean> = mutableMapOf(),
        val variants: MutableMap<Experiment, String> = mutableMapOf(),
    ) : FeatureFlags {
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        override fun isEnabled(flag: Flag): Boolean = values[flag] ?: flag.defaultValue
        override fun variant(experiment: Experiment): String = variants[experiment] ?: experiment.defaultVariant
        override val version: String = "test"
        override suspend fun setUser(userId: String?) = Unit
        override suspend fun refresh() = Unit
        fun set(flag: Flag, on: Boolean) {
            values[flag] = on
            changes.tryEmit(Unit)
        }

        fun set(experiment: Experiment, variant: String) {
            variants[experiment] = variant
            changes.tryEmit(Unit)
        }
    }

    private class FakeTracker : Tracker {
        val events = mutableListOf<Pair<Event, Map<String, String>>>()
        override fun track(event: Event, params: Map<String, String>) {
            events += event to params
        }
        override fun setUserId(userId: String?) = Unit
    }

    private val scheduler = TestCoroutineScheduler()
    private val api = FakeApi()
    private val assistant = FakeAssistant()
    private val statements = FakeStatements()
    private val operations = FakeOperations()
    private val flags = FakeFlags()
    private val tracker = FakeTracker()

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm(zone: ReportZone = reportZone) = AnalyticsViewModel(api, assistant, statements, operations, flags, tracker, zone, ru.finassist.pf.core.common.time.ElapsedClock { scheduler.currentTime })

    private fun opened(params: AnalyticsParams? = null) = vm().apply { init(params) }

    private fun data(
        period: PeriodTypeCode = PeriodTypeCode.MONTH,
        date: String = "2026-09",
        mode: TransferMode = TransferMode.WITH,
        hasData: Boolean = true,
        recalculating: Boolean = false,
    ) = Analytics(
        hasData = hasData,
        params = AnalyticsParams(period, PeriodKey(date), mode),
        state = AnalyticsState(
            stale = false, dataFrom = at(1), dataTo = at(30), fullMonths = 3, unreadLinesCount = 0,
            calculatedAt = at(30), recalculating = recalculating,
        ),
    )

    @Test fun `rolling selections keep singleton requests and restore each calendar choice with transfer mode`() {
        api.answers += data(mode = TransferMode.WITHOUT)
        api.answers += data(PeriodTypeCode.ROLLING_YEAR, "rolling_year", TransferMode.WITHOUT)
        api.answers += data(PeriodTypeCode.ROLLING_QUARTER, "rolling_quarter", TransferMode.WITHOUT)
        api.answers += data(mode = TransferMode.WITHOUT)
        val vm = opened(AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITHOUT))
        vm.setPeriodType(PeriodTypeCode.ROLLING_YEAR)
        assertEquals(Request(PeriodTypeCode.ROLLING_YEAR, PeriodKey.ROLLING_YEAR, TransferMode.WITHOUT), api.requests.last())
        vm.setPeriodType(PeriodTypeCode.ROLLING_QUARTER)
        assertEquals(Request(PeriodTypeCode.ROLLING_QUARTER, PeriodKey.ROLLING_QUARTER, TransferMode.WITHOUT), api.requests.last())
        vm.setPeriodType(PeriodTypeCode.MONTH)
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITHOUT), api.requests.last())
    }

    @Test fun `failed rolling switch preserves the prior screen and retries the exact singleton and mode`() {
        val previous = data(mode = TransferMode.WITHOUT)
        api.answers += previous
        api.answers += AppError.Offline()
        api.answers += data(PeriodTypeCode.ROLLING_QUARTER, "rolling_quarter", TransferMode.WITHOUT)
        val vm = opened(AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITHOUT))
        vm.setPeriodType(PeriodTypeCode.ROLLING_QUARTER)
        assertEquals(previous, vm.state.value.data)
        assertTrue(vm.state.value.periodChangeFailed)
        vm.retryPeriodChange()
        assertEquals(Request(PeriodTypeCode.ROLLING_QUARTER, PeriodKey.ROLLING_QUARTER, TransferMode.WITHOUT), api.requests.last())
        assertEquals(api.requests[1], api.requests[2])
        assertEquals(PeriodTypeCode.ROLLING_QUARTER, vm.state.value.data!!.params!!.period)
    }

    @Test fun `edge chart action opens its exact calendar month without changing transfer mode`() {
        api.answers += data(PeriodTypeCode.ROLLING_YEAR, "rolling_year", TransferMode.WITHOUT)
        api.answers += data(date = "2025-10", mode = TransferMode.WITHOUT)
        val vm = opened(AnalyticsParams(PeriodTypeCode.ROLLING_YEAR, PeriodKey.ROLLING_YEAR, TransferMode.WITHOUT))
        val block = Block(id = "monthly_chart", type = BlockType.CHART)
        val action = BlockAction(ActionType.SELECT_ANALYTICS_PERIOD, period = PeriodTypeCode.MONTH, date = PeriodKey("2025-10"))
        vm.onAction(block, action) { error("period selection must not navigate away") }
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2025-10"), TransferMode.WITHOUT), api.requests.last())
        vm.onAction(block, action) { error("duplicate month must remain here") }
        assertEquals(2, api.requests.size)
    }

    private fun names() = tracker.events.map { it.first.name }

    private val reportZone = ReportZone { java.time.ZoneId.of("Europe/Moscow") }

    @Test fun `a new profile zone re-requests the shown period, the first known zone does not (FIN-92)`() {
        api.answers += data(date = "2026-09")
        api.answers += data(date = "2026-09")
        reportZone.set("Europe/Moscow")
        val vm = opened()
        assertEquals(1, api.requests.size)

        reportZone.set("Asia/Vladivostok")
        assertEquals(2, api.requests.size)
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH), api.requests.last())
        assertFalse(vm.state.value.loading)
    }

    @Test fun `the tab root asks for the server's default period and adopts the answer's parameters`() {
        api.answers += data(date = "2026-09")
        val vm = opened()
        assertEquals(listOf(Request(PeriodTypeCode.MONTH, null, TransferMode.WITH)), api.requests)
        assertFalse(vm.state.value.loading)
        assertEquals(PeriodKey("2026-09"), vm.state.value.data?.params?.date)
        assertEquals(listOf(Events.ANALYTICS_OPENED.name), names())

        vm.load()
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH), api.requests.last())
    }

    @Test fun `opening twice (recomposition, back stack) loads once`() {
        api.answers += data()
        val vm = opened()
        vm.init(null)
        assertEquals(1, api.requests.size)
        assertEquals(1, tracker.events.size)
    }

    @Test fun `the period route opens the given parameters`() {
        api.answers += data(PeriodTypeCode.QUARTER, "2026-Q3", TransferMode.WITHOUT)
        val vm = opened(AnalyticsParams(PeriodTypeCode.QUARTER, PeriodKey("2026-Q3"), TransferMode.WITHOUT))
        assertEquals(Request(PeriodTypeCode.QUARTER, PeriodKey("2026-Q3"), TransferMode.WITHOUT), api.requests.single())
        assertEquals(TransferMode.WITHOUT, vm.currentTransferMode())
    }

    @Test fun `with the transfers filter off every request is made with transfers`() {
        flags.set(Flag.ANALYTICS_FILTER_TRANSFERS, false)
        api.answers += data(mode = TransferMode.WITHOUT)
        val vm = opened(AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITHOUT))
        assertEquals(TransferMode.WITH, api.requests.single().mode)
        // Even when the server answers `without`.
        assertEquals(TransferMode.WITH, vm.currentTransferMode())
    }

    @Test fun `an unknown transfer mode in the answer is handled as with`() {
        api.answers += data(mode = TransferMode.UNKNOWN)
        assertEquals(TransferMode.WITH, opened().currentTransferMode())
    }

    @Test fun `the blocks follow their flags`() {
        val off = Flag.entries.associateWith { false }.toMutableMap()
        val vm = AnalyticsViewModel(api, assistant, statements, operations, FakeFlags(off), tracker, reportZone, ru.finassist.pf.core.common.time.ElapsedClock { scheduler.currentTime })
        assertEquals(
            AnalyticsBlocks(
                hidden = setOf(
                    "tiles", "expense_categories", "income_categories", "monthly_chart",
                    "regular_payments", "notable_spending", "bank_fees", "small_frequent",
                ),
                transfersFilter = false, assistant = false, upload = false,
            ),
            vm.state.value.blocks,
        )

        val on = AnalyticsViewModel(api, assistant, statements, operations, FakeFlags(Flag.entries.associateWith { true }.toMutableMap()), tracker, reportZone, ru.finassist.pf.core.common.time.ElapsedClock { scheduler.currentTime })
        assertEquals(AnalyticsBlocks(), on.state.value.blocks)
    }

    @Test fun `a single block switched off remotely disappears from the open screen`() {
        api.answers += data()
        val vm = opened()
        flags.set(Flag.ANALYTICS_BLOCK_BANK_FEES, false)
        assertEquals(setOf("bank_fees"), vm.state.value.blocks.hidden)
        assertEquals(1, api.requests.size) // a block is not a request parameter
    }

    @Test fun `the transfers filter switched off remotely re-requests the shown period with transfers`() {
        api.answers += data(mode = TransferMode.WITHOUT)
        val vm = opened(AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITHOUT))
        flags.set(Flag.ANALYTICS_FILTER_TRANSFERS, false)
        assertFalse(vm.state.value.blocks.transfersFilter)
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH), api.requests.last())
        assertEquals(TransferMode.WITH, vm.currentTransferMode())
    }

    @Test fun `no network on the first load is the offline state without a snackbar`() {
        api.answers += AppError.Offline()
        api.answers += data()
        val vm = opened()
        assertTrue(vm.state.value.offline)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.snackbar)

        vm.load()
        assertFalse(vm.state.value.offline)
        assertTrue(vm.state.value.data != null)
    }

    @Test fun `a failed switch retains visible data without a transient snackbar`() {
        api.answers += periodData(PeriodTypeCode.MONTH, "2026-09")
        api.answers += AppError.Server(500)
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(PeriodKey("2026-09"), vm.state.value.data?.params?.date)
        assertNull(vm.state.value.snackbar, "period failure needs a durable notice with an explicit retry")
    }

    @Test fun `a failed period switch keeps the shown period and says so`() {
        api.answers += data(PeriodTypeCode.MONTH, "2026-09")
        api.answers += AppError.Offline()
        api.answers += data(PeriodTypeCode.MONTH, "2026-09")
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(PeriodTypeCode.YEAR, api.requests.last().period)
        assertNull(vm.state.value.snackbar)
        assertTrue(vm.state.value.periodChangeFailed)
        assertTrue(vm.state.value.periodChangeErrorPresentation?.connectionFailure == true)
        assertFalse(vm.state.value.offline)
        assertEquals("2026-09", vm.state.value.data?.params?.date?.value)

        // The parameters went back to what the screen shows: a reload asks for the month again.
        vm.load()
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH), api.requests.last())
    }

    @Test fun `explicit period retry preserves the failed target and resume never retries it`() {
        api.answers += periodData(PeriodTypeCode.MONTH, "2026-09")
        api.answers += AppError.Offline()
        api.answers += periodData(PeriodTypeCode.YEAR, "2026")
        api.answers += periodData(PeriodTypeCode.MONTH, "2026-09")
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        val failed = api.requests.last()
        assertTrue(vm.state.value.periodChangeFailed)
        assertTrue(vm.state.value.periodChangeRetryReady)
        vm.onResume(null)
        assertEquals(2, api.requests.size)
        vm.retryPeriodChange()
        assertEquals(failed, api.requests.last())
        assertEquals(PeriodTypeCode.YEAR, vm.state.value.data?.params?.period)
        assertFalse(vm.state.value.periodChangeFailed)
        vm.retryPeriodChange()
        assertEquals(3, api.requests.size, "a completed retry cannot be submitted twice")
        vm.setPeriodType(PeriodTypeCode.MONTH)
        assertEquals(PeriodKey("2026-09"), api.requests.last().date)
    }

    @Test fun `an original request refusal blocks period retry but not an explicit refresh of the shown month`() {
        api.answers += data()
        api.answers += refused()
        api.answers += data()
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertTrue(vm.state.value.periodChangeFailed)
        assertFalse(vm.state.value.periodChangeRetryReady)
        vm.retryPeriodChange()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(2, api.requests.size, "the segment cannot bypass the same original refusal")
        vm.load()
        assertEquals(PeriodTypeCode.MONTH, api.requests.last().period)
        assertFalse(vm.state.value.periodChangeFailed)
    }

    @Test fun `a period retry obeys the captured server delay without automatic submission`() {
        api.answers += data()
        api.answers += AppError.RateLimited(null, retryAfterSeconds = 2)
        api.answers += periodData(PeriodTypeCode.YEAR, "2026")
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        val target = api.requests.last()
        assertFalse(vm.state.value.periodChangeRetryReady)
        vm.retryPeriodChange()
        scheduler.advanceTimeBy(1999)
        vm.onResume(null)
        vm.retryPeriodChange()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(2, api.requests.size)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertTrue(vm.state.value.periodChangeRetryReady)
        assertEquals(2, api.requests.size, "expiry alone never submits a retry")
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(target, api.requests.last())
    }

    @Test fun `a different selection retires a failed target and its retry action`() {
        api.answers += data()
        api.answers += AppError.Offline()
        api.answers += periodData(PeriodTypeCode.QUARTER, "2026-Q3")
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        vm.setPeriodType(PeriodTypeCode.QUARTER)
        assertFalse(vm.state.value.periodChangeFailed)
        vm.retryPeriodChange()
        assertEquals(3, api.requests.size)
        assertEquals(PeriodTypeCode.QUARTER, vm.state.value.data?.params?.period)
    }

    @Test fun `a dismissal failure cannot relabel or consume the pending period error`() {
        api.answers += periodData(PeriodTypeCode.MONTH, "2026-09")
        api.answers += AppError.Server(500)
        api.answers += periodData(PeriodTypeCode.YEAR, "2026")
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        val periodError = assertNotNull(vm.state.value.periodChangeErrorPresentation)
        val target = api.requests.last()
        api.dismissFailure = AppError.Offline()
        vm.dismissRegular("rp_1")
        assertEquals(periodError, vm.state.value.periodChangeErrorPresentation)
        assertTrue(vm.state.value.errorPresentation?.connectionFailure == true)
        assertEquals("Не получилось убрать", vm.state.value.snackbar)
        assertTrue(vm.state.value.periodChangeFailed)
        vm.consumeSnackbar()
        assertNull(vm.state.value.errorPresentation)
        assertEquals(periodError, vm.state.value.periodChangeErrorPresentation)
        vm.retryPeriodChange()
        assertEquals(target, api.requests.last())
        assertNull(vm.state.value.periodChangeErrorPresentation)
        assertFalse(vm.state.value.periodChangeFailed)
    }

    @Test fun `any other failure of a visible reload asks to try again`() {
        api.answers += data()
        api.answers += AppError.Server(500)
        val vm = opened()
        vm.load()
        assertEquals("Не получилось обновить. Попробуйте ещё раз", vm.state.value.snackbar)
        vm.consumeSnackbar()
        assertNull(vm.state.value.snackbar)
    }

    @Test fun `a failed refresh after deletion never presents deleted data as current`() {
        api.answers += data()
        api.answers += AppError.Offline()
        val vm = opened()
        statements.events.tryEmit(StatementsEvent.UploadDeleted("u1"))
        assertEquals(2, api.requests.size)
        assertNull(vm.state.value.snackbar)
        assertTrue(vm.state.value.offline)
        assertNull(vm.state.value.data)
    }

    @Test fun `an import or a category change reloads the shown period`() {
        api.answers += data(date = "2026-08")
        opened(AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey("2026-08"), TransferMode.WITH))
        statements.events.tryEmit(StatementsEvent.UploadDeleted("u1"))
        operations.categoryChanges.tryEmit(Unit)
        assertEquals(3, api.requests.size)
        assertTrue(api.requests.all { it.date == PeriodKey("2026-08") })
    }

    @Test fun `resuming a saved tab refreshes its stale empty state without restarting the app`() {
        api.answers += data(hasData = false)
        api.answers += data()
        val vm = vm()
        vm.onResume(null)
        assertEquals(false, vm.state.value.data?.hasData)
        vm.onResume(null)
        assertEquals(true, vm.state.value.data?.hasData)
        assertEquals(2, api.requests.size)
    }

    @Test fun `resuming refreshes deletion of the last statement while preserving the selected period`() {
        api.answers += data()
        api.answers += data(hasData = false)
        val vm = vm()
        vm.onResume(null)
        vm.onResume(null)
        assertEquals(false, vm.state.value.data?.hasData)
        assertEquals(PeriodKey("2026-09"), api.requests.last().date)
    }

    @Test fun `a data mutation immediately removes old numbers until the remaining statements are loaded`() {
        api.answers += data()
        val vm = opened()
        val completion = CompletableDeferred<Analytics>()
        api.reply = { completion.await() }
        statements.events.tryEmit(StatementsEvent.UploadDeleted("u1"))
        assertTrue(vm.state.value.loading)
        assertNull(vm.state.value.data)
        completion.complete(data(date = "2026-08"))
        assertFalse(vm.state.value.loading)
        assertEquals(PeriodKey("2026-08"), vm.state.value.data?.params?.date)
    }

    @Test fun `resuming never bypasses a server retry refusal`() {
        api.answers += refused()
        val vm = vm()
        vm.onResume(null)
        vm.onResume(null)
        assertEquals(1, api.requests.size)
    }

    @Test fun `a completed import with delayed projection never shows the empty state`() {
        statements.pendingImport = 1L
        api.answers += Analytics(hasData = false)
        api.answers += data()
        val vm = opened()
        assertTrue(vm.state.value.loading)
        assertNull(vm.state.value.data)
        assertTrue(statements.acknowledged.isEmpty())
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()
        assertEquals(true, vm.state.value.data?.hasData)
        assertEquals(listOf(1L), statements.acknowledged)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `an import completed before the analytics view model existed is still awaited`() {
        statements.pendingImport = 2L
        api.answers += Analytics(hasData = false)
        api.answers += data(recalculating = true)
        api.answers += data()
        val vm = opened()
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()
        assertTrue(statements.acknowledged.isEmpty())
        assertTrue(vm.state.value.recalculating)
        assertNull(vm.state.value.data)
        assertTrue(vm.state.value.loading)
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()
        assertEquals(listOf(2L), statements.acknowledged)
    }

    @Test fun `a projection that stays absent stops polling with an explicit retry instead of upload advice`() {
        statements.pendingImport = 3L
        api.answers += Analytics(hasData = false)
        val vm = opened()
        scheduler.advanceTimeBy(30_000)
        scheduler.runCurrent()
        assertEquals(10, api.requests.size)
        assertTrue(vm.state.value.preparationTimedOut)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.data)
        assertEquals(3L, statements.pendingImport)
        api.answers.clear()
        api.answers += data()
        vm.load()
        assertEquals(true, vm.state.value.data?.hasData)
        assertFalse(vm.state.value.preparationTimedOut)
    }

    @Test fun `a refused read while waiting for import publication stops polling and blocks retry`() {
        statements.pendingImport = 4L
        api.answers += Analytics(hasData = false)
        api.answers += refused()
        val vm = opened()
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(30_000)
        scheduler.runCurrent()
        vm.load()
        assertEquals(2, api.requests.size)
        assertFalse(vm.state.value.retryReady)
        assertEquals(4L, statements.pendingImport)
    }

    @Test fun `a failed recalculation poll keeps labelled previous data and offers recovery`() {
        api.answers += data()
        api.answers += data(recalculating = true)
        api.answers += AppError.Offline()
        val vm = opened()
        vm.load(quiet = true)
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.offline)
        assertNotNull(vm.state.value.data)
        assertTrue(vm.state.value.recalculationPaused)
        api.answers.clear()
        api.answers += data()
        vm.load()
        assertEquals(true, vm.state.value.data?.hasData)
        assertFalse(vm.state.value.offline)
    }

    @Test fun `a refused recalculation poll labels previous numbers and stops polling`() {
        api.answers += data()
        api.answers += data(recalculating = true)
        api.answers += refused()
        val vm = opened()
        vm.load(quiet = true)
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()
        assertFalse(vm.state.value.loading)
        assertNotNull(vm.state.value.data)
        assertTrue(vm.state.value.recalculationPaused)
        assertNotNull(vm.state.value.errorPresentation)
        assertFalse(vm.state.value.retryReady)
        vm.load()
        assertEquals(3, api.requests.size)
    }

    @Test fun `a new import generation receives a fresh full polling allowance after timeout`() {
        statements.pendingImport = 1L
        api.answers += Analytics(hasData = false)
        val vm = opened()
        scheduler.advanceTimeBy(30_000)
        scheduler.runCurrent()
        assertTrue(vm.state.value.preparationTimedOut)
        assertEquals(10, api.requests.size)
        vm.onResume(null)
        assertEquals(10, api.requests.size, "resume is not an implicit retry after timeout")
        statements.pendingImport = 2L
        statements.events.tryEmit(StatementsEvent.UploadDeleted("previous-source"))
        assertFalse(vm.state.value.preparationTimedOut)
        assertTrue(vm.state.value.loading)
        assertEquals(11, api.requests.size)
        scheduler.advanceTimeBy(30_000)
        scheduler.runCurrent()
        assertEquals(20, api.requests.size)
        assertTrue(vm.state.value.preparationTimedOut)
    }

    @Test fun `a regular payment edit keeps the previous screen when its background refresh fails`() {
        api.answers += data()
        api.answers += AppError.Offline()
        val vm = opened()
        val previous = vm.state.value.data
        vm.dismissRegular("rp_1")
        assertEquals(previous, vm.state.value.data)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.offline)
        assertEquals("Убрали из регулярных платежей", vm.state.value.snackbar)
    }

    @Test fun `while the server recalculates the screen asks again every 3 seconds`() {
        api.answers += data(recalculating = true)
        api.answers += data(recalculating = false)
        opened()
        assertEquals(1, api.requests.size)
        scheduler.advanceTimeBy(2_999)
        scheduler.runCurrent()
        assertEquals(1, api.requests.size)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertEquals(2, api.requests.size)
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()
        assertEquals(2, api.requests.size)
    }

    @Test fun `the first data after the first import starts the hints, categories then ask`() {
        statements.firstImportHints = true
        api.answers += data()
        val vm = opened()
        assertEquals(AnalyticsHint.CATEGORIES, vm.state.value.hint)
        vm.nextHint(stop = false)
        assertEquals(AnalyticsHint.ASK, vm.state.value.hint)
        vm.nextHint(stop = false)
        assertNull(vm.state.value.hint)
    }

    @Test fun `«Не показывать» stops the hints`() {
        statements.firstImportHints = true
        api.answers += data()
        val vm = opened()
        vm.nextHint(stop = true)
        assertNull(vm.state.value.hint)
    }

    @Test fun `without the assistant the ask hint is skipped and no limit is requested`() {
        flags.set(Flag.ASSISTANT, false)
        statements.firstImportHints = true
        api.answers += data()
        val vm = opened()
        assertEquals(AnalyticsHint.CATEGORIES, vm.state.value.hint)
        vm.nextHint(stop = false)
        assertNull(vm.state.value.hint)
        assertEquals(0, assistant.limitCalls)
        assertNull(vm.state.value.limit)
    }

    @Test fun `no hints without data, and the one-time hints are not consumed by it`() {
        statements.firstImportHints = true
        api.answers += data(hasData = false)
        val vm = opened()
        assertNull(vm.state.value.hint)
        assertTrue(statements.firstImportHints)
    }

    @Test fun `with the assistant the question limit is shown, and its failure is ignored`() {
        api.answers += data()
        val vm = opened()
        assertEquals(AssistantLimit(3, 5, at(30)), vm.state.value.limit)

        assistant.failure = AppError.Offline()
        vm.load()
        assertEquals(AssistantLimit(3, 5, at(30)), vm.state.value.limit)
        assertNull(vm.state.value.snackbar)
    }

    @Test fun `the same period type does not request again`() {
        api.answers += data()
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.MONTH)
        assertEquals(1, api.requests.size)
    }

    @Test fun `a new period type keeps the date and is reported`() {
        api.answers += data(date = "2026-09")
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.QUARTER)
        assertEquals(Request(PeriodTypeCode.QUARTER, PeriodKey("2026-09"), TransferMode.WITH), api.requests.last())
        assertEquals(Events.ANALYTICS_PERIOD_CHANGED to mapOf("period" to "quarter"), tracker.events.last())
    }

    private fun periodData(type: PeriodTypeCode, key: String, lastCovered: String? = null): Analytics {
        val range = PeriodKey(key).toRange(reportZone.current)
        return data(type, key).copy(period = ru.finassist.pf.core.api.model.AnalyticsPeriodInfo(
            range = range, isCurrent = false,
            coverage = if (lastCovered == null) Coverage.COMPLETE else Coverage.PARTIAL,
            dataTo = lastCovered?.let(OffsetDateTime::parse), gaps = emptyList(),
        ))
    }

    private fun normalizeRequests() {
        api.reply = {
            val request = api.requests.last()
            val type = request.period!!
            val key = request.date ?: PeriodKey("2026-09")
            val calendarType = ru.finassist.pf.core.common.time.PeriodType.fromCode(type.code)!!
            periodData(type, PeriodKey.of(calendarType, key.start()).value)
        }
    }

    @Test fun `month quarter and year retain independent choices even outside the other tabs`() {
        normalizeRequests()
        val vm = opened(AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey("2025-09"), TransferMode.WITH))
        vm.setPeriodType(PeriodTypeCode.QUARTER)
        assertEquals(PeriodKey("2025-Q3"), vm.state.value.data?.params?.date)
        vm.goTo(PeriodKey("2026-Q2"))
        vm.setPeriodType(PeriodTypeCode.YEAR)
        vm.goTo(PeriodKey("2024"))
        vm.setPeriodType(PeriodTypeCode.MONTH)
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2025-09"), TransferMode.WITH), api.requests.last())
        vm.setPeriodType(PeriodTypeCode.QUARTER)
        assertEquals(PeriodKey("2026-Q2"), api.requests.last().date)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(PeriodKey("2024"), api.requests.last().date)
        assertNull(vm.state.value.errorPresentation)
    }

    @Test fun `a first month visit uses the last covered month of the opened year`() {
        api.answers += periodData(PeriodTypeCode.YEAR, "2025", "2025-08-31T23:59:59+03:00")
        api.answers += periodData(PeriodTypeCode.MONTH, "2025-08")
        val vm = opened(AnalyticsParams(PeriodTypeCode.YEAR, PeriodKey("2025"), TransferMode.WITH))
        vm.setPeriodType(PeriodTypeCode.MONTH)
        assertEquals(PeriodKey("2025-08"), api.requests.last().date)
        assertNull(vm.state.value.errorPresentation)
    }

    @Test fun `a first quarter visit uses coverage of the opened year and then remembers it`() {
        api.answers += periodData(PeriodTypeCode.YEAR, "2025", "2025-07-01T00:00:00+03:00")
        api.answers += periodData(PeriodTypeCode.QUARTER, "2025-Q3")
        val vm = opened(AnalyticsParams(PeriodTypeCode.YEAR, PeriodKey("2025"), TransferMode.WITH))
        vm.setPeriodType(PeriodTypeCode.QUARTER)
        assertEquals(PeriodKey("2025-Q3"), api.requests.last().date)
    }

    @Test fun `a first month visit to a complete quarter respects its exclusive end`() {
        api.answers += periodData(PeriodTypeCode.QUARTER, "2025-Q4")
        api.answers += periodData(PeriodTypeCode.MONTH, "2025-12")
        val vm = opened(AnalyticsParams(PeriodTypeCode.QUARTER, PeriodKey("2025-Q4"), TransferMode.WITH))
        vm.setPeriodType(PeriodTypeCode.MONTH)
        assertEquals(PeriodKey("2025-12"), api.requests.last().date)
    }

    @Test fun `the initial finer key uses the profile zone and keeps the inclusive covered day`() {
        api.answers += periodData(PeriodTypeCode.YEAR, "2025", "2025-08-31T23:30:00Z")
        api.answers += periodData(PeriodTypeCode.MONTH, "2025-09")
        val vm = opened(AnalyticsParams(PeriodTypeCode.YEAR, PeriodKey("2025"), TransferMode.WITH))
        vm.setPeriodType(PeriodTypeCode.MONTH)
        assertEquals(PeriodKey("2025-09"), api.requests.last().date)
    }

    @Test fun `a failed selection does not replace another tabs remembered period`() {
        normalizeRequests()
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.QUARTER)
        api.reply = null
        api.answers += AppError.Offline()
        vm.goTo(PeriodKey("2026-Q2"))
        assertEquals(PeriodKey("2026-Q3"), vm.state.value.data?.params?.date)
        normalizeRequests()
        vm.setPeriodType(PeriodTypeCode.MONTH)
        vm.setPeriodType(PeriodTypeCode.QUARTER)
        assertEquals(PeriodKey("2026-Q3"), api.requests.last().date)
    }

    @Test fun `rapid first visits derive from the displayed year and cancel the obsolete request`() {
        api.answers += periodData(PeriodTypeCode.YEAR, "2025", "2025-08-31T23:59:59+03:00")
        val vm = opened(AnalyticsParams(PeriodTypeCode.YEAR, PeriodKey("2025"), TransferMode.WITH))
        val gate = CompletableDeferred<Unit>()
        api.reply = {
            if (api.requests.last().period == PeriodTypeCode.QUARTER) {
                gate.await()
                periodData(PeriodTypeCode.QUARTER, "2025-Q3")
            } else periodData(PeriodTypeCode.MONTH, "2025-08")
        }
        vm.setPeriodType(PeriodTypeCode.QUARTER)
        vm.setPeriodType(PeriodTypeCode.MONTH)
        assertEquals(PeriodKey("2025-08"), api.requests.last().date)
        gate.complete(Unit)
        scheduler.runCurrent()
        assertEquals(PeriodKey("2025-08"), vm.state.value.data?.params?.date)
        assertNull(vm.state.value.errorPresentation)
    }

    @Test fun `missing coverage never sends a coarser key to a finer request`() {
        api.answers += data(PeriodTypeCode.YEAR, "2025")
        api.answers += periodData(PeriodTypeCode.MONTH, "2026-09")
        val vm = opened(AnalyticsParams(PeriodTypeCode.YEAR, PeriodKey("2025"), TransferMode.WITH))
        vm.setPeriodType(PeriodTypeCode.MONTH)
        assertNull(api.requests.last().date)
    }

    @Test fun `a period from the list closes the sheet and is requested`() {
        api.answers += data()
        val vm = opened()
        vm.openSheet(AnalyticsSheet.PERIODS)
        vm.goTo(PeriodKey("2026-07"))
        assertNull(vm.state.value.sheet)
        assertEquals(PeriodKey("2026-07"), api.requests.last().date)
        assertEquals(Events.ANALYTICS_PERIOD_CHANGED to mapOf("period" to "month"), tracker.events.last())
    }

    @Test fun `the transfer mode closes the sheet and requests only when it changes`() {
        api.answers += data()
        api.answers += data(mode = TransferMode.WITHOUT)
        val vm = opened()
        vm.openSheet(AnalyticsSheet.TRANSFERS)
        vm.setTransferMode(TransferMode.WITH)
        assertNull(vm.state.value.sheet)
        assertEquals(1, api.requests.size)

        vm.setTransferMode(TransferMode.WITHOUT)
        assertEquals(TransferMode.WITHOUT, api.requests.last().mode)
        assertEquals(TransferMode.WITHOUT, vm.currentTransferMode())
        assertEquals(Events.ANALYTICS_TRANSFERS_CHANGED to mapOf("mode" to "without"), tracker.events.last())
    }

    @Test fun `the period list is requested when its sheet opens, for the current period type`() {
        api.answers += data(PeriodTypeCode.QUARTER, "2026-Q3")
        val vm = opened(AnalyticsParams(PeriodTypeCode.QUARTER, PeriodKey("2026-Q3"), TransferMode.WITH))
        assertEquals(emptyList(), api.periodRequests)
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertEquals(listOf<PeriodTypeCode?>(PeriodTypeCode.QUARTER), api.periodRequests)
        assertEquals(listOf(PeriodKey("2026-09")), vm.state.value.periods?.map { it.key })

        vm.openSheet(AnalyticsSheet.TRANSFERS)
        assertEquals(1, api.periodRequests.size)
        assertEquals(AnalyticsSheet.TRANSFERS, vm.state.value.sheet)
    }

    @Test fun `a failed period list shows its error and reopening asks again from scratch`() {
        api.answers += data()
        val vm = opened()
        api.periodsFailure = AppError.Offline()
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertTrue(vm.state.value.periodsFailed)
        assertNull(vm.state.value.periods)

        api.periodsFailure = null
        vm.openSheet(null)
        assertTrue(vm.state.value.periodsFailed, "closing keeps the previous outcome until a real request starts")
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertEquals(2, api.periodRequests.size)
        assertTrue(vm.state.value.periods != null)
    }

    @Test fun `reopening periods waits for the original server duration before another fetch`() {
        api.answers += data()
        val vm = opened()
        api.periodsFailure = AppError.RateLimited(null, 3, ru.finassist.pf.core.common.error.ErrorResponseInfo(
            "RATE_LIMITED", 429, requestRetryable = true,
            timing = ru.finassist.pf.core.common.time.ResponseTiming(receivedElapsedMillis = scheduler.currentTime),
        ))
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertEquals(1, api.periodRequests.size)
        assertTrue(vm.state.value.periodsFailed)
        assertFalse(vm.state.value.periodsRetryReady)
        val shownData = vm.state.value.data
        api.periodsFailure = null

        vm.openSheet(null)
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertTrue(vm.state.value.periodsFailed)
        scheduler.advanceTimeBy(2_999)
        scheduler.runCurrent()
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertEquals(1, api.periodRequests.size, "reopening does not bypass or restart the wait")
        assertEquals(AnalyticsSheet.PERIODS, vm.state.value.sheet)
        assertEquals(shownData, vm.state.value.data)

        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertTrue(vm.state.value.periodsRetryReady)
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertEquals(2, api.periodRequests.size)
        assertFalse(vm.state.value.periodsFailed)
        assertNull(vm.state.value.periodsErrorPresentation)
        assertEquals(listOf(PeriodKey("2026-09")), vm.state.value.periods?.map { it.key })
        assertEquals(1, api.requests.size, "the sheet does not resend the main analytics request")
    }

    @Test fun `a forbidden period-list request stays forbidden when its sheet is reopened later`() {
        api.answers += data()
        val vm = opened()
        val presentation = ru.finassist.pf.core.common.error.ErrorPresentation(
            ru.finassist.pf.core.common.error.UserMessage.from("Периоды временно недоступны"),
            ru.finassist.pf.core.common.error.ErrorSeverity.WARNING,
        )
        api.periodsFailure = AppError.Api("FUTURE_PERIOD_POLICY", 409,
            response = ru.finassist.pf.core.common.error.ErrorResponseInfo("FUTURE_PERIOD_POLICY", 409, requestRetryable = false, presentation = presentation))
        vm.openSheet(AnalyticsSheet.PERIODS)
        api.periodsFailure = null
        scheduler.advanceTimeBy(60_000)
        scheduler.runCurrent()
        vm.openSheet(AnalyticsSheet.PERIODS)
        vm.openSheet(null)
        vm.openSheet(AnalyticsSheet.TRANSFERS)
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertEquals(1, api.periodRequests.size)
        assertTrue(vm.state.value.periodsFailed, "blocked reopening shows the refusal, not a permanent loader")
        assertEquals(presentation, vm.state.value.periodsErrorPresentation)
        assertFalse(vm.state.value.periodsRetryReady)
        assertEquals(AnalyticsSheet.PERIODS, vm.state.value.sheet)
        assertEquals(1, api.requests.size)
    }

    private fun periodList(key: String) = AnalyticsPeriodsList(listOf(
        AnalyticsPeriodItem(PeriodKey(key), DateRange(at(1), at(30)), Coverage.COMPLETE),
    ))

    @Test fun `a device fallback zone change refreshes a pending directory without a profile emission`() {
        var device = java.time.ZoneId.of("Europe/Moscow")
        val zone = ReportZone { device }
        api.answers += data()
        val old = CompletableDeferred<AnalyticsPeriodsList>()
        var replies = 0
        api.periodsReply = { if (++replies == 1) old.await() else periodList("2026-08") }
        val vm = vm(zone).apply { init(null) }
        vm.openSheet(AnalyticsSheet.PERIODS)
        device = java.time.ZoneId.of("Asia/Vladivostok")
        old.complete(periodList("2026-07"))
        assertNull(zone.profile.value)
        assertEquals(2, api.periodRequests.size)
        assertEquals(listOf(PeriodKey("2026-08")), vm.state.value.periods?.map { it.key })
        assertFalse(vm.state.value.periodsFailed)
    }

    @Test fun `a device-zone old refusal is discarded while a new-context refusal remains effective`() {
        var device = java.time.ZoneId.of("Europe/Moscow")
        val zone = ReportZone { device }
        api.answers += data()
        val old = CompletableDeferred<AnalyticsPeriodsList>()
        var replies = 0
        api.periodsReply = { if (++replies == 1) old.await() else throw refused() }
        val vm = vm(zone).apply { init(null) }
        vm.openSheet(AnalyticsSheet.PERIODS)
        device = java.time.ZoneId.of("Asia/Vladivostok")
        old.completeExceptionally(refused())
        assertEquals(2, api.periodRequests.size)
        assertTrue(vm.state.value.periodsFailed)
        assertFalse(vm.state.value.periodsRetryReady)
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertEquals(2, api.periodRequests.size)
    }

    @Test fun `a previous-zone period refusal cannot poison the visible new-zone directory`() {
        reportZone.set("Europe/Moscow")
        api.answers += data()
        var old: kotlin.coroutines.Continuation<AnalyticsPeriodsList>? = null
        var replies = 0
        api.periodsReply = {
            if (++replies == 1) kotlin.coroutines.suspendCoroutine { old = it }
            else periodList("2026-08")
        }
        val vm = opened()
        vm.openSheet(AnalyticsSheet.PERIODS)
        reportZone.set("Asia/Vladivostok")
        assertEquals(2, api.periodRequests.size, "a visible directory refreshes for the new zone")
        old!!.resumeWith(Result.failure(refused()))
        assertEquals(listOf(PeriodKey("2026-08")), vm.state.value.periods?.map { it.key })
        assertFalse(vm.state.value.periodsFailed)
        assertNull(vm.state.value.periodsRetryable)
        assertTrue(vm.state.value.periodsRetryReady)
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertEquals(3, api.periodRequests.size)
    }

    @Test fun `a pre-mutation period success cannot replace or release the current directory refusal`() {
        api.answers += data()
        var old: kotlin.coroutines.Continuation<AnalyticsPeriodsList>? = null
        var replies = 0
        api.periodsReply = {
            if (++replies == 1) kotlin.coroutines.suspendCoroutine { old = it }
            else throw refused()
        }
        val vm = opened()
        vm.openSheet(AnalyticsSheet.PERIODS)
        statements.events.tryEmit(StatementsEvent.UploadDeleted("u1"))
        old!!.resumeWith(Result.success(periodList("2026-07")))
        assertNull(vm.state.value.periods)
        assertTrue(vm.state.value.periodsFailed)
        assertEquals(false, vm.state.value.periodsRetryable)
        assertFalse(vm.state.value.periodsRetryReady)
        vm.openSheet(null)
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertEquals(2, api.periodRequests.size, "the current unchanged refusal remains in force")
    }

    @Test fun `a previous period-type refusal cannot overwrite the new type directory`() {
        api.answers += data()
        api.answers += data(period = PeriodTypeCode.QUARTER, date = "2026-Q3")
        var old: kotlin.coroutines.Continuation<AnalyticsPeriodsList>? = null
        api.periodsReply = { type ->
            if (type == PeriodTypeCode.MONTH) kotlin.coroutines.suspendCoroutine { old = it }
            else periodList("2026-Q3")
        }
        val vm = opened()
        vm.openSheet(AnalyticsSheet.PERIODS)
        vm.setPeriodType(PeriodTypeCode.QUARTER)
        old!!.resumeWith(Result.failure(refused()))
        assertEquals(listOf<PeriodTypeCode?>(PeriodTypeCode.MONTH, PeriodTypeCode.QUARTER), api.periodRequests)
        assertEquals(listOf(PeriodKey("2026-Q3")), vm.state.value.periods?.map { it.key })
        assertFalse(vm.state.value.periodsFailed)
        assertTrue(vm.state.value.periodsRetryReady)
    }

    @Test fun `restoring the shown period type refreshes an open directory for that restored context`() {
        api.answers += data()
        api.answers += refused()
        api.periodsReply = { type -> periodList(if (type == PeriodTypeCode.QUARTER) "2026-Q3" else "2026-09") }
        val vm = opened()
        vm.openSheet(AnalyticsSheet.PERIODS)
        vm.setPeriodType(PeriodTypeCode.QUARTER)
        assertEquals(PeriodTypeCode.MONTH, vm.state.value.data?.params?.period)
        assertEquals(listOf<PeriodTypeCode?>(PeriodTypeCode.MONTH, PeriodTypeCode.QUARTER, PeriodTypeCode.MONTH), api.periodRequests)
        assertEquals(listOf(PeriodKey("2026-09")), vm.state.value.periods?.map { it.key })
    }

    @Test fun `the first known profile zone equal to the device zone does not release a directory refusal`() {
        api.answers += data()
        val vm = opened()
        api.periodsFailure = refused()
        vm.openSheet(AnalyticsSheet.PERIODS)
        api.periodsFailure = null
        reportZone.set("Europe/Moscow")
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertEquals(1, api.periodRequests.size)
        assertFalse(vm.state.value.periodsRetryReady)
        reportZone.set("Asia/Vladivostok")
        assertEquals(2, api.periodRequests.size, "an actual effective-zone change releases the old context")
        assertTrue(vm.state.value.periodsRetryReady)
    }

    @Test fun `same-zone publication does not clear or restart the current period wait`() {
        api.answers += data()
        val vm = opened()
        api.periodsFailure = AppError.RateLimited(null, 3, ru.finassist.pf.core.common.error.ErrorResponseInfo(
            "RATE_LIMITED", 429, requestRetryable = true,
            timing = ru.finassist.pf.core.common.time.ResponseTiming(receivedElapsedMillis = scheduler.currentTime),
        ))
        vm.openSheet(AnalyticsSheet.PERIODS)
        api.periodsFailure = null
        scheduler.advanceTimeBy(1_000)
        reportZone.set("Europe/Moscow")
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertEquals(1, api.periodRequests.size)
        scheduler.advanceTimeBy(2_000)
        scheduler.runCurrent()
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertEquals(2, api.periodRequests.size, "the original duration remains anchored at the first refusal")
    }

    @Test fun `closing the sheet drops the pending period list`() {
        api.answers += data()
        val vm = opened()
        val gate = CompletableDeferred<Unit>()
        api.periodsGate = gate
        vm.openSheet(AnalyticsSheet.PERIODS)
        vm.openSheet(null)
        gate.complete(Unit)
        assertNull(vm.state.value.periods)
    }

    @Test fun `a list is shown in full and folded back by its id`() {
        val vm = vm()
        vm.toggleExpanded("expense_categories")
        assertEquals(setOf("expense_categories"), vm.state.value.expanded)
        vm.toggleExpanded("expense_categories")
        assertEquals(emptySet(), vm.state.value.expanded)
    }

    @Test fun `a dismissed regular payment is reported and the analytics recalculated`() {
        api.answers += data()
        val vm = opened()
        vm.dismissRegular("rp_1")
        assertEquals(listOf("rp_1"), api.dismissed)
        assertEquals("Убрали из регулярных платежей", vm.state.value.snackbar)
        assertEquals(Events.ANALYTICS_REGULAR_DISMISSED.name, names().last())
        assertEquals(2, api.requests.size)
    }

    @Test fun `period directory failures distinguish local connection from server failure`() {
        api.answers += data()
        val vm = opened()
        for (failure in listOf(AppError.Offline(), AppError.Server(503))) {
            api.periodsFailure = failure
            vm.openSheet(null)
            vm.openSheet(AnalyticsSheet.PERIODS)
            assertTrue(vm.state.value.periodsFailed)
            assertEquals(failure is AppError.Offline, vm.state.value.periodsErrorPresentation?.connectionFailure)
        }
    }

    @Test fun `a regular payment that could not be dismissed says why`() {
        api.answers += data()
        val vm = opened()
        api.dismissFailure = AppError.Offline()
        vm.dismissRegular("rp_1")
        assertEquals("Не получилось убрать", vm.state.value.snackbar)
        assertTrue(vm.state.value.errorPresentation?.connectionFailure == true)
        api.dismissFailure = AppError.Server(500)
        vm.dismissRegular("rp_1")
        assertEquals("Не получилось убрать. Попробуйте ещё раз", vm.state.value.snackbar)
        assertEquals(false, vm.state.value.errorPresentation?.connectionFailure)
        assertEquals(1, api.requests.size)
        assertTrue(tracker.events.none { it.first == Events.ANALYTICS_REGULAR_DISMISSED })
    }

    @Test fun `a tile opening the operations is reported and navigates`() {
        val navigated = mutableListOf<BlockAction>()
        val action = BlockAction(ActionType.OPEN_OPERATIONS, filter = OperationsFilter(categoryId = "c"))
        vm().onAction(Block(id = "tiles", type = BlockType.TILE_GROUP), action) { navigated += it }
        assertEquals(Events.ANALYTICS_TILE_OPENED to mapOf("block" to "tiles"), tracker.events.single())
        assertEquals(listOf(action), navigated)
    }

    @Test fun `«Не подписка» of a block dismisses the payment, an unknown action does nothing`() {
        api.answers += data()
        val vm = opened()
        val navigated = mutableListOf<BlockAction>()
        val card = Block(id = "regular_payments", type = BlockType.INSIGHT_CARD)
        vm.onAction(card, BlockAction(ActionType.DISMISS_REGULAR_PAYMENT, paymentId = "rp_1", label = "Не подписка")) { navigated += it }
        vm.onAction(card, BlockAction(ActionType.UNKNOWN)) { navigated += it }
        assertEquals(listOf("rp_1"), api.dismissed)
        assertTrue(navigated.isEmpty())
    }

    @Test fun `every request says which blocks this client draws and the experiment variants (FIN-4, FIN-9)`() {
        api.answers += data()
        opened()
        val (supports, exp) = api.extras.single()
        assertEquals(CatalogV1.registry().supports, supports)
        assertTrue(supports!!.contains("tile_group@2") && supports.contains("insight_card@1"))
        assertEquals("analytics_order:a", exp)

        val b = AnalyticsViewModel(api, assistant, statements, operations, FakeFlags(variants = mutableMapOf(Experiment.ANALYTICS_ORDER to "b")), tracker, reportZone, ru.finassist.pf.core.common.time.ElapsedClock { scheduler.currentTime })
        b.init(null)
        assertEquals("analytics_order:b", api.extras.last().second)
    }

    @Test fun `a drawn block is exposed once per response with the experiment variants (FIN-9)`() {
        api.answers += data()
        val vm = opened()
        val tiles = Block(id = "tiles", type = BlockType.TILE_GROUP)
        vm.onBlockShown(tiles)
        vm.onBlockShown(tiles)
        vm.onBlockShown(Block(type = BlockType.HEADING))
        val exposures = tracker.events.filter { it.first == Events.ANALYTICS_BLOCK_EXPOSED }
        assertEquals(listOf(mapOf("block" to "tiles", "exp" to "analytics_order:a")), exposures.map { it.second })

        vm.load()
        vm.onBlockShown(tiles)
        assertEquals(2, tracker.events.count { it.first == Events.ANALYTICS_BLOCK_EXPOSED }, "a new response is a new exposure")
    }

    private companion object {
        fun at(day: Int): OffsetDateTime = OffsetDateTime.parse("2026-09-%02dT00:00:00+03:00".format(day))
    }

    @Test fun `exposures report the variants the shown screen was asked with, a new variant asks again (FIN-9 review)`() {
        api.answers += data()
        api.answers += data()
        val flags = FakeFlags()
        val vm = AnalyticsViewModel(api, assistant, statements, operations, flags, tracker, reportZone, ru.finassist.pf.core.common.time.ElapsedClock { scheduler.currentTime })
        vm.init(null)
        assertEquals(1, vm.state.value.response)
        val tiles = Block(id = "tiles", type = BlockType.TILE_GROUP)

        // The variant changes before the drawn blocks are reported: they still belong to the screen of variant a.
        val onScreen = vm.state.value.data
        flags.variants[Experiment.ANALYTICS_ORDER] = "b"
        vm.onBlockShown(tiles)
        assertEquals("analytics_order:a", tracker.events.last { it.first == Events.ANALYTICS_BLOCK_EXPOSED }.second["exp"])
        assertTrue(onScreen === vm.state.value.data, "no reload without a config change event")

        // The config change event: the screen is composed anew for b, and its blocks are a new exposure.
        flags.changes.tryEmit(Unit)
        assertEquals("analytics_order:b", api.extras.last().second)
        assertEquals(2, vm.state.value.response)
        vm.onBlockShown(tiles)
        assertEquals("analytics_order:b", tracker.events.last { it.first == Events.ANALYTICS_BLOCK_EXPOSED }.second["exp"])
    }

    @Test fun `a config change with the same variants does not ask again`() {
        api.answers += data()
        val flags = FakeFlags()
        val vm = AnalyticsViewModel(api, assistant, statements, operations, flags, tracker, reportZone, ru.finassist.pf.core.common.time.ElapsedClock { scheduler.currentTime })
        vm.init(null)
        flags.set(Flag.ANALYTICS_BLOCK_BANK_FEES, false)
        assertEquals(1, api.extras.size)
        assertEquals(1, vm.state.value.response)
    }
    private fun refused() = AppError.Api("FUTURE_ANALYTICS_POLICY", 429,
        response = ru.finassist.pf.core.common.error.ErrorResponseInfo("FUTURE_ANALYTICS_POLICY", 429, requestRetryable = false))

    private fun blockedAnalytics(): AnalyticsViewModel {
        api.answers += data(date = "2026-08")
        api.answers += refused()
        api.answers += data(date = "2026-08", hasData = false)
        return opened().also {
            it.load()
            assertFalse(it.state.value.retryReady)
            it.load()
            assertEquals(2, api.requests.size)
        }
    }

    private fun assertContextRefreshed(vm: AnalyticsViewModel) {
        assertEquals(3, api.requests.size)
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-08"), TransferMode.WITH), api.requests.last())
        assertTrue(vm.state.value.retryReady)
        assertEquals(false, vm.state.value.data?.hasData)
        assertNull(vm.state.value.errorPresentation)
        assertNull(vm.state.value.requestRetryable)
    }

    @Test fun `a completed import releases only the old analytics request restriction`() {
        val vm = blockedAnalytics()
        statements.events.tryEmit(StatementsEvent.ImportCompleted("u2", ImportResult(
            operationCount = 0, newCount = 0, duplicateCount = 0, unreadCount = 0, isFirstImport = false,
            accounts = emptyList(), categorizedCount = 0, ownTransferCount = 0, uncategorizedCount = 0,
            coverage = ru.finassist.pf.core.api.model.ImportCoverage(0, 0, emptyList(), emptyList(), emptyList()), notices = emptyList(),
        )))
        assertContextRefreshed(vm)
    }

    @Test fun `deleting a statement refreshes analytics despite a previous refusal`() {
        val vm = blockedAnalytics()
        statements.events.tryEmit(StatementsEvent.UploadDeleted("u1"))
        assertContextRefreshed(vm)
    }

    @Test fun `changing an operation category refreshes analytics despite a previous refusal`() {
        val vm = blockedAnalytics()
        operations.categoryChanges.tryEmit(Unit)
        assertContextRefreshed(vm)
    }

    @Test fun `a profile zone change refreshes analytics despite a previous refusal`() {
        reportZone.set("Europe/Moscow")
        val vm = blockedAnalytics()
        reportZone.set("Asia/Vladivostok")
        assertContextRefreshed(vm)
    }

    @Test fun `a zone change can recover the first refused response without existing data`() {
        reportZone.set("Europe/Moscow")
        api.answers += refused()
        api.answers += data()
        val vm = opened()
        assertFalse(vm.state.value.retryReady)
        assertNull(vm.state.value.data)
        reportZone.set("Asia/Vladivostok")
        assertEquals(2, api.requests.size)
        assertTrue(vm.state.value.retryReady)
        assertNotNull(vm.state.value.data)
    }

    @Test fun `selecting the current date type and transfers cannot lift a direct retry refusal`() {
        api.answers += data()
        api.answers += refused()
        api.answers += data()
        val vm = opened()
        vm.load()
        val presentation = vm.state.value.errorPresentation
        vm.setPeriodType(PeriodTypeCode.MONTH)
        vm.openSheet(AnalyticsSheet.PERIODS)
        vm.goTo(PeriodKey("2026-09"))
        vm.setTransferMode(TransferMode.WITH)
        vm.load()
        assertEquals(2, api.requests.size)
        assertFalse(vm.state.value.retryReady)
        assertEquals(false, vm.state.value.requestRetryable)
        assertEquals(presentation, vm.state.value.errorPresentation)
        assertNull(vm.state.value.sheet)
    }

    @Test fun `selecting identical parameters preserves the original remaining server wait`() {
        api.answers += data()
        api.answers += AppError.RateLimited(null, 3, ru.finassist.pf.core.common.error.ErrorResponseInfo(
            "RATE_LIMITED", 429, requestRetryable = true,
            timing = ru.finassist.pf.core.common.time.ResponseTiming(receivedElapsedMillis = scheduler.currentTime)))
        api.answers += data()
        val vm = opened()
        vm.load()
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        vm.setPeriodType(PeriodTypeCode.MONTH)
        vm.goTo(PeriodKey("2026-09"))
        vm.setTransferMode(TransferMode.WITH)
        vm.load()
        assertEquals(2, api.requests.size)
        assertFalse(vm.state.value.retryReady)
        scheduler.advanceTimeBy(1_999)
        scheduler.runCurrent()
        vm.load()
        assertEquals(2, api.requests.size)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertTrue(vm.state.value.retryReady)
        vm.load()
        assertEquals(3, api.requests.size)
    }

    @Test fun `a changed transfer mode is a new request context after a refusal`() {
        api.answers += data()
        api.answers += refused()
        api.answers += data(mode = TransferMode.WITHOUT)
        val vm = opened()
        vm.load()
        vm.setTransferMode(TransferMode.WITHOUT)
        assertEquals(3, api.requests.size)
        assertEquals(TransferMode.WITHOUT, api.requests.last().mode)
        assertTrue(vm.state.value.retryReady)
    }

    @Test fun `period directory restrictions survive date and transfer changes but not a changed period type`() {
        api.answers += data()
        api.answers += data(date = "2026-08")
        api.answers += data(date = "2026-08", mode = TransferMode.WITHOUT)
        api.answers += data(period = PeriodTypeCode.QUARTER, date = "2026-Q3", mode = TransferMode.WITHOUT)
        val vm = opened()
        api.periodsFailure = refused()
        vm.openSheet(AnalyticsSheet.PERIODS)
        api.periodsFailure = null
        vm.goTo(PeriodKey("2026-08"))
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertEquals(1, api.periodRequests.size)
        assertFalse(vm.state.value.periodsRetryReady)
        vm.setTransferMode(TransferMode.WITHOUT)
        vm.openSheet(AnalyticsSheet.PERIODS)
        assertEquals(1, api.periodRequests.size)
        assertFalse(vm.state.value.periodsRetryReady)
        vm.setPeriodType(PeriodTypeCode.QUARTER)
        assertEquals(listOf<PeriodTypeCode?>(PeriodTypeCode.MONTH, PeriodTypeCode.QUARTER), api.periodRequests)
        assertTrue(vm.state.value.periodsRetryReady)
    }

    @Test fun `a successful dismissal changes analytics data and permits its canonical refresh`() {
        val vm = blockedAnalytics()
        vm.dismissRegular("rp_1")
        assertEquals(listOf("rp_1"), api.dismissed)
        assertContextRefreshed(vm)
    }

    @Test fun `a refused year switch keeps its explanation but cannot restrict the restored month request`() {
        api.answers += data()
        api.answers += refused()
        api.answers += data()
        api.answers += data(period = PeriodTypeCode.YEAR, date = "2026")
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(2, api.requests.size, "rollback does not automatically fetch anything")
        assertEquals(PeriodTypeCode.YEAR, api.requests.last().period)
        assertEquals(PeriodTypeCode.MONTH, vm.state.value.data?.params?.period)
        val refusal = assertNotNull(vm.state.value.periodChangeErrorPresentation)
        assertEquals(false, vm.state.value.requestRetryable)
        assertTrue(vm.state.value.retryReady, "the restored month is a different request context")
        vm.setPeriodType(PeriodTypeCode.MONTH)
        assertEquals(2, api.requests.size, "selecting the already restored month is still a no-op")
        assertEquals(refusal, vm.state.value.periodChangeErrorPresentation)
        vm.load()
        assertEquals(3, api.requests.size)
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH), api.requests.last())
        assertNull(vm.state.value.errorPresentation)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(4, api.requests.size, "the newly selected year is an explicit changed intent")
        assertEquals(PeriodTypeCode.YEAR, api.requests.last().period)
        assertEquals(PeriodTypeCode.YEAR, vm.state.value.data?.params?.period)
    }

    @Test fun `pull refresh preserves period transfer mode and expansion while deduplicating requests`() {
        val initial = data(date = "2026-08", mode = TransferMode.WITHOUT)
        api.answers += initial
        val vm = opened(initial.params)
        vm.toggleExpanded("expense_categories")
        val pending = CompletableDeferred<Analytics>()
        api.reply = { pending.await() }
        vm.refresh()
        vm.refresh()
        assertEquals(2, api.requests.size)
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-08"), TransferMode.WITHOUT), api.requests.last())
        assertTrue(vm.state.value.refreshing)
        assertEquals(initial, vm.state.value.data)
        assertTrue("expense_categories" in vm.state.value.expanded)
        pending.completeExceptionally(AppError.Offline())
        assertFalse(vm.state.value.refreshing)
        assertEquals(initial, vm.state.value.data)
        assertTrue(vm.state.value.errorPresentation?.connectionFailure == true)
    }

    @Test fun `pull refresh obeys retry after and retries only after the deadline`() {
        api.answers += data()
        val vm = opened()
        api.answers.clear()
        api.answers += AppError.RateLimited(null, 2)
        vm.refresh()
        assertFalse(vm.state.value.retryReady)
        vm.refresh()
        assertEquals(2, api.requests.size)
        scheduler.advanceTimeBy(2000)
        scheduler.runCurrent()
        api.answers.clear()
        api.answers += data()
        vm.refresh()
        assertEquals(3, api.requests.size)
    }

    @Test fun `manual refresh updates quota even after analytics read is refused`() {
        api.answers += data()
        val vm = opened()
        api.answers.clear()
        api.answers += AppError.Api("FUTURE_REFUSAL", 429, response = ru.finassist.pf.core.common.error.ErrorResponseInfo(
            "FUTURE_REFUSAL", 429, requestRetryable = false))
        assistant.limit = assistant.limit.copy(remaining = 1)
        vm.refresh()
        assertEquals(1, vm.state.value.limit?.remaining)
        assertFalse(vm.state.value.retryReady)
        assertEquals(2, assistant.limitCalls)
        val failure = vm.state.value.errorPresentation
        assistant.limit = assistant.limit.copy(remaining = 0)
        vm.refresh()
        assertEquals(2, api.requests.size)
        assertEquals(3, assistant.limitCalls)
        assertEquals(0, vm.state.value.limit?.remaining)
        assertEquals(failure, vm.state.value.errorPresentation)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun `quota only refresh respects its own retry after while analytics remains refused`() {
        api.answers += data()
        val vm = opened()
        api.answers.clear()
        api.answers += AppError.Api("FUTURE_REFUSAL", 429, response = ru.finassist.pf.core.common.error.ErrorResponseInfo(
            "FUTURE_REFUSAL", 429, requestRetryable = false))
        assistant.failure = AppError.RateLimited(null, 2)
        vm.refresh()
        assertFalse(vm.state.value.limitRetryReady)
        vm.refresh()
        assertEquals(2, assistant.limitCalls)
        scheduler.advanceTimeBy(2000)
        scheduler.runCurrent()
        assistant.failure = null
        vm.refresh()
        assertEquals(3, assistant.limitCalls)
        assertEquals(2, api.requests.size)
        assertTrue(vm.state.value.limitRetryReady)
    }

    @Test fun `manual refresh of the shown month preserves a refused year switch`() {
        api.answers += data()
        api.answers += refused()
        api.answers += data()
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        val explanation = assertNotNull(vm.state.value.periodChangeErrorPresentation)
        assertFalse(vm.state.value.periodChangeRetryReady)
        vm.refresh()
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH), api.requests.last())
        assertEquals(explanation, vm.state.value.periodChangeErrorPresentation)
        assertFalse(vm.state.value.periodChangeRetryReady)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        vm.retryPeriodChange()
        assertEquals(3, api.requests.size, "refreshing the shown month must not release the refused year target")
    }

    @Test fun `shown-period refresh preserves the failed target wait until explicit retry`() {
        api.answers += data()
        api.answers += AppError.RateLimited(null, 3)
        api.answers += data()
        api.answers += data(period = PeriodTypeCode.YEAR, date = "2026")
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        val failedTarget = api.requests.last()
        val explanation = assertNotNull(vm.state.value.periodChangeErrorPresentation)
        vm.refresh()
        assertEquals(PeriodTypeCode.MONTH, api.requests.last().period)
        assertEquals(explanation, vm.state.value.periodChangeErrorPresentation)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        vm.retryPeriodChange()
        assertEquals(3, api.requests.size)
        scheduler.advanceTimeBy(2999)
        scheduler.runCurrent()
        assertFalse(vm.state.value.periodChangeRetryReady)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertTrue(vm.state.value.periodChangeRetryReady)
        assertEquals(3, api.requests.size, "wait expiry never submits the saved target")
        vm.retryPeriodChange()
        assertEquals(failedTarget, api.requests.last())
        assertEquals(4, api.requests.size)
        assertEquals(PeriodTypeCode.YEAR, vm.state.value.data?.params?.period)
    }

    @Test fun `a failed shown-period refresh retains the independent refused target and its retry gate`() {
        api.answers += data()
        api.answers += refused()
        api.answers += AppError.Offline()
        api.answers += data()
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        val explanation = assertNotNull(vm.state.value.periodChangeErrorPresentation)
        vm.refresh()
        assertTrue(vm.state.value.errorPresentation?.connectionFailure == true)
        assertEquals(explanation, vm.state.value.periodChangeErrorPresentation)
        assertFalse(vm.state.value.periodChangeRetryReady)
        assertEquals(PeriodTypeCode.MONTH, vm.state.value.data?.params?.period)
        vm.refresh()
        assertNull(vm.state.value.errorPresentation)
        assertEquals(explanation, vm.state.value.periodChangeErrorPresentation)
        assertFalse(vm.state.value.periodChangeRetryReady)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(4, api.requests.size)
        assertTrue(api.requests.drop(2).all { it.period == PeriodTypeCode.MONTH })
    }

    @Test fun `retrying paused recalculation through refresh retains a refused period target`() {
        api.answers += data()
        api.answers += data(recalculating = true)
        api.answers += refused()
        api.answers += data()
        val vm = opened()
        vm.load(quiet = true)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertTrue(vm.state.value.recalculationPaused)
        val explanation = assertNotNull(vm.state.value.periodChangeErrorPresentation)
        vm.refresh()
        assertEquals(PeriodTypeCode.MONTH, api.requests.last().period)
        assertEquals(explanation, vm.state.value.periodChangeErrorPresentation)
        assertFalse(vm.state.value.periodChangeRetryReady)
        assertFalse(vm.state.value.recalculationPaused)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(4, api.requests.size)
    }

    @Test fun `recalculation polling after manual refresh preserves a failed period target wait`() {
        api.answers += data()
        api.answers += AppError.RateLimited(null, 60)
        api.answers += data(recalculating = true)
        api.answers += data()
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        val explanation = assertNotNull(vm.state.value.periodChangeErrorPresentation)
        vm.refresh()
        assertTrue(vm.state.value.recalculating)
        scheduler.advanceTimeBy(3000)
        scheduler.runCurrent()
        assertFalse(vm.state.value.recalculating)
        assertEquals(explanation, vm.state.value.periodChangeErrorPresentation)
        assertFalse(vm.state.value.periodChangeRetryReady)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(4, api.requests.size)
        assertTrue(api.requests.drop(2).all { it.period == PeriodTypeCode.MONTH })
    }

    @Test fun `import publication polling after manual refresh preserves a refused period target`() {
        api.answers += data()
        api.answers += data(recalculating = true)
        api.answers += refused()
        api.answers += Analytics(hasData = false)
        api.answers += data()
        val vm = opened()
        statements.pendingImport = 42L
        vm.load(quiet = true)
        assertTrue(statements.acknowledged.isEmpty())
        vm.setPeriodType(PeriodTypeCode.YEAR)
        val explanation = assertNotNull(vm.state.value.periodChangeErrorPresentation)
        vm.refresh()
        assertNull(vm.state.value.data)
        assertTrue(vm.state.value.loading)
        scheduler.advanceTimeBy(3000)
        scheduler.runCurrent()
        assertEquals(PeriodTypeCode.MONTH, vm.state.value.data?.params?.period)
        assertEquals(listOf(42L), statements.acknowledged)
        assertEquals(explanation, vm.state.value.periodChangeErrorPresentation)
        assertFalse(vm.state.value.periodChangeRetryReady)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(5, api.requests.size)
        assertTrue(api.requests.drop(3).all { it.period == PeriodTypeCode.MONTH })
    }

    @Test fun `no-data offline recovery preserves a refused period target`() {
        api.answers += data()
        api.answers += data(recalculating = true)
        api.answers += refused()
        api.answers += Analytics(hasData = false)
        api.answers += AppError.Offline()
        api.answers += data()
        val vm = opened()
        statements.pendingImport = 42L
        vm.load(quiet = true)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        val explanation = assertNotNull(vm.state.value.periodChangeErrorPresentation)
        vm.refresh()
        scheduler.advanceTimeBy(3000)
        scheduler.runCurrent()
        assertNull(vm.state.value.data)
        assertTrue(vm.state.value.offline)
        assertFalse(vm.state.value.loading)
        assertEquals(explanation, vm.state.value.periodChangeErrorPresentation)
        assertFalse(vm.state.value.periodChangeRetryReady)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(5, api.requests.size)
        val recovery = CompletableDeferred<Analytics>()
        api.reply = { recovery.await() }
        vm.refresh()
        assertTrue(vm.state.value.refreshing)
        vm.refresh()
        assertEquals(6, api.requests.size, "the fullscreen retry is deduplicated while pending")
        recovery.complete(data())
        assertFalse(vm.state.value.refreshing)
        assertFalse(vm.state.value.offline)
        assertEquals(PeriodTypeCode.MONTH, vm.state.value.data?.params?.period)
        assertEquals(explanation, vm.state.value.periodChangeErrorPresentation)
        assertFalse(vm.state.value.periodChangeRetryReady)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(6, api.requests.size)
        assertTrue(api.requests.drop(3).all { it.period == PeriodTypeCode.MONTH })
    }

    @Test fun `publication timeout recovery preserves the original period wait until its expiry`() {
        api.answers += data()
        api.answers += data(recalculating = true)
        api.answers += AppError.RateLimited(null, 60)
        api.answers += Analytics(hasData = false)
        val vm = opened()
        statements.pendingImport = 42L
        vm.load(quiet = true)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        val failedTarget = api.requests.last()
        val explanation = assertNotNull(vm.state.value.periodChangeErrorPresentation)
        vm.refresh()
        scheduler.advanceTimeBy(30_000)
        scheduler.runCurrent()
        assertNull(vm.state.value.data)
        assertTrue(vm.state.value.preparationTimedOut)
        assertFalse(vm.state.value.loading)
        assertEquals(13, api.requests.size)
        assertEquals(explanation, vm.state.value.periodChangeErrorPresentation)
        assertFalse(vm.state.value.periodChangeRetryReady)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(13, api.requests.size)
        api.answers.clear()
        api.answers += data()
        api.answers += data(period = PeriodTypeCode.YEAR, date = "2026")
        vm.refresh()
        assertFalse(vm.state.value.preparationTimedOut)
        assertEquals(PeriodTypeCode.MONTH, vm.state.value.data?.params?.period)
        assertEquals(explanation, vm.state.value.periodChangeErrorPresentation)
        assertFalse(vm.state.value.periodChangeRetryReady)
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertEquals(14, api.requests.size)
        scheduler.advanceTimeBy(29_999)
        scheduler.runCurrent()
        assertFalse(vm.state.value.periodChangeRetryReady)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertTrue(vm.state.value.periodChangeRetryReady)
        assertEquals(14, api.requests.size, "wait expiry must not submit Year automatically")
        vm.retryPeriodChange()
        assertEquals(15, api.requests.size)
        assertEquals(failedTarget, api.requests.last())
        assertEquals(PeriodTypeCode.YEAR, vm.state.value.data?.params?.period)
    }

    @Test fun `fullscreen offline retry retains quota failure without changing the current error context`() {
        api.answers += AppError.Offline()
        val vm = opened()
        assertEquals(0, assistant.limitCalls)
        assistant.failure = AppError.Offline()
        vm.refresh()
        assertEquals(2, api.requests.size)
        assertEquals(1, assistant.limitCalls)
        assertNull(vm.state.value.data)
        assertTrue(vm.state.value.offline)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.refreshing)
        assertTrue(vm.state.value.limitErrorPresentation?.connectionFailure == true)
        assertTrue(vm.state.value.limitRetryReady)
    }

    @Test fun `quota failure before a late analytics success is retained for the newly shown card`() {
        api.answers += AppError.Offline()
        val vm = opened()
        val analytics = CompletableDeferred<Analytics>()
        api.reply = { analytics.await() }
        assistant.failure = AppError.Offline()
        vm.refresh()
        val quotaError = assertNotNull(vm.state.value.limitErrorPresentation)
        assertTrue(quotaError.connectionFailure)
        assertNull(vm.state.value.data)
        assertTrue(vm.state.value.refreshing)
        analytics.complete(data())
        assertEquals(true, vm.state.value.data?.hasData)
        assertEquals(quotaError, vm.state.value.limitErrorPresentation)
        assertFalse(vm.state.value.offline)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun `late quota failure remains visible after analytics succeeds`() {
        api.answers += AppError.Offline()
        val vm = opened()
        api.answers.clear()
        api.answers += data()
        val quota = CompletableDeferred<AssistantLimit>()
        assistant.reply = { quota.await() }
        vm.refresh()
        assertEquals(true, vm.state.value.data?.hasData)
        assertNull(vm.state.value.limitErrorPresentation)
        assertTrue(vm.state.value.refreshing)
        quota.completeExceptionally(AppError.Offline())
        assertEquals(true, vm.state.value.data?.hasData)
        assertTrue(vm.state.value.limitErrorPresentation?.connectionFailure == true)
        assertFalse(vm.state.value.refreshing)
    }

    private fun allTimeData(mode: TransferMode = TransferMode.WITH) = data(PeriodTypeCode.ALL_TIME, "all_time", mode).copy(
        period = ru.finassist.pf.core.api.model.AnalyticsPeriodInfo(
            range = DateRange(OffsetDateTime.parse("2025-10-30T00:00:00+03:00"), OffsetDateTime.parse("2026-10-31T00:00:00+03:00")),
            isCurrent = false, coverage = Coverage.PARTIAL,
            dataFrom = OffsetDateTime.parse("2025-10-30T00:00:00+03:00"),
            dataTo = OffsetDateTime.parse("2026-10-30T23:59:59+03:00"), gaps = emptyList(),
        ),
    )

    @Test fun `all-time sends its marker and preserves transfers and the successful calendar selections`() {
        api.answers += data(date = "2026-07", mode = TransferMode.WITHOUT)
        api.answers += allTimeData(TransferMode.WITHOUT)
        api.answers += data(date = "2026-07", mode = TransferMode.WITHOUT)
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.ALL_TIME)
        assertEquals(Request(PeriodTypeCode.ALL_TIME, PeriodKey.ALL_TIME, TransferMode.WITHOUT), api.requests.last())
        assertEquals(PeriodTypeCode.ALL_TIME, vm.state.value.data?.params?.period)
        vm.setPeriodType(PeriodTypeCode.ALL_TIME)
        assertEquals(2, api.requests.size)
        vm.setPeriodType(PeriodTypeCode.MONTH)
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-07"), TransferMode.WITHOUT), api.requests.last())
    }

    @Test fun `a first calendar selection from all-time uses the last covered profile instant`() {
        reportZone.set("Asia/Vladivostok")
        api.answers += allTimeData().let { it.copy(period = it.period!!.copy(
            coverage = Coverage.COMPLETE,
            dataTo = OffsetDateTime.parse("2026-12-31T16:30:00Z"),
            range = DateRange(OffsetDateTime.parse("2025-01-01T00:00:00+10:00"), OffsetDateTime.parse("2027-01-02T00:00:00+10:00")),
        )) }
        api.answers += data(PeriodTypeCode.QUARTER, "2027-Q1")
        val vm = opened(AnalyticsParams(PeriodTypeCode.ALL_TIME, PeriodKey.ALL_TIME, TransferMode.WITH))
        vm.setPeriodType(PeriodTypeCode.QUARTER)
        assertEquals(Request(PeriodTypeCode.QUARTER, PeriodKey("2027-Q1"), TransferMode.WITH), api.requests.last())
    }

    @Test fun `failed all-time switch retains the old screen and retries the exact marker only explicitly`() {
        api.answers += data(date = "2026-09", mode = TransferMode.WITHOUT)
        api.answers += AppError.Offline()
        api.answers += allTimeData(TransferMode.WITHOUT)
        val vm = opened()
        val previous = vm.state.value.data
        vm.setPeriodType(PeriodTypeCode.ALL_TIME)
        assertEquals(previous, vm.state.value.data)
        assertNotNull(vm.state.value.periodChangeErrorPresentation)
        vm.onResume(null)
        assertEquals(2, api.requests.size)
        vm.retryPeriodChange()
        assertEquals(Request(PeriodTypeCode.ALL_TIME, PeriodKey.ALL_TIME, TransferMode.WITHOUT), api.requests.last())
        assertEquals(PeriodTypeCode.ALL_TIME, vm.state.value.data?.params?.period)
        assertNull(vm.state.value.periodChangeErrorPresentation)
    }

    @Test fun `a deleted source reloads all-time instead of reverting to a calendar default`() {
        api.answers += allTimeData(TransferMode.WITHOUT)
        api.answers += Analytics(hasData = false)
        val vm = opened(AnalyticsParams(PeriodTypeCode.ALL_TIME, PeriodKey.ALL_TIME, TransferMode.WITHOUT))
        statements.events.tryEmit(StatementsEvent.UploadDeleted("source"))
        assertEquals(Request(PeriodTypeCode.ALL_TIME, PeriodKey.ALL_TIME, TransferMode.WITHOUT), api.requests.last())
        assertEquals(false, vm.state.value.data?.hasData)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `unsupported route params fall back to the default known request`() {
        api.answers += data()
        val vm = opened(AnalyticsParams(PeriodTypeCode.UNKNOWN, PeriodKey("2026-W40"), TransferMode.WITHOUT))
        assertEquals(Request(PeriodTypeCode.MONTH, null, TransferMode.WITH), api.requests.single())
        vm.setPeriodType(PeriodTypeCode.UNKNOWN)
        assertEquals(1, api.requests.size)
    }


    private val monthlyBlock = Block(id = "monthly_chart", type = BlockType.CHART)
    private fun monthAction(key: String) = BlockAction(ActionType.SELECT_ANALYTICS_PERIOD,
        period = PeriodTypeCode.MONTH, date = PeriodKey(key))
    private fun AnalyticsViewModel.selectMonth(key: String, source: Block = monthlyBlock) =
        onAction(source, monthAction(key)) { error("month selection must not navigate outside Analytics") }

    @Test fun `a chart action changes type and exact year month atomically and deduplicates an in-flight target`() {
        val original = data(PeriodTypeCode.YEAR, "2026", TransferMode.WITHOUT)
        api.answers += original
        val vm = opened(original.params)
        val pending = CompletableDeferred<Analytics>()
        api.reply = { pending.await() }
        vm.selectMonth("2025-09")
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2025-09"), TransferMode.WITHOUT), api.requests.last())
        assertEquals(original, vm.state.value.data, "shown type, date and amounts stay together until success")
        vm.selectMonth("2025-09")
        assertEquals(2, api.requests.size)
        val selected = data(PeriodTypeCode.MONTH, "2025-09", TransferMode.WITHOUT)
        pending.complete(selected)
        assertEquals(selected, vm.state.value.data)
        assertEquals(TransferMode.WITHOUT, vm.currentTransferMode())
        vm.selectMonth("2025-09")
        assertEquals(2, api.requests.size, "the currently shown month is a no-op")
        assertEquals(1, names().count { it == Events.ANALYTICS_PERIOD_CHANGED.name })
    }

    @Test fun `quarter year and all-time chart actions each submit one month request with the selected mode`() {
        for ((type, key) in listOf(PeriodTypeCode.QUARTER to "2026-Q3", PeriodTypeCode.YEAR to "2026", PeriodTypeCode.ALL_TIME to "all_time")) {
            val localApi = FakeApi()
            localApi.answers += data(type, key, TransferMode.WITHOUT)
            localApi.answers += data(PeriodTypeCode.MONTH, "2025-09", TransferMode.WITHOUT)
            val vm = AnalyticsViewModel(localApi, assistant, statements, operations, flags, tracker, reportZone,
                ru.finassist.pf.core.common.time.ElapsedClock { scheduler.currentTime }).apply { init(null) }
            vm.selectMonth("2025-09")
            assertEquals(2, localApi.requests.size, type.code)
            assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2025-09"), TransferMode.WITHOUT), localApi.requests.last())
            assertEquals(PeriodKey("2025-09"), vm.state.value.data?.params?.date)
        }
    }

    @Test fun `invalid targets and non-monthly contexts never submit chart selection`() {
        api.answers += data()
        val vm = opened()
        val action = monthAction("2026-08")
        listOf(action.copy(period = null), action.copy(date = null), action.copy(period = PeriodTypeCode.UNKNOWN),
            action.copy(date = PeriodKey("2026-13")), action.copy(date = PeriodKey("2026")),
            action.copy(period = PeriodTypeCode.ALL_TIME, date = PeriodKey.ALL_TIME)).forEach { target ->
            vm.onAction(monthlyBlock, target) { error("must stay inert") }
        }
        listOf(monthlyBlock.copy(id = "balance"), monthlyBlock.copy(id = null), monthlyBlock.copy(type = BlockType.ROWS),
            monthlyBlock.copy(v = 2), monthlyBlock.copy(status = ru.finassist.pf.core.api.model.BlockStatus.LOCKED)).forEach { source ->
            vm.selectMonth("2026-08", source)
        }
        assertEquals(1, api.requests.size)
        assertEquals(PeriodKey("2026-09"), vm.state.value.data?.params?.date)
    }

    @Test fun `a failed goTo key change keeps prior data and retries its exact target only explicitly`() {
        val original = data(mode = TransferMode.WITHOUT)
        api.answers += original
        api.answers += AppError.Offline()
        api.answers += data(date = "2026-08", mode = TransferMode.WITHOUT)
        val vm = opened(original.params)
        vm.goTo(PeriodKey("2026-08"))
        assertEquals(original, vm.state.value.data)
        assertTrue(vm.state.value.periodChangeFailed)
        assertTrue(vm.state.value.periodChangeRetryReady)
        assertNull(vm.state.value.errorPresentation)
        assertNull(vm.state.value.snackbar)
        vm.onResume(null)
        assertEquals(2, api.requests.size, "resume cannot replace the notice or submit either period")
        vm.retryPeriodChange()
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-08"), TransferMode.WITHOUT), api.requests.last())
        assertEquals(PeriodKey("2026-08"), vm.state.value.data?.params?.date)
        assertFalse(vm.state.value.periodChangeFailed)
        vm.retryPeriodChange()
        assertEquals(3, api.requests.size)
    }

    @Test fun `repeated goTo cannot bypass a failed target original request refusal`() {
        val original = data()
        api.answers += original
        api.answers += refused()
        api.answers += data(date = "2026-07")
        val vm = opened()
        vm.goTo(PeriodKey("2026-08"))
        val explanation = assertNotNull(vm.state.value.periodChangeErrorPresentation)
        vm.goTo(PeriodKey("2026-08"))
        vm.retryPeriodChange()
        vm.onResume(null)
        assertEquals(2, api.requests.size)
        assertEquals(original, vm.state.value.data)
        assertEquals(explanation, vm.state.value.periodChangeErrorPresentation)
        assertFalse(vm.state.value.periodChangeRetryReady)
        vm.goTo(PeriodKey("2026-07"))
        assertEquals(3, api.requests.size, "a different key retires the previous request context")
        assertEquals(PeriodKey("2026-07"), vm.state.value.data?.params?.date)
    }

    @Test fun `repeated goTo preserves the failed target Retry-After boundary and never resumes it`() {
        val original = data()
        api.answers += original
        api.answers += AppError.RateLimited(null, 3, ru.finassist.pf.core.common.error.ErrorResponseInfo(
            "RATE_LIMITED", 429, requestRetryable = true,
            timing = ru.finassist.pf.core.common.time.ResponseTiming(receivedElapsedMillis = scheduler.currentTime)))
        api.answers += data(date = "2026-08")
        val vm = opened()
        vm.goTo(PeriodKey("2026-08"))
        scheduler.advanceTimeBy(2_999)
        scheduler.runCurrent()
        vm.goTo(PeriodKey("2026-08"))
        vm.retryPeriodChange()
        vm.onResume(null)
        assertEquals(2, api.requests.size)
        assertEquals(original, vm.state.value.data)
        assertFalse(vm.state.value.periodChangeRetryReady)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertTrue(vm.state.value.periodChangeRetryReady)
        vm.onResume(null)
        assertEquals(2, api.requests.size, "deadline expiry does not submit the failed key")
        vm.goTo(PeriodKey("2026-08"))
        assertEquals(3, api.requests.size)
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-08"), TransferMode.WITH), api.requests.last())
        assertFalse(vm.state.value.periodChangeFailed)
    }

    @Test fun `a failed month-to-month chart action keeps the shown data and retries the exact target explicitly`() {
        val original = data(mode = TransferMode.WITHOUT)
        api.answers += original
        api.answers += AppError.Server(500)
        api.answers += data(date = "2026-08", mode = TransferMode.WITHOUT)
        val vm = opened(original.params)
        vm.selectMonth("2026-08")
        assertEquals(original, vm.state.value.data)
        assertTrue(vm.state.value.periodChangeFailed)
        assertNull(vm.state.value.errorPresentation)
        assertNull(vm.state.value.snackbar)
        vm.onResume(null)
        assertEquals(2, api.requests.size, "resume does not replace or retry the failed target")
        vm.retryPeriodChange()
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-08"), TransferMode.WITHOUT), api.requests.last())
        assertEquals(PeriodKey("2026-08"), vm.state.value.data?.params?.date)
        assertFalse(vm.state.value.periodChangeFailed)
    }

    @Test fun `a repeated failed chart target cannot lift its direct retry refusal`() {
        api.answers += data()
        api.answers += refused()
        api.answers += data(date = "2026-07")
        val vm = opened()
        vm.selectMonth("2026-08")
        assertTrue(vm.state.value.periodChangeFailed)
        assertFalse(vm.state.value.periodChangeRetryReady)
        vm.selectMonth("2026-08")
        vm.retryPeriodChange()
        vm.onResume(null)
        assertEquals(2, api.requests.size)
        vm.selectMonth("2026-07")
        assertEquals(3, api.requests.size, "a different target retires the original refusal")
        assertEquals(PeriodKey("2026-07"), vm.state.value.data?.params?.date)
        assertFalse(vm.state.value.periodChangeFailed)
    }

    @Test fun `a repeated chart target preserves the captured Retry-After deadline`() {
        api.answers += data()
        api.answers += AppError.RateLimited(null, 3, ru.finassist.pf.core.common.error.ErrorResponseInfo(
            "RATE_LIMITED", 429, requestRetryable = true,
            timing = ru.finassist.pf.core.common.time.ResponseTiming(receivedElapsedMillis = scheduler.currentTime)))
        api.answers += data(date = "2026-08")
        val vm = opened()
        vm.selectMonth("2026-08")
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        vm.selectMonth("2026-08")
        vm.retryPeriodChange()
        assertEquals(2, api.requests.size)
        assertFalse(vm.state.value.periodChangeRetryReady)
        scheduler.advanceTimeBy(1_999)
        scheduler.runCurrent()
        vm.selectMonth("2026-08")
        assertEquals(2, api.requests.size)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        vm.selectMonth("2026-08")
        assertEquals(3, api.requests.size)
        assertEquals(PeriodKey("2026-08"), vm.state.value.data?.params?.date)
    }

    @Test fun `a failed chart target does not replace a segment's last successful month`() {
        api.answers += data()
        api.answers += data(PeriodTypeCode.YEAR, "2026")
        api.answers += AppError.Server(500)
        api.answers += data()
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        vm.selectMonth("2026-05")
        assertEquals(PeriodTypeCode.YEAR, vm.state.value.data?.params?.period)
        vm.setPeriodType(PeriodTypeCode.MONTH)
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH), api.requests.last())
        assertFalse(vm.state.value.periodChangeFailed)
    }

    @Test fun `the selected chart month becomes the successful month segment memory`() {
        api.answers += data(PeriodTypeCode.YEAR, "2026")
        api.answers += data(date = "2025-09")
        api.answers += data(PeriodTypeCode.YEAR, "2026")
        api.answers += data(date = "2025-09")
        val vm = opened()
        vm.selectMonth("2025-09")
        vm.setPeriodType(PeriodTypeCode.YEAR)
        vm.setPeriodType(PeriodTypeCode.MONTH)
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2025-09"), TransferMode.WITH), api.requests.last())
    }

    @Test fun `a changed transfer mode retires a failed chart target`() {
        api.answers += data()
        api.answers += AppError.Server(500)
        api.answers += data(mode = TransferMode.WITHOUT)
        val vm = opened()
        vm.selectMonth("2026-08")
        vm.setTransferMode(TransferMode.WITHOUT)
        assertEquals(Request(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITHOUT), api.requests.last())
        assertFalse(vm.state.value.periodChangeFailed)
        vm.retryPeriodChange()
        assertEquals(3, api.requests.size)
    }

    @Test fun `the current chart month cannot clear a direct refresh refusal`() {
        api.answers += data()
        api.answers += refused()
        val vm = opened()
        vm.load()
        val explanation = vm.state.value.errorPresentation
        vm.selectMonth("2026-09")
        assertEquals(2, api.requests.size)
        assertFalse(vm.state.value.retryReady)
        assertEquals(explanation, vm.state.value.errorPresentation)
    }

    @Test fun `recalculation preserves the entire ready snapshot until a ready replacement`() {
        val previous = data().copy(currency = "RUB", blocks = listOf(Block(id = "old", type = BlockType.UNKNOWN)))
        val changed = data().copy(currency = "EUR", blocks = listOf(Block(id = "new", type = BlockType.UNKNOWN)))
        api.answers += previous
        api.answers += changed.copy(state = changed.state!!.copy(recalculating = true))
        api.answers += changed.copy(state = changed.state!!.copy(recalculating = true))
        api.answers += changed
        val vm = opened()
        vm.onBlockShown(Block(id = "old", type = BlockType.INSIGHT_CARD))
        val response = vm.state.value.response
        vm.load(quiet = true)
        vm.onBlockShown(Block(id = "old", type = BlockType.INSIGHT_CARD))
        assertEquals(response, vm.state.value.response)
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()
        vm.onBlockShown(Block(id = "old", type = BlockType.INSIGHT_CARD))
        assertEquals(response, vm.state.value.response)
        assertEquals(1, tracker.events.count { it.first == Events.ANALYTICS_BLOCK_EXPOSED })
        assertEquals(previous, vm.state.value.data)
        assertTrue(vm.state.value.recalculating)
        assertFalse(vm.state.value.loading)
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()
        assertEquals(changed, vm.state.value.data)
        assertFalse(vm.state.value.recalculating)
    }

    @Test fun `a recalculating period without a ready cache prepares instead of leaking another period`() {
        api.answers += data()
        api.answers += data(period = PeriodTypeCode.YEAR, date = "2026", recalculating = true)
        api.answers += data(period = PeriodTypeCode.YEAR, date = "2026")
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        assertNull(vm.state.value.data)
        assertTrue(vm.state.value.loading)
        assertTrue(vm.state.value.recalculating)
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()
        assertEquals(PeriodTypeCode.YEAR, vm.state.value.data?.params?.period)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `recalculation times out with cached data and explicit retry recovers`() {
        val previous = data()
        api.answers += previous
        api.answers += data(recalculating = true)
        val vm = opened()
        vm.load(quiet = true)
        scheduler.advanceTimeBy(30_000)
        scheduler.runCurrent()
        assertEquals(11, api.requests.size)
        assertEquals(previous, vm.state.value.data)
        assertTrue(vm.state.value.recalculationPaused)
        assertFalse(vm.state.value.preparationTimedOut)
        vm.onResume(null)
        assertEquals(11, api.requests.size)
        api.answers.clear()
        api.answers += data().copy(currency = "EUR")
        vm.refresh()
        assertEquals("EUR", vm.state.value.data?.currency)
        assertFalse(vm.state.value.recalculating)
        assertFalse(vm.state.value.recalculationPaused)
    }

    @Test fun `recalculation without cache times out with preparation retry`() {
        api.answers += data(recalculating = true)
        val vm = opened()
        scheduler.advanceTimeBy(30_000)
        scheduler.runCurrent()
        assertEquals(10, api.requests.size)
        assertNull(vm.state.value.data)
        assertTrue(vm.state.value.preparationTimedOut)
        assertFalse(vm.state.value.loading)
        vm.onResume(null)
        assertEquals(10, api.requests.size)
        api.answers.clear()
        api.answers += data()
        vm.refresh()
        assertNotNull(vm.state.value.data)
        assertFalse(vm.state.value.preparationTimedOut)
    }

    @Test fun `a late ready response cannot replace newer data or poison its cache`() {
        api.answers += data()
        val vm = opened()
        val late = CompletableDeferred<Analytics>()
        api.reply = { withContext(NonCancellable) { late.await() } }
        vm.load(quiet = true)
        val fresh = data().copy(currency = "EUR")
        api.reply = { fresh }
        vm.load(quiet = true)
        late.complete(data().copy(currency = "USD"))
        scheduler.runCurrent()
        assertEquals(fresh, vm.state.value.data)
        api.reply = { fresh.copy(state = fresh.state!!.copy(recalculating = true)) }
        vm.load(quiet = true)
        assertEquals(fresh, vm.state.value.data)
        assertTrue(vm.state.value.recalculating)
    }

    @Test fun `a late error cannot stop the newer recalculation or impose retry refusal`() {
        api.answers += data()
        val vm = opened()
        val late = CompletableDeferred<Unit>()
        api.reply = { withContext(NonCancellable) { late.await(); throw refused() } }
        vm.load(quiet = true)
        api.reply = { data(recalculating = true) }
        vm.load(quiet = true)
        late.complete(Unit)
        scheduler.runCurrent()
        assertTrue(vm.state.value.recalculating)
        assertFalse(vm.state.value.recalculationPaused)
        assertNull(vm.state.value.errorPresentation)
        assertTrue(vm.state.value.retryReady)
        api.reply = { data() }
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()
        assertFalse(vm.state.value.recalculating)
    }

    @Test fun `mutation start invalidates even a read whose transport ignores cancellation`() {
        val previous = data()
        api.answers += previous
        val vm = opened()
        val late = CompletableDeferred<Analytics>()
        val mutation = CompletableDeferred<Unit>()
        api.reply = { withContext(NonCancellable) { late.await() } }
        vm.load(quiet = true)
        api.dismissReply = { mutation.await() }
        vm.dismissRegular("first")
        late.complete(data(recalculating = true).copy(currency = "USD"))
        scheduler.runCurrent()
        assertEquals(previous, vm.state.value.data)
        assertFalse(vm.state.value.recalculating)
        api.reply = { data(recalculating = true) }
        mutation.complete(Unit)
        scheduler.runCurrent()
        assertEquals(previous, vm.state.value.data)
        assertTrue(vm.state.value.recalculating)
    }

    @Test fun `different regular payment mutations serialize and a repeated pending tap is ignored`() {
        api.answers += data()
        val vm = opened()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        api.dismissReply = { if (it == "first") first.await() else second.await() }
        vm.dismissRegular("first")
        vm.dismissRegular("first")
        vm.dismissRegular("second")
        assertEquals(listOf("first"), api.dismissed)
        first.complete(Unit)
        scheduler.runCurrent()
        assertEquals(listOf("first", "second"), api.dismissed)
        second.complete(Unit)
        scheduler.runCurrent()
        assertEquals(3, api.requests.size)
        assertEquals(2, tracker.events.count { it.first == Events.ANALYTICS_REGULAR_DISMISSED })
    }

    @Test fun `a failed mutation releases the block for a queued different payment`() {
        api.answers += data()
        val vm = opened()
        val first = CompletableDeferred<Unit>()
        api.dismissReply = { if (it == "first") { first.await(); throw AppError.Offline() } }
        vm.dismissRegular("first")
        vm.dismissRegular("second")
        first.complete(Unit)
        scheduler.runCurrent()
        assertEquals(listOf("first", "second"), api.dismissed)
        assertEquals(1, tracker.events.count { it.first == Events.ANALYTICS_REGULAR_DISMISSED })
        assertEquals(2, api.requests.size)
    }

    @Test fun `returning to a cached context during recalculation restores its own snapshot with a new exposure epoch`() {
        val month = data()
        val year = data(period = PeriodTypeCode.YEAR, date = "2026")
        api.answers += month
        api.answers += year
        api.answers += data(recalculating = true)
        val vm = opened()
        vm.setPeriodType(PeriodTypeCode.YEAR)
        val epoch = vm.state.value.response
        vm.setPeriodType(PeriodTypeCode.MONTH)
        assertEquals(month, vm.state.value.data)
        assertEquals(epoch + 1, vm.state.value.response)
        assertTrue(vm.state.value.recalculating)
    }

    @Test fun `a changed report zone cannot reuse cached financial data`() {
        reportZone.set("Europe/Moscow")
        api.answers += data()
        api.answers += data(recalculating = true)
        val vm = opened()
        reportZone.set("Asia/Vladivostok")
        assertNull(vm.state.value.data)
        assertTrue(vm.state.value.loading)
        assertTrue(vm.state.value.recalculating)
    }

    @Test fun `a changed experiment cannot reuse a differently composed snapshot`() {
        api.answers += data()
        api.answers += data(recalculating = true)
        val vm = opened()
        flags.set(Experiment.ANALYTICS_ORDER, "b")
        assertNull(vm.state.value.data)
        assertTrue(vm.state.value.loading)
        assertEquals("analytics_order:b", api.extras.last().second)
    }

    @Test fun `an uncached recalculation interrupted by offline keeps recovery instead of upload advice`() {
        api.answers += data(recalculating = true)
        api.answers += AppError.Offline()
        val vm = opened()
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()
        assertNull(vm.state.value.data)
        assertTrue(vm.state.value.offline)
        assertTrue(vm.state.value.recalculationPaused)
        assertFalse(vm.state.value.loading)
        api.answers.clear()
        api.answers += data()
        vm.refresh()
        assertNotNull(vm.state.value.data)
        assertFalse(vm.state.value.offline)
        assertFalse(vm.state.value.recalculating)
    }

}
