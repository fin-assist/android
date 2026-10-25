package ru.finassist.pf.feature.operations.impl.ui

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.OperationsList
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.navigation.PfNavTypes
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.toggles.StaticFlags
import ru.finassist.pf.core.tracking.Events
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Robolectric: the preset filter comes from the typed route as URL-encoded JSON (`JsonNavType` uses `Uri`).
 * The main dispatcher runs on [scheduler], so the 350 ms debounce of the query is driven by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val api = FakeOperationsApi()
    private val categories = FakeCategories(listOf(category("cat_taxi")))
    private val operations = FakeOperationChanges()
    private val tracker = FakeTracker()
    private val reportZone = ru.finassist.pf.core.common.time.ReportZone { java.time.ZoneId.of("Europe/Moscow") }

    private val window = DateRange(OffsetDateTime.parse("2025-10-01T00:00:00+03:00"), OffsetDateTime.parse("2026-10-01T00:00:00+03:00"))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(preset: OperationsFilter? = null, flags: StaticFlags = StaticFlags()): SearchViewModel {
        val args = preset?.let { mapOf("filter" to PfNavTypes.operationsFilter.serializeAsValue(it)) }.orEmpty()
        return SearchViewModel(SavedStateHandle(args), api, categories, operations, operations, flags, tracker, reportZone, ru.finassist.pf.core.common.time.ElapsedClock { scheduler.currentTime })
    }

    private fun found(vararg ids: String, total: Int? = null, range: DateRange? = window, older: Boolean? = null) =
        OperationsList(items = ids.map { item(it) }, range = range, totalCount = total, hasOlderData = older)

    private fun typed(vm: SearchViewModel, q: String) {
        vm.setQuery(q)
        scheduler.advanceTimeBy(350)
        scheduler.runCurrent()
    }

    @Test fun `a blank search waits for input and asks nothing`() {
        val vm = vm()
        assertEquals(SearchResult.Initial, vm.state.value.result)
        assertEquals(emptyList(), api.queries)
        assertEquals(listOf("cat_taxi"), vm.state.value.categories.map { it.id })
    }

    @Test fun `the filter chips follow the search flags`() {
        val flags = StaticFlags(mapOf(Flag.SEARCH_FILTER_PERIOD to false, Flag.SEARCH_FILTER_AMOUNT to false))
        assertEquals(SearchChips(period = false, category = true, amount = false, kind = true), vm(flags = flags).state.value.chips)
    }

    @Test fun `a failing directory leaves the category list empty`() {
        categories.failure = AppError.Offline()
        assertEquals(emptyList(), vm().state.value.categories)
    }

    @Test fun `a preset from analytics is searched right away`() {
        api.lists += found("op_1", "op_2", total = 7, older = true)
        val preset = OperationsFilter(categoryId = "cat_taxi", transferMode = TransferMode.WITHOUT)
        val vm = vm(preset)
        assertEquals(listOf(OperationsQuery(filter = preset)), api.queries)
        val result = assertIs<SearchResult.Found>(vm.state.value.result)
        assertEquals(listOf("op_1", "op_2"), result.items.map { it.id })
        assertEquals(7, result.totalCount)
        assertTrue(result.hasOlderData)
        assertEquals(window, result.range)
        assertEquals(Events.OPERATIONS_SEARCH_SUBMITTED to mapOf("count" to "7"), tracker.events.single())
    }

    @Test fun `without a total the count is the number of rows`() {
        api.lists += found("op_1", "op_2")
        val result = assertIs<SearchResult.Found>(vm(OperationsFilter(q = "кофе")).state.value.result)
        assertEquals(2, result.totalCount)
        assertFalse(result.hasOlderData)
        assertEquals(mapOf("count" to "2"), tracker.events.single().second)
    }

    @Test fun `typing is debounced and only the last query is sent`() {
        api.lists += found("op_1")
        val vm = vm()
        vm.setQuery("ко")
        scheduler.advanceTimeBy(200)
        vm.setQuery("кофе")
        scheduler.advanceTimeBy(349)
        scheduler.runCurrent()
        assertEquals(emptyList(), api.queries)
        assertEquals("кофе", vm.state.value.filter.q)

        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertEquals(listOf(OperationsQuery(filter = OperationsFilter(q = "кофе"))), api.queries)
        assertIs<SearchResult.Found>(vm.state.value.result)
    }

    @Test fun `clearing the query returns to the hint without a request`() {
        api.lists += found("op_1")
        val vm = vm()
        typed(vm, "кофе")
        typed(vm, "")
        assertNull(vm.state.value.filter.q)
        assertEquals(SearchResult.Initial, vm.state.value.result)
        assertEquals(1, api.queries.size)
    }

    @Test fun `the default window is remembered from a search without a period`() {
        api.lists += found("op_1")
        val vm = vm()
        typed(vm, "кофе")
        assertEquals(LocalDate.of(2025, 10, 1), vm.state.value.windowFrom)

        // A search with a period does not describe the default window: the remembered one stays.
        api.lists.clear()
        api.lists += found("op_1", range = DateRange(OffsetDateTime.parse("2026-01-01T00:00:00+03:00"), OffsetDateTime.parse("2026-02-01T00:00:00+03:00")))
        vm.setPeriod(OffsetDateTime.parse("2026-01-01T00:00:00+03:00"), OffsetDateTime.parse("2026-02-01T00:00:00+03:00"), allTime = false)
        assertEquals(LocalDate.of(2025, 10, 1), vm.state.value.windowFrom)
    }

    @Test fun `too many results, no network and a failure are separate states, and retry repeats the search`() {
        api.lists += AppError.Api(ErrorCodes.TOO_MANY_RESULTS, 422)
        api.lists += AppError.Offline()
        api.lists += AppError.Server(500)
        api.lists += found("op_1")
        val vm = vm(OperationsFilter(q = "кофе"))
        assertEquals(SearchResult.TooMany, vm.state.value.result)
        vm.retry()
        assertEquals(SearchResult.Offline, vm.state.value.result)
        vm.retry()
        assertEquals(SearchResult.Failed, vm.state.value.result)
        vm.retry()
        assertIs<SearchResult.Found>(vm.state.value.result)
        assertEquals(4, api.queries.size)
        assertEquals(1, tracker.events.size) // only a completed search is reported
    }

    @Test fun `a stale analytics selection is dropped and the search repeated without it`() {
        api.lists += AppError.Api(ErrorCodes.SELECTION_NOT_FOUND, 404)
        api.lists += found("op_1")
        val vm = vm(OperationsFilter(q = "кофе", selection = "sel_1", selectionName = "Такси"))
        val s = vm.state.value
        assertTrue(s.selectionDropped)
        assertNull(s.filter.selection)
        assertNull(s.filter.selectionName)
        assertEquals(OperationsFilter(q = "кофе"), api.queries.last().filter)
        assertIs<SearchResult.Found>(s.result)
    }

    @Test fun `«Искать за всё время» drops the period and asks for all time`() {
        api.lists += found("op_1")
        val vm = vm(OperationsFilter(q = "кофе", from = window.from, to = window.to))
        vm.searchAllTime()
        val s = vm.state.value
        assertTrue(s.allTime)
        assertNull(s.filter.from)
        assertEquals(OperationsQuery(filter = OperationsFilter(q = "кофе"), allTime = true), api.queries.last())
        assertTrue(s.hasFilters)
    }

    @Test fun `a period closes the sheet, and all time holds only without dates`() {
        api.lists += found("op_1")
        val vm = vm()
        vm.openSheet(SearchSheet.PERIOD)
        assertEquals(SearchSheet.PERIOD, vm.state.value.sheet)
        vm.setPeriod(window.from, window.to, allTime = true)
        assertNull(vm.state.value.sheet)
        assertFalse(vm.state.value.allTime)
        assertEquals(OperationsQuery(filter = OperationsFilter(from = window.from, to = window.to)), api.queries.last())

        vm.setPeriod(null, null, allTime = true)
        assertTrue(vm.state.value.allTime)
        assertEquals(OperationsQuery(allTime = true), api.queries.last())
    }

    @Test fun `category and amount close their sheets and search at once`() {
        api.lists += found("op_1")
        val vm = vm()
        vm.openSheet(SearchSheet.CATEGORY)
        vm.setCategory("cat_taxi")
        assertNull(vm.state.value.sheet)
        assertEquals(OperationsFilter(categoryId = "cat_taxi"), api.queries.last().filter)

        vm.openSheet(SearchSheet.AMOUNT)
        vm.setAmount(Money(100_00), Money(500_00))
        assertNull(vm.state.value.sheet)
        assertEquals(OperationsFilter(categoryId = "cat_taxi", amountFrom = Money(100_00), amountTo = Money(500_00)), api.queries.last().filter)
    }

    @Test fun `the expenses chip toggles, and also clears a preset income filter`() {
        api.lists += found("op_1")
        val vm = vm()
        vm.toggleKind()
        assertEquals(OperationKindFilter.EXPENSE, vm.state.value.filter.kind)
        vm.toggleKind()
        assertNull(vm.state.value.filter.kind)
        assertEquals(SearchResult.Initial, vm.state.value.result)

        val income = vm(OperationsFilter(kind = OperationKindFilter.INCOME))
        income.toggleKind()
        assertNull(income.state.value.filter.kind)
    }

    @Test fun `removing the analytics chip drops the transfer mode and the selection`() {
        api.lists += found("op_1")
        val vm = vm(OperationsFilter(categoryId = "cat_taxi", transferMode = TransferMode.WITHOUT, selection = "sel_1", selectionName = "Такси"))
        vm.clearAnalyticsScope()
        assertEquals(OperationsFilter(categoryId = "cat_taxi"), vm.state.value.filter)
        assertEquals(OperationsFilter(categoryId = "cat_taxi"), api.queries.last().filter)
    }

    @Test fun `reset keeps the query next to other filters`() {
        api.lists += AppError.Api(ErrorCodes.SELECTION_NOT_FOUND, 404)
        api.lists += found("op_1")
        val vm = vm(OperationsFilter(q = "кофе", categoryId = "cat_taxi", selection = "sel_1"))
        vm.searchAllTime()
        vm.reset()
        val s = vm.state.value
        assertEquals(OperationsFilter(q = "кофе"), s.filter)
        assertFalse(s.allTime)
        assertFalse(s.selectionDropped)
        assertEquals(OperationsQuery(filter = OperationsFilter(q = "кофе")), api.queries.last())
    }

    @Test fun `reset with only a query clears it and returns to the hint`() {
        api.lists += found()
        val vm = vm(OperationsFilter(q = "Самокатт"))
        vm.reset()
        assertEquals(OperationsFilter(), vm.state.value.filter)
        assertEquals(SearchResult.Initial, vm.state.value.result)
        assertEquals(1, api.queries.size)
    }

    @Test fun `a category changed on the details screen refreshes the visible result`() {
        api.lists += found("op_1")
        vm(OperationsFilter(categoryId = "cat_taxi"))
        operations.notifyCategoryChanged()
        assertEquals(2, api.queries.size)
    }

    @Test fun `a new profile zone asks the visible result again, the first known zone does not (FIN-92)`() {
        api.lists += found("op_1")
        vm(OperationsFilter(categoryId = "cat_taxi"))
        reportZone.set("Europe/Moscow")
        assertEquals(1, api.queries.size)
        reportZone.set("Asia/Vladivostok")
        assertEquals(2, api.queries.size)
    }

    @Test fun `a new zone keeps the shown result while asking again (FIN-92 review)`() {
        api.lists += found("op_1")
        api.lists += found("op_1", "op_2")
        val vm = vm(OperationsFilter(categoryId = "cat_taxi"))
        val shown = vm.state.value.result
        api.gate = kotlinx.coroutines.CompletableDeferred()
        reportZone.set("Asia/Vladivostok")
        assertEquals(shown, vm.state.value.result, "no spinner over the shown result")
        api.gate!!.complete(Unit)
        assertEquals(2, (vm.state.value.result as SearchResult.Found).items.size)
    }

    @Test fun `a new zone during a pending search for a changed filter does not keep the old result (FIN-92 review)`() {
        api.lists += found("op_1")
        val vm = vm(OperationsFilter(categoryId = "cat_taxi"))
        vm.setQuery("такси")
        api.gate = kotlinx.coroutines.CompletableDeferred()
        reportZone.set("Asia/Vladivostok")
        // Asked by the new filter, and the old filter's result is not shown meanwhile.
        assertEquals("такси", api.queries.last().filter.q)
        assertEquals(SearchResult.Loading, vm.state.value.result)
        api.gate!!.complete(Unit)
    }

    @Test fun `an explicit period does not depend on the zone and is not asked again (FIN-92 review)`() {
        api.lists += found("op_1")
        val period = vm(OperationsFilter(categoryId = "cat_taxi", from = window.from, to = window.to))
        val asked = api.queries.size
        reportZone.set("Europe/Kaliningrad")
        assertEquals(asked, api.queries.size)
        assertEquals(SearchResult.Found::class, period.state.value.result::class)
    }

    @Test fun `a category change on a blank search asks nothing`() {
        vm()
        operations.notifyCategoryChanged()
        assertEquals(emptyList(), api.queries)
    }

    @Test fun `a kind this client does not know is searched as is and goes with the analytics chip (FIN-83)`() {
        val unknown = ru.finassist.pf.core.api.model.OperationKindFilter("expense_v2")
        val preset = OperationsFilter(kind = unknown, transferMode = TransferMode.WITH)
        api.lists += found("op_1")
        api.lists += found("op_1")
        val vm = vm(preset)
        assertEquals(listOf(OperationsQuery(filter = preset)), api.queries, "not dropped: the search is not widened")

        vm.clearAnalyticsScope()
        assertEquals(OperationsFilter(), vm.state.value.filter, "the chip takes the unknown kind with it")
    }

    @Test fun `«Только расходы» replaces a kind this client does not know (FIN-83)`() {
        val preset = OperationsFilter(kind = ru.finassist.pf.core.api.model.OperationKindFilter("expense_v2"))
        api.lists += found("op_1")
        api.lists += found("op_1")
        val vm = vm(preset)
        vm.toggleKind()
        assertEquals(ru.finassist.pf.core.api.model.OperationKindFilter.EXPENSE, api.queries.last().filter.kind)
    }
    @Test fun `details timestamp stays in search when its canonical refresh fails`() {
        api.lists += found("op_1")
        api.lists += AppError.Offline()
        val vm = vm(OperationsFilter(q = "Пятёрочка"))
        val updated = details("op_1").copy(occurredAt = at(25).plusMonths(1))
        operations.updateRecords(listOf(updated))
        assertEquals(updated.occurredAt, assertIs<SearchResult.Found>(vm.state.value.result).items.single().occurredAt)
        assertEquals(2, api.queries.size)
    }

    @Test fun `a timestamp outside search period triggers canonical filtering`() {
        api.lists += found("op_1")
        api.lists += found()
        val vm = vm(OperationsFilter(from = window.from, to = window.to))
        operations.updateRecords(listOf(details("op_1").copy(occurredAt = at(25).plusMonths(1))))
        assertTrue(assertIs<SearchResult.Found>(vm.state.value.result).items.isEmpty())
    }

}
