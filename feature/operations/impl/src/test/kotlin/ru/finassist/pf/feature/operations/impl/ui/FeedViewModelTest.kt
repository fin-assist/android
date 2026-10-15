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
import ru.finassist.pf.feature.operations.impl.data.OperationsRepositoryImpl
import ru.finassist.pf.feature.statements.api.StatementsEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {
    private val api = FakeOperationsApi()
    private val statements = FakeStatements()
    private val operations = OperationsRepositoryImpl()
    private val flags = FakeFlags()
    private val tracker = FakeTracker()

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val reportZone = ru.finassist.pf.core.common.time.ReportZone { java.time.ZoneId.of("Europe/Moscow") }

    private fun vm() = FeedViewModel(api, statements, operations, flags, tracker, reportZone)

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
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.isEmpty) // offline is not «no operations»

        vm.load()
        assertFalse(vm.state.value.offline)
        assertEquals(listOf("op_1"), vm.state.value.items.map { it.id })
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
}
