package ru.finassist.pf.feature.operations.impl.ui

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.CategoryKind
import ru.finassist.pf.core.api.model.ChangeCategoryResponse
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.tracking.Events
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Robolectric: the operation id comes from the typed route (`SavedStateHandle.toRoute` needs a `Bundle`). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DetailViewModelTest {
    private val api = FakeOperationsApi()
    private val categories = FakeCategories(listOf(category("cat_supermarkets"), category("cat_restaurants")))
    private val operations = FakeOperationChanges()
    private val tracker = FakeTracker()
    private val savedState = SavedStateHandle(mapOf("id" to "op_1", "occurredAt" to "2026-09-25T12:00:00+03:00"))

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        api.operations["op_1"] = details("op_1")
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = DetailViewModel(savedState, api, categories, operations, tracker, ru.finassist.pf.core.common.time.ElapsedClock { 0L })

    private fun picked(categoryId: String) = vm().apply {
        openPicker()
        choose(categoryId)
    }

    @Test fun `the operation of the route is shown with the category directory`() {
        val s = vm().state.value
        assertEquals(listOf("op_1"), api.opened)
        assertEquals(listOf(api.operations.getValue("op_1")), operations.published.single())
        assertEquals("op_1", s.operation?.id)
        assertEquals(listOf("cat_supermarkets", "cat_restaurants"), s.categories.map { it.id })
        assertFalse(s.loading)
        assertFalse(s.error)
        assertEquals(listOf(Events.OPERATIONS_DETAIL_OPENED.name), tracker.names())
    }

    @Test fun `the operation is shown even when the directory fails`() {
        categories.failure = AppError.Offline()
        val s = vm().state.value
        assertEquals("op_1", s.operation?.id)
        assertEquals(emptyList(), s.categories)
        assertFalse(s.error)
    }

    @Test fun `a missing operation is the not-found state, not an error`() {
        api.getFailure = AppError.Api(ErrorCodes.NOT_FOUND, 404)
        val s = vm().state.value
        assertTrue(s.notFound)
        assertFalse(s.error)
        assertFalse(s.loading)
    }

    @Test fun `any other failure is the retryable error, and retry clears it`() {
        api.getFailure = AppError.Offline()
        val vm = vm()
        assertTrue(vm.state.value.error)
        assertFalse(vm.state.value.notFound)

        api.getFailure = null
        vm.load()
        assertFalse(vm.state.value.error)
        assertEquals("op_1", vm.state.value.operation?.id)
    }

    @Test fun `a failed operation read distinguishes connection failure from server failure`() {
        for (failure in listOf(AppError.Offline(), AppError.Server(500))) {
            api.getFailure = failure
            val vm = vm()
            assertEquals(failure is AppError.Offline, vm.state.value.errorPresentation?.connectionFailure)
            assertTrue(vm.state.value.error)
        }
    }

    @Test fun `the picker offers the categories the operation accepts`() {
        assertEquals(setOf(CategoryKind.EXPENSE), DetailUiState(operation = details("a", OperationKind.EXPENSE)).pickerKinds)
        assertEquals(setOf(CategoryKind.INCOME), DetailUiState(operation = details("a", OperationKind.INCOME)).pickerKinds)
        val both = setOf(CategoryKind.EXPENSE, CategoryKind.INCOME)
        assertEquals(both, DetailUiState(operation = details("a", OperationKind.OWN_TRANSFER)).pickerKinds)
        assertEquals(both, DetailUiState(operation = details("a", OperationKind.UNKNOWN)).pickerKinds)
        assertEquals(both, DetailUiState().pickerKinds)
    }

    @Test fun `the picker opens on the current category and closing it drops the draft`() {
        val vm = vm()
        vm.openPicker()
        assertTrue(vm.state.value.picker)
        assertEquals("cat_supermarkets", vm.state.value.draftCategoryId)
        vm.choose("cat_restaurants")
        assertEquals("cat_restaurants", vm.state.value.draftCategoryId)
        vm.closePicker()
        assertFalse(vm.state.value.picker)
        assertNull(vm.state.value.draftCategoryId)
        assertEquals("cat_supermarkets", vm.state.value.operation?.categoryId)
    }

    @Test fun `saving the current category just closes the picker`() {
        val vm = picked("cat_supermarkets")
        vm.save()
        assertEquals(emptyList(), api.changes)
        assertFalse(vm.state.value.picker)
        assertNull(vm.state.value.snackbar)
    }

    @Test fun `nothing is saved before the operation is loaded`() {
        api.getFailure = AppError.Offline()
        val vm = picked("cat_restaurants")
        vm.save()
        assertEquals(emptyList(), api.changes)
    }

    @Test fun `a new category is saved, reported and announced to the other screens`() = runTest(UnconfinedTestDispatcher()) {
        api.changeResponse = ChangeCategoryResponse(listOf(details("op_1", categoryId = "cat_restaurants")))
        var announced = 0
        val watcher = launch { operations.categoryChanges.collect { announced++ } }
        val vm = picked("cat_restaurants")
        vm.save()
        val s = vm.state.value
        assertEquals(listOf("op_1" to "cat_restaurants"), api.changes)
        assertEquals("cat_restaurants", s.operation?.categoryId)
        assertFalse(s.picker)
        assertFalse(s.saving)
        assertNull(s.draftCategoryId)
        assertEquals("Категория изменена — аналитика пересчитается", s.snackbar)
        assertEquals(Events.OPERATIONS_CATEGORY_CHANGED to mapOf("split" to "false"), tracker.events.last())
        assertEquals(1, announced)
        assertEquals(api.changeResponse!!.items, operations.published.last())
        watcher.cancel()
    }

    @Test fun `a split pair keeps the chosen sides timestamp for reload recreation and another patch`() {
        val debitAt = "2026-09-25T12:00:00+03:00"
        val creditAt = "2026-09-26T15:00:00.123456789+03:00"
        val debit = details("op_1a", categoryId = "cat_own_transfer")
        val credit = details("op_1b", categoryId = "cat_restaurants").copy(
            occurredAt = java.time.OffsetDateTime.parse(creditAt),
        )
        api.operations["op_1"] = details("op_1", OperationKind.OWN_TRANSFER, categoryId = "cat_own_transfer")
        api.operations[credit.id] = credit
        api.changeResponse = ChangeCategoryResponse(items = listOf(debit, credit), replacedId = "op_1")
        val vm = picked("cat_restaurants")
        vm.save()
        assertEquals(debitAt, api.changedAt.single())
        assertEquals("op_1b", vm.state.value.operation?.id)
        assertEquals(credit.occurredAt, vm.state.value.operation?.occurredAt)
        assertEquals("Перевод разделили на две операции", vm.state.value.snackbar)
        assertEquals(mapOf("split" to "true"), tracker.events.last().second)

        vm.load()
        assertEquals("op_1b", api.opened.last())
        assertEquals(creditAt, api.openedAt.last())
        val recreated = DetailViewModel(savedState, api, categories, operations, tracker, ru.finassist.pf.core.common.time.ElapsedClock { 0L })
        assertEquals("op_1b", api.opened.last())
        assertEquals(creditAt, api.openedAt.last())
        api.changeResponse = ChangeCategoryResponse(listOf(credit.copy(categoryId = "cat_supermarkets")))
        recreated.openPicker()
        recreated.choose("cat_supermarkets")
        recreated.save()
        assertEquals("op_1b" to "cat_supermarkets", api.changes.last())
        assertEquals(creditAt, api.changedAt.last())
    }

    @Test fun `a debit that found its pair is announced as an own transfer`() {
        api.changeResponse = ChangeCategoryResponse(
            items = listOf(details("pair_1", OperationKind.OWN_TRANSFER, categoryId = "cat_own_transfer")),
            replacedId = "op_1",
        )
        val vm = picked("cat_restaurants")
        vm.save()
        // No record carries the choice: the first one is shown.
        assertEquals("pair_1", vm.state.value.operation?.id)
        assertEquals("Нашли вторую сторону — это перевод между своими счетами", vm.state.value.snackbar)
    }

    @Test fun `an empty answer keeps the operation on screen`() {
        api.changeResponse = ChangeCategoryResponse(emptyList())
        val vm = picked("cat_restaurants")
        vm.save()
        assertEquals("op_1", vm.state.value.operation?.id)
        assertFalse(vm.state.value.picker)
    }

    @Test fun `a category the operation cannot take keeps the picker open with the reason`() {
        assertSaveError(AppError.Api(ErrorCodes.CATEGORY_NOT_ASSIGNABLE, 422), "Эту категорию нельзя назначить этой операции")
    }

    @Test fun `no network keeps the picker open with the offline reason`() {
        assertSaveError(AppError.Offline(), "Категория не изменилась")
    }

    @Test fun `any other failure keeps the picker open with a retry hint`() {
        assertSaveError(AppError.Server(500), "Не получилось сохранить. Попробуйте ещё раз")
    }

    private fun assertSaveError(error: AppError, text: String) {
        api.changeFailure = error
        val vm = picked("cat_restaurants")
        vm.save()
        val s = vm.state.value
        assertEquals(text, s.pickerError)
        assertEquals(error is AppError.Offline, s.pickerErrorPresentation?.connectionFailure)
        assertTrue(s.picker)
        assertFalse(s.saving)
        assertEquals("cat_restaurants", s.draftCategoryId)
        assertEquals("cat_supermarkets", s.operation?.categoryId)
        assertTrue(tracker.events.none { it.first == Events.OPERATIONS_CATEGORY_CHANGED })
        // Another choice clears the reason.
        vm.choose("cat_supermarkets")
        assertNull(vm.state.value.pickerError)
    }

    private fun forbiddenCategory() = AppError.Api("FUTURE_REFUSAL", 400,
        response = ru.finassist.pf.core.common.error.ErrorResponseInfo("FUTURE_REFUSAL", 400, requestRetryable = false))

    @Test fun `an old category refusal cannot restrict a different draft selected during the PATCH`() {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.changeReply = { release.await(); throw forbiddenCategory() }
        val vm = picked("cat_restaurants")
        vm.save()
        assertTrue(vm.state.value.saving)
        vm.save()
        assertEquals(1, api.changes.size, "busy prevents a duplicate submission")
        vm.choose("cat_taxi")
        release.complete(Unit)
        assertFalse(vm.state.value.saving)
        assertTrue(vm.state.value.pickerRetryReady)
        assertNull(vm.state.value.pickerRequestRetryable)
        assertNull(vm.state.value.pickerError)
        api.changeReply = null
        api.changeResponse = ChangeCategoryResponse(listOf(details("op_1", categoryId = "cat_taxi")))
        vm.save()
        assertEquals(listOf("op_1" to "cat_restaurants", "op_1" to "cat_taxi"), api.changes)
        assertEquals("cat_taxi", vm.state.value.operation?.categoryId)
    }

    @Test fun `an unchanged draft preserves its own refusal until an actual new category is chosen`() {
        api.changeFailure = forbiddenCategory()
        val vm = picked("cat_restaurants")
        vm.save()
        vm.choose("cat_restaurants")
        vm.save()
        assertEquals(1, api.changes.size)
        assertFalse(vm.state.value.pickerRetryReady)
        vm.choose("cat_taxi")
        api.changeFailure = null
        api.changeResponse = ChangeCategoryResponse(listOf(details("op_1", categoryId = "cat_taxi")))
        vm.save()
        assertEquals(2, api.changes.size)
        assertEquals("cat_taxi", vm.state.value.operation?.categoryId)
    }

    @Test fun `the snackbar is shown once`() {
        api.changeResponse = ChangeCategoryResponse(listOf(details("op_1", categoryId = "cat_restaurants")))
        val vm = picked("cat_restaurants")
        vm.save()
        vm.consumeSnackbar()
        assertNull(vm.state.value.snackbar)
    }
    @Test fun `details refreshes the timestamp used by patch and process recreation`() {
        val latest = java.time.OffsetDateTime.parse("2026-10-01T00:00:00.123456789+03:00")
        api.operations["op_1"] = details("op_1").copy(occurredAt = latest)
        val vm = picked("cat_restaurants")
        assertEquals("2026-09-25T12:00:00+03:00", api.openedAt.single())
        api.changeResponse = ChangeCategoryResponse(listOf(details("op_1", categoryId = "cat_restaurants").copy(occurredAt = latest.plusDays(1))))
        vm.save()
        assertEquals("2026-10-01T00:00:00.123456789+03:00", api.changedAt.single())
        DetailViewModel(savedState, api, categories, operations, tracker, ru.finassist.pf.core.common.time.ElapsedClock { 0L })
        assertEquals("2026-10-02T00:00:00.123456789+03:00", api.openedAt.last())
    }

    @Test fun `refresh preserves operation and category draft and never writes category`() {
        val vm = vm()
        vm.openPicker()
        vm.choose("cat_restaurants")
        vm.refresh()
        assertEquals(1, api.opened.size)
        assertEquals("cat_restaurants", vm.state.value.draftCategoryId)
        vm.closePicker()
        val shown = vm.state.value.operation
        api.getFailure = AppError.Offline()
        vm.refresh()
        assertEquals(shown, vm.state.value.operation)
        assertTrue(vm.state.value.errorPresentation?.connectionFailure == true)
        assertFalse(vm.state.value.refreshing)
        assertTrue(api.changes.isEmpty())
    }

    @Test fun `pending refresh deduplicates detail reads and keeps content visible`() = runTest {
        val wait = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val delayed = object : ru.finassist.pf.core.api.OperationsApi by api {
            override suspend fun getOperation(id: String, occurredAt: String): ru.finassist.pf.core.api.model.OperationDetails {
                calls++
                if (calls > 1) wait.await()
                return api.getOperation(id, occurredAt)
            }
        }
        val vm = DetailViewModel(savedState, delayed, categories, operations, tracker, ru.finassist.pf.core.common.time.ElapsedClock { 0L })
        val shown = vm.state.value.operation
        vm.refresh()
        vm.refresh()
        vm.load()
        assertEquals(2, calls)
        assertEquals(shown, vm.state.value.operation)
        assertTrue(vm.state.value.refreshing)
        assertFalse(vm.state.value.loading)
        wait.complete(Unit)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun `detail refresh preserves old data and respects retry after`() {
        val vm = vm()
        val shown = vm.state.value.operation
        api.getFailure = AppError.RateLimited(null, retryAfterSeconds = 60)
        vm.refresh()
        vm.refresh()
        assertEquals(2, api.opened.size)
        assertEquals(shown, vm.state.value.operation)
        assertFalse(vm.state.value.retryReady)
    }

    @Test fun `late refresh cannot undo a successful category mutation or restore a split identity`() = runTest {
        val wait = kotlinx.coroutines.CompletableDeferred<Unit>()
        val old = api.operations.getValue("op_1")
        var calls = 0
        val delayed = object : ru.finassist.pf.core.api.OperationsApi by api {
            override suspend fun getOperation(id: String, occurredAt: String): ru.finassist.pf.core.api.model.OperationDetails {
                calls++
                if (calls == 2) {
                    wait.await()
                    return old
                }
                return api.getOperation(id, occurredAt)
            }
        }
        val vm = DetailViewModel(savedState, delayed, categories, operations, tracker, ru.finassist.pf.core.common.time.ElapsedClock { 0L })
        vm.refresh()
        vm.openPicker()
        vm.choose("cat_restaurants")
        val changed = details("split_debit", categoryId = "cat_restaurants").copy(occurredAt = old.occurredAt.plusDays(1))
        api.operations[changed.id] = changed
        api.changeResponse = ChangeCategoryResponse(listOf(changed), replacedId = old.id)
        vm.save()
        assertEquals(changed, vm.state.value.operation)
        assertFalse(vm.state.value.refreshing)
        val published = operations.published.toList()
        wait.complete(Unit)
        assertEquals(changed, vm.state.value.operation)
        assertEquals(published, operations.published)
        vm.load()
        assertEquals(changed.id, api.opened.last())
        assertEquals(changed.occurredAt, java.time.OffsetDateTime.parse(api.openedAt.last()))
    }

    @Test fun `stale read preserves endpoint wait without restoring error or operation after a successful patch`() = runTest {
        val wait = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val delayed = object : ru.finassist.pf.core.api.OperationsApi by api {
            override suspend fun getOperation(id: String, occurredAt: String): ru.finassist.pf.core.api.model.OperationDetails {
                calls++
                if (calls == 2) {
                    wait.await()
                    throw AppError.RateLimited(null, 60)
                }
                return api.getOperation(id, occurredAt)
            }
        }
        val vm = DetailViewModel(savedState, delayed, categories, operations, tracker, ru.finassist.pf.core.common.time.ElapsedClock { testScheduler.currentTime })
        vm.refresh()
        vm.openPicker()
        vm.choose("cat_restaurants")
        val changed = details("op_1", categoryId = "cat_restaurants")
        api.operations[changed.id] = changed
        api.changeResponse = ChangeCategoryResponse(listOf(changed))
        vm.save()
        vm.refresh()
        val completedCalls = calls
        wait.complete(Unit)
        assertEquals(changed, vm.state.value.operation)
        assertFalse(vm.state.value.refreshing)
        assertNull(vm.state.value.errorPresentation)
        assertFalse(vm.state.value.retryReady)
        vm.refresh()
        assertEquals(completedCalls, calls)
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertTrue(vm.state.value.retryReady)
        vm.refresh()
        assertEquals(completedCalls + 1, calls)
    }

    @Test fun `refresh not found clears a deleted operation and dismisses its category draft`() = runTest {
        val wait = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val delayed = object : ru.finassist.pf.core.api.OperationsApi by api {
            override suspend fun getOperation(id: String, occurredAt: String): ru.finassist.pf.core.api.model.OperationDetails {
                calls++
                if (calls == 2) {
                    wait.await()
                    throw AppError.Api(ErrorCodes.NOT_FOUND, 404)
                }
                return api.getOperation(id, occurredAt)
            }
        }
        val vm = DetailViewModel(savedState, delayed, categories, operations, tracker, ru.finassist.pf.core.common.time.ElapsedClock { 0L })
        vm.refresh()
        vm.openPicker()
        vm.choose("cat_restaurants")
        wait.complete(Unit)
        assertTrue(vm.state.value.notFound)
        assertFalse(vm.state.value.error)
        assertFalse(vm.state.value.picker)
        assertNull(vm.state.value.operation)
        assertNull(vm.state.value.draftCategoryId)
        vm.save()
        vm.refresh()
        assertTrue(api.changes.isEmpty())
        assertEquals(2, calls)
    }

}
