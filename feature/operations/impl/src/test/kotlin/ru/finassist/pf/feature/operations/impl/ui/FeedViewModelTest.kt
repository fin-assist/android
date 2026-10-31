package ru.finassist.pf.feature.operations.impl.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.finassist.pf.core.api.model.FeedState
import ru.finassist.pf.core.api.model.MonthSummary
import ru.finassist.pf.core.api.model.OperationsList
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.feature.statements.api.StatementsEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {
    private val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
    private val api = FakeOperationsApi()
    private val statements = FakeStatements()
    private val operations = FakeOperationChanges()
    private val flags = FakeFlags()
    private val tracker = FakeTracker()

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val reportZone = ru.finassist.pf.core.common.time.ReportZone { java.time.ZoneId.of("Europe/Moscow") }

    private fun vm() = FeedViewModel(api, statements, operations, operations, flags, tracker, reportZone, ru.finassist.pf.core.common.time.ElapsedClock { scheduler.currentTime })

    @Test fun `a new profile zone reloads the feed from the top, the first known zone does not (FIN-92)`() {
        api.lists += page("op_1", next = 1)
        api.lists += page("op_2")
        reportZone.set("Europe/Moscow")
        val vm = vm()
        assertEquals(1, api.queries.size)

        reportZone.set("Asia/Vladivostok")
        assertEquals(listOf(OperationsQuery(), OperationsQuery()), api.queries)
        assertEquals(listOf("op_2"), vm.state.value.items.map { it.id })
    }

    private fun month(name: String) = MonthSummary(name, DateRange(at(1), at(30)), Money(1_000_00), Money(2_000_00))

    private fun page(vararg ids: String, next: Int? = null, month: String = "2026-09") = OperationsList(
        items = ids.map { item(it) },
        summary = listOf(month(month)),
        state = FeedState(stale = false, lastOperationAt = at(25)),
        nextBefore = next?.let { at(it) },
    )

    @Test fun `opening loads the first portion of the feed`() {
        api.lists += page("op_1", "op_2", next = 1)
        val s = vm().state.value
        assertEquals(listOf(OperationsQuery()), api.queries)
        assertEquals(listOf("op_1", "op_2"), s.items.map { it.id })
        assertEquals(listOf("2026-09"), s.summary.map { it.month })
        assertEquals(FeedState(stale = false, lastOperationAt = at(25)), s.state)
        assertEquals(at(1), s.nextBefore)
        assertFalse(s.loading)
        assertFalse(s.isEmpty)
        assertEquals(listOf(Events.OPERATIONS_FEED_OPENED.name), tracker.names())
    }

    @Test fun `no operations and no older portion is the empty feed`() {
        api.lists += OperationsList(emptyList())
        val s = vm().state.value
        assertTrue(s.isEmpty)
        assertEquals(emptyList(), s.summary) // a missing summary is an empty one
    }

    @Test fun `an empty portion with an older one behind it is not the empty feed`() {
        api.lists += OperationsList(emptyList(), nextBefore = at(1))
        assertFalse(vm().state.value.isEmpty)
    }

    @Test fun `no network on the first load is the offline state, and retry recovers`() {
        api.lists += AppError.Offline()
        api.lists += page("op_1")
        val vm = vm()
        assertTrue(vm.state.value.offline)
        assertEquals(null, vm.state.value.errorPresentation) // the tagged full-screen connection state owns the hint
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.isEmpty) // offline is not «no operations»

        vm.load()
        assertFalse(vm.state.value.offline)
        assertEquals(listOf("op_1"), vm.state.value.items.map { it.id })
    }

    @Test fun `a disconnected refresh keeps rows and exposes the local hint until retry succeeds`() {
        api.lists += page("op_1")
        api.lists += AppError.Offline()
        api.lists += page("op_1", "op_2")
        val vm = vm()
        vm.refresh()
        assertEquals(listOf("op_1"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.offline)
        assertTrue(vm.state.value.errorPresentation?.connectionFailure == true)
        vm.load()
        assertEquals(null, vm.state.value.errorPresentation)
        assertEquals(listOf("op_1", "op_2"), vm.state.value.items.map { it.id })
    }

    @Test fun `a failed refresh keeps the list on screen`() {
        api.lists += page("op_1")
        api.lists += AppError.Server(500)
        val vm = vm()
        vm.refresh()
        val s = vm.state.value
        assertEquals(listOf("op_1"), s.items.map { it.id })
        assertFalse(s.offline)
        assertFalse(s.refreshing)
    }

    @Test fun `unknown safe server warning preserves loaded rows and false blocks direct refresh`() {
        val presentation = ru.finassist.pf.core.common.error.ErrorPresentation(
            ru.finassist.pf.core.common.error.UserMessage.from("Сервис временно ограничил чтение"),
            ru.finassist.pf.core.common.error.ErrorSeverity.WARNING,
        )
        api.lists += page("op_1")
        api.lists += AppError.Api("FUTURE_POLICY", 429, response = ru.finassist.pf.core.common.error.ErrorResponseInfo(
            "FUTURE_POLICY", 429, presentation = presentation, requestRetryable = false,
        ))
        val vm = vm()
        vm.refresh()
        assertEquals(listOf("op_1"), vm.state.value.items.map { it.id })
        assertEquals(presentation, vm.state.value.errorPresentation)
        assertFalse(vm.state.value.offline)
        vm.load()
        vm.refresh()
        assertEquals(2, api.queries.size)
        // A real import changes the data context and may refresh it.
        api.lists.clear()
        api.lists += page("op_2")
        statements.events.tryEmit(StatementsEvent.UploadDeleted("u1"))
        assertEquals(listOf("op_2"), vm.state.value.items.map { it.id })
    }

    @Test fun `manual retry waits server duration without losing loaded rows`() {
        api.lists += page("op_1")
        api.lists += AppError.RateLimited(null, 3, ru.finassist.pf.core.common.error.ErrorResponseInfo(
            "RATE_LIMITED", 429, requestRetryable = true,
            timing = ru.finassist.pf.core.common.time.ResponseTiming(receivedElapsedMillis = 0),
        ))
        val vm = vm()
        vm.refresh()
        assertFalse(vm.state.value.retryReady)
        vm.load()
        assertEquals(2, api.queries.size)
        scheduler.advanceTimeBy(2999)
        scheduler.runCurrent()
        assertFalse(vm.state.value.retryReady)
        assertEquals(listOf("op_1"), vm.state.value.items.map { it.id })
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertTrue(vm.state.value.retryReady)
        api.lists.clear()
        api.lists += page("op_2")
        vm.load()
        assertEquals(3, api.queries.size)
        assertEquals(listOf("op_2"), vm.state.value.items.map { it.id })
    }

    @Test fun `a reload with items on screen does not show the full-screen loader`() {
        api.lists += page("op_1")
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        vm.load()
        assertFalse(vm.state.value.loading)
        vm.refresh()
        assertTrue(vm.state.value.refreshing)
        gate.complete(Unit)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun `the next portion is requested before the cursor and appended without duplicates`() {
        api.lists += page("op_1", "op_2", next = 1)
        api.lists += page("op_2", "op_3", next = null, month = "2026-08")
        val vm = vm()
        vm.loadMore()
        val s = vm.state.value
        assertEquals(OperationsQuery(before = at(1)), api.queries.last())
        assertEquals(listOf("op_1", "op_2", "op_3"), s.items.map { it.id })
        assertEquals(listOf("2026-09", "2026-08"), s.summary.map { it.month })
        assertEquals(null, s.nextBefore)
        assertFalse(s.loadingMore)
    }

    @Test fun `without a cursor there is nothing more to load`() {
        api.lists += page("op_1")
        val vm = vm()
        vm.loadMore()
        assertEquals(1, api.queries.size)
    }

    @Test fun `a second scroll to the end while a portion is loading does not request it twice`() {
        api.lists += page("op_1", next = 1)
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        vm.loadMore()
        assertTrue(vm.state.value.loadingMore)
        vm.loadMore()
        assertEquals(2, api.queries.size)
        gate.complete(Unit)
        assertFalse(vm.state.value.loadingMore)
    }

    @Test fun `no portion is requested while the first one is reloading behind the full-screen loader`() {
        api.lists += OperationsList(emptyList(), nextBefore = at(1))
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        vm.load()
        assertTrue(vm.state.value.loading)
        vm.loadMore()
        assertEquals(listOf(OperationsQuery(), OperationsQuery()), api.queries)
        gate.complete(Unit)
    }

    @Test fun `a failed portion keeps the list and shows the retry row until a retry succeeds`() {
        api.lists += page("op_1", next = 1)
        api.lists += AppError.Offline()
        api.lists += page("op_2")
        val vm = vm()
        vm.loadMore()
        assertTrue(vm.state.value.moreFailed)
        assertFalse(vm.state.value.loadingMore)
        assertEquals(listOf("op_1"), vm.state.value.items.map { it.id })
        assertEquals(at(1), vm.state.value.nextBefore)

        vm.loadMore()
        assertFalse(vm.state.value.moreFailed)
        assertEquals(listOf("op_1", "op_2"), vm.state.value.items.map { it.id })
    }

    @Test fun `an import or a deleted statement reloads the feed from the top`() {
        api.lists += page("op_1", next = 1)
        api.lists += page("op_0", "op_1", next = 1)
        val vm = vm()
        statements.events.tryEmit(StatementsEvent.UploadDeleted("u1"))
        assertEquals(listOf(OperationsQuery(), OperationsQuery()), api.queries)
        assertEquals(listOf("op_0", "op_1"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun `a category changed on the details screen reloads the feed`() {
        api.lists += page("op_1")
        vm()
        operations.notifyCategoryChanged()
        assertEquals(2, api.queries.size)
        assertEquals(OperationsQuery(), api.queries.last())
    }

    @Test fun `a reload drops the error of the next portion`() {
        api.lists += page("op_1", next = 1)
        api.lists += AppError.Offline()
        api.lists += page("op_1", next = 1)
        val vm = vm()
        vm.loadMore()
        assertTrue(vm.state.value.moreFailed)
        statements.events.tryEmit(StatementsEvent.UploadDeleted("u1"))
        assertFalse(vm.state.value.moreFailed)
    }

    @Test fun `the upload button follows the statements upload flag, also when it changes later`() {
        flags.set(Flag.STATEMENTS_UPLOAD, false)
        val vm = vm()
        assertFalse(vm.state.value.uploadEnabled)
        flags.set(Flag.STATEMENTS_UPLOAD, true)
        assertTrue(vm.state.value.uploadEnabled)
    }
    @Test fun `a timestamp from details stays in the feed when the canonical refresh fails`() {
        api.lists += page("op_1")
        api.lists += AppError.Offline()
        val vm = vm()
        val updated = details("op_1").copy(occurredAt = at(25).plusMonths(1))
        operations.updateRecords(listOf(updated))
        assertEquals(updated.occurredAt, vm.state.value.items.single().occurredAt)
        assertEquals(2, api.queries.size)
        assertFalse(vm.state.value.offline)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun `a timestamp crossing a month reloads canonical feed totals and rows`() {
        api.lists += page("op_1")
        api.lists += page("op_2", month = "2026-10")
        val vm = vm()
        operations.updateRecords(listOf(details("op_1").copy(occurredAt = at(25).plusMonths(1))))
        assertEquals(listOf("op_2"), vm.state.value.items.map { it.id })
        assertEquals(listOf("2026-10"), vm.state.value.summary.map { it.month })
    }

    private fun pendingReplies(vararg replies: CompletableDeferred<OperationsList>) {
        val queue = ArrayDeque(replies.toList())
        api.listReply = { queue.removeFirst().await() }
    }

    @Test fun `old first page cannot roll back the timestamp received from details`() {
        api.lists += page("op_1", next = 1)
        val vm = vm()
        val old = CompletableDeferred<OperationsList>()
        val fresh = CompletableDeferred<OperationsList>()
        pendingReplies(old, fresh)
        vm.refresh()
        val updated = details("op_1").copy(occurredAt = at(25).plusMonths(1))
        operations.updateRecords(listOf(updated))
        val canonical = page("op_1", month = "2026-10").copy(items = listOf(updated.toItem()))
        fresh.complete(canonical)
        old.complete(page("op_1", next = 1))
        assertEquals(canonical.items, vm.state.value.items)
        assertEquals(canonical.summary, vm.state.value.summary)
        assertEquals(null, vm.state.value.nextBefore)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun `old first page error cannot clear a newer refresh state`() {
        api.lists += page("op_1")
        val vm = vm()
        val old = CompletableDeferred<OperationsList>()
        val fresh = CompletableDeferred<OperationsList>()
        pendingReplies(old, fresh)
        vm.refresh()
        // A confirmed server-data change supersedes an old read; repeated gestures are deduplicated.
        vm.refresh(changedContext = true)
        old.completeExceptionally(AppError.Offline())
        assertTrue(vm.state.value.refreshing)
        assertFalse(vm.state.value.offline)
        fresh.complete(page("op_2"))
        assertEquals(listOf("op_2"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun `old next page cannot append rows or change cursor after a timestamp refresh`() {
        api.lists += page("op_1", next = 1)
        val vm = vm()
        val oldPage = CompletableDeferred<OperationsList>()
        val fresh = CompletableDeferred<OperationsList>()
        val resumed = CompletableDeferred<OperationsList>()
        pendingReplies(oldPage, fresh, resumed)
        vm.loadMore()
        val updated = details("op_1").copy(occurredAt = at(25).plusMonths(1))
        operations.updateRecords(listOf(updated))
        assertFalse(vm.state.value.loadingMore)
        val canonical = page("op_1", next = 2, month = "2026-10").copy(items = listOf(updated.toItem()))
        fresh.complete(canonical)
        oldPage.complete(page("op_old", month = "2026-08"))
        assertEquals(canonical.items, vm.state.value.items)
        assertEquals(canonical.summary, vm.state.value.summary)
        assertEquals(at(2), vm.state.value.nextBefore)
        assertTrue(vm.state.value.loadingMore)
        assertEquals(OperationsQuery(before = at(2)), api.queries.last())
        resumed.complete(page("op_new", month = "2026-09"))
        assertEquals(listOf("op_1", "op_new"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.loadingMore)
    }

    @Test fun `old next page error cannot fail a newer pagination request`() {
        api.lists += page("op_1", next = 1)
        val vm = vm()
        val oldPage = CompletableDeferred<OperationsList>()
        val fresh = CompletableDeferred<OperationsList>()
        val newPage = CompletableDeferred<OperationsList>()
        pendingReplies(oldPage, fresh, newPage)
        vm.loadMore()
        vm.refresh()
        fresh.complete(page("op_2", next = 2))
        vm.loadMore()
        oldPage.completeExceptionally(AppError.Offline())
        assertTrue(vm.state.value.loadingMore)
        assertFalse(vm.state.value.moreFailed)
        newPage.complete(page("op_3"))
        assertEquals(listOf("op_2", "op_3"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.loadingMore)
        assertFalse(vm.state.value.moreFailed)
    }

    @Test fun `pagination waits while a shown feed is refreshing`() {
        api.lists += page("op_1", next = 1)
        val vm = vm()
        val fresh = CompletableDeferred<OperationsList>()
        pendingReplies(fresh)
        vm.refresh()
        vm.loadMore()
        assertEquals(2, api.queries.size)
        assertFalse(vm.state.value.loadingMore)
        fresh.complete(page("op_2"))
        vm.loadMore()
        assertEquals(2, api.queries.size)
    }

    @Test fun `pending pagination resumes after refresh with the same cursor without another scroll`() {
        api.lists += page("op_1", next = 1)
        val vm = vm()
        val oldPage = CompletableDeferred<OperationsList>()
        val fresh = CompletableDeferred<OperationsList>()
        val resumed = CompletableDeferred<OperationsList>()
        pendingReplies(oldPage, fresh, resumed)
        vm.loadMore()
        vm.refresh()
        fresh.complete(page("op_1", next = 1))
        assertEquals(4, api.queries.size)
        assertEquals(OperationsQuery(before = at(1)), api.queries.last())
        oldPage.complete(page("op_stale"))
        resumed.complete(page("op_2"))
        assertEquals(listOf("op_1", "op_2"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.loadingMore)
    }

    @Test fun `pending pagination resumes with the retained cursor when refresh fails`() {
        api.lists += page("op_1", next = 1)
        val vm = vm()
        val oldPage = CompletableDeferred<OperationsList>()
        val fresh = CompletableDeferred<OperationsList>()
        val resumed = CompletableDeferred<OperationsList>()
        pendingReplies(oldPage, fresh, resumed)
        vm.loadMore()
        vm.refresh()
        fresh.completeExceptionally(AppError.Offline())
        assertEquals(OperationsQuery(before = at(1)), api.queries.last())
        assertTrue(vm.state.value.loadingMore)
        oldPage.completeExceptionally(AppError.Offline())
        assertFalse(vm.state.value.moreFailed)
        resumed.complete(page("op_2"))
        assertEquals(listOf("op_1", "op_2"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun `pagination requested during refresh is deferred and coalesced until the current cursor is known`() {
        api.lists += page("op_1", next = 1)
        val vm = vm()
        val fresh = CompletableDeferred<OperationsList>()
        val resumed = CompletableDeferred<OperationsList>()
        pendingReplies(fresh, resumed)
        vm.refresh()
        vm.loadMore()
        vm.loadMore()
        assertEquals(2, api.queries.size)
        fresh.complete(page("op_2", next = 2))
        assertEquals(3, api.queries.size)
        assertEquals(OperationsQuery(before = at(2)), api.queries.last())
        resumed.complete(page("op_3"))
        assertEquals(listOf("op_2", "op_3"), vm.state.value.items.map { it.id })
    }

    @Test fun `superseding refresh keeps pagination demand until only the latest response`() {
        api.lists += page("op_1", next = 1)
        val vm = vm()
        val oldPage = CompletableDeferred<OperationsList>()
        val firstRefresh = CompletableDeferred<OperationsList>()
        val latestRefresh = CompletableDeferred<OperationsList>()
        val resumed = CompletableDeferred<OperationsList>()
        pendingReplies(oldPage, firstRefresh, latestRefresh, resumed)
        vm.loadMore()
        vm.refresh()
        // A confirmed server-data change supersedes an old read; repeated gestures are deduplicated.
        vm.refresh(changedContext = true)
        firstRefresh.complete(page("op_stale", next = 2))
        assertEquals(4, api.queries.size)
        assertTrue(vm.state.value.refreshing)
        latestRefresh.complete(page("op_current", next = 1))
        assertEquals(5, api.queries.size)
        assertEquals(OperationsQuery(before = at(1)), api.queries.last())
        oldPage.complete(page("op_old"))
        resumed.complete(page("op_more"))
        assertEquals(listOf("op_current", "op_more"), vm.state.value.items.map { it.id })
        assertEquals(5, api.queries.size)
    }

    @Test fun `record updates can come from an independent read-only source`() {
        api.lists += page("op_1")
        val updates = kotlinx.coroutines.flow.MutableSharedFlow<List<ru.finassist.pf.core.api.model.OperationDetails>>(extraBufferCapacity = 4)
        val records = object : ru.finassist.pf.feature.operations.api.OperationRecords {
            override val records = updates
        }
        val vm = FeedViewModel(api, statements, operations, records, flags, tracker, reportZone, ru.finassist.pf.core.common.time.ElapsedClock { scheduler.currentTime })
        val updated = details("op_1", categoryId = "cat_restaurants")
        updates.tryEmit(listOf(updated))
        assertEquals(updated.toItem(), vm.state.value.items.single())
        assertEquals(1, api.queries.size)
    }

    private fun refusal() = AppError.Api("FUTURE_POLICY", 429,
        response = ru.finassist.pf.core.common.error.ErrorResponseInfo("FUTURE_POLICY", 429, requestRetryable = false))

    private fun waitFor(seconds: Long) = AppError.RateLimited(null, seconds,
        ru.finassist.pf.core.common.error.ErrorResponseInfo("RATE_LIMITED", 429, requestRetryable = true,
            timing = ru.finassist.pf.core.common.time.ResponseTiming(receivedElapsedMillis = scheduler.currentTime)))

    @Test fun `stale first-page refusal cannot restrict a newer successful feed`() {
        api.lists += page("initial")
        val vm = vm()
        val old = CompletableDeferred<OperationsList>()
        val fresh = CompletableDeferred<OperationsList>()
        pendingReplies(old, fresh)
        vm.refresh()
        // A confirmed server-data change supersedes an old read; repeated gestures are deduplicated.
        vm.refresh(changedContext = true)
        fresh.complete(page("current"))
        old.completeExceptionally(refusal())
        assertTrue(vm.state.value.retryReady)
        assertEquals(listOf("current"), vm.state.value.items.map { it.id })
        assertEquals(null, vm.state.value.errorPresentation)
        api.listReply = { page("latest") }
        vm.refresh()
        assertEquals(4, api.queries.size)
        assertEquals(listOf("latest"), vm.state.value.items.map { it.id })
    }

    @Test fun `stale pagination refusal cannot restrict the current request for the same cursor`() {
        api.lists += page("initial", next = 1)
        val vm = vm()
        val old = CompletableDeferred<OperationsList>()
        val fresh = CompletableDeferred<OperationsList>()
        val current = CompletableDeferred<OperationsList>()
        pendingReplies(old, fresh, current)
        vm.loadMore()
        vm.refresh()
        fresh.complete(page("fresh", next = 1))
        assertTrue(vm.state.value.loadingMore)
        old.completeExceptionally(refusal())
        assertTrue(vm.state.value.retryReady)
        assertTrue(vm.state.value.moreRetryReady)
        assertEquals(null, vm.state.value.errorPresentation)
        current.complete(page("current"))
        assertEquals(listOf("fresh", "current"), vm.state.value.items.map { it.id })
    }

    @Test fun `a forbidden cursor never blocks first-page refresh and stays forbidden when that cursor returns`() {
        api.lists += page("initial", next = 1)
        api.lists += refusal()
        api.lists += page("refreshed", next = 1)
        val vm = vm()
        vm.loadMore()
        assertTrue(vm.state.value.moreFailed)
        assertTrue(vm.state.value.retryReady)
        assertFalse(vm.state.value.moreRetryReady)
        vm.refresh()
        assertEquals(3, api.queries.size)
        assertEquals(listOf("refreshed"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.moreRetryReady)
        vm.loadMore()
        assertEquals(3, api.queries.size)
    }

    @Test fun `a different cursor has its own retry permission`() {
        api.lists += page("initial", next = 1)
        api.lists += refusal()
        api.lists += page("refreshed", next = 2)
        api.lists += page("older")
        val vm = vm()
        vm.loadMore()
        vm.refresh()
        assertTrue(vm.state.value.moreRetryReady)
        vm.loadMore()
        assertEquals(OperationsQuery(before = at(2)), api.queries.last())
        assertEquals(listOf("refreshed", "older"), vm.state.value.items.map { it.id })
    }

    @Test fun `first-page refusal cannot erase a cursor wait or select pagination for its own error`() {
        api.lists += page("initial", next = 1)
        api.lists += waitFor(5)
        api.lists += refusal()
        api.lists += page("older")
        val vm = vm()
        vm.loadMore()
        assertFalse(vm.state.value.moreRetryReady)
        assertTrue(vm.state.value.retryReady)
        vm.refresh()
        assertFalse(vm.state.value.retryReady)
        assertFalse(vm.state.value.moreFailed, "the latest error belongs to the first page")
        vm.loadMore()
        assertEquals(3, api.queries.size)
        scheduler.advanceTimeBy(4999)
        scheduler.runCurrent()
        vm.loadMore()
        assertEquals(3, api.queries.size)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertTrue(vm.state.value.moreRetryReady)
        assertFalse(vm.state.value.retryReady)
        vm.loadMore()
        assertEquals(4, api.queries.size)
        assertEquals(OperationsQuery(before = at(1)), api.queries.last())
    }

    @Test fun `an explicit data-context change releases an old cursor restriction`() {
        api.lists += page("initial", next = 1)
        api.lists += refusal()
        api.lists += page("refreshed", next = 1)
        api.lists += page("older")
        val vm = vm()
        vm.loadMore()
        statements.events.tryEmit(StatementsEvent.UploadDeleted("u1"))
        assertTrue(vm.state.value.moreRetryReady)
        vm.loadMore()
        assertEquals(4, api.queries.size)
        assertEquals(listOf("refreshed", "older"), vm.state.value.items.map { it.id })
    }

    @Test fun `pull refresh ignores repeated gestures and keeps rows while pending`() {
        api.lists += page("op_1")
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        api.lists.clear()
        api.lists += page("op_2")
        vm.refresh()
        vm.refresh()
        vm.load()
        assertEquals(2, api.queries.size)
        assertTrue(vm.state.value.refreshing)
        assertEquals(listOf("op_1"), vm.state.value.items.map { it.id })
        gate.complete(Unit)
        assertFalse(vm.state.value.refreshing)
        assertEquals(listOf("op_2"), vm.state.value.items.map { it.id })
    }

}
