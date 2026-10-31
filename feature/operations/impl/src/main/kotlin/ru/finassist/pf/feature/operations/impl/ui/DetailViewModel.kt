package ru.finassist.pf.feature.operations.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.OperationsApi
import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.CategoryKind
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.OperationDetails
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.feature.operations.impl.domain.OperationTimestamp.toWire
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.operations.api.CategoriesRepository
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import ru.finassist.pf.feature.operations.api.OperationChangesPublisher
import javax.inject.Inject

data class DetailUiState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val notFound: Boolean = false,
    val operation: OperationDetails? = null,
    val categories: List<Category> = emptyList(),
    /** Picker open with a draft choice; closing without «Сохранить» drops it (decision gpt-6). */
    val picker: Boolean = false,
    val draftCategoryId: String? = null,
    val saving: Boolean = false,
    val pickerError: String? = null,
    val snackbar: String? = null,
    val errorPresentation: ErrorPresentation? = null,
    val pickerErrorPresentation: ErrorPresentation? = null,
    val requestRetryable: Boolean? = null,
    val retryReady: Boolean = true,
    val pickerRequestRetryable: Boolean? = null,
    val pickerRetryReady: Boolean = true,
) {
    /** Kinds of categories the record accepts: debit → expense, credit → income, own transfer pair → both. */
    val pickerKinds: Set<CategoryKind>
        get() = when (operation?.kind) {
            OperationKind.EXPENSE -> setOf(CategoryKind.EXPENSE)
            OperationKind.INCOME -> setOf(CategoryKind.INCOME)
            else -> setOf(CategoryKind.EXPENSE, CategoryKind.INCOME)
        }
}

/** Operation details + manual category change (api.md 4.2, 4.3). */
@HiltViewModel
class DetailViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val api: OperationsApi,
    private val categories: CategoriesRepository,
    private val operations: OperationChangesPublisher,
    private val tracker: Tracker,
    private val elapsedClock: ElapsedClock,
) : ViewModel() {
    /** After a split/merge the record id changes; kept in the saved state so process death reopens the new one. */
    private var id: String = savedState.get<String>(KEY_ID) ?: savedState.toRoute<OperationsRoutes.Detail>().id
    private var occurredAt: String = savedState.get<String>(KEY_OCCURRED_AT)
        ?: savedState.toRoute<OperationsRoutes.Detail>().occurredAt
    private val _state = MutableStateFlow(DetailUiState())
    private val retryGate = RequestRetryGate(elapsedClock)
    init { viewModelScope.launch { retryGate.readiness.collect { ready -> _state.update { it.copy(retryReady = ready) } } } }
    private val pickerRetryGate = RequestRetryGate(elapsedClock)
    private var pickerGeneration = 0L
    init { viewModelScope.launch { pickerRetryGate.readiness.collect { ready -> _state.update { it.copy(pickerRetryReady = ready) } } } }
    val state: StateFlow<DetailUiState> = _state

    init {
        tracker.track(Events.OPERATIONS_DETAIL_OPENED)
        load()
    }

    fun load() {
        if (!retryGate.isReady()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = it.operation == null, error = false, notFound = false, errorPresentation = null, requestRetryable = null) }
            try {
                val op = api.getOperation(id, occurredAt)
                remember(op)
                operations.updateRecords(listOf(op))
                val cats = runCatching { categories.categories() }.getOrDefault(emptyList())
                _state.update { it.copy(loading = false, operation = op, categories = cats) }
            } catch (e: AppError) {
                retryGate.record(e)
                val notFound = e is AppError.Api && e.response?.context != ErrorContext.RESULT_EVENT && e.code == ErrorCodes.NOT_FOUND
                _state.update { it.copy(loading = false, error = !notFound, notFound = notFound, errorPresentation = e.presentation, requestRetryable = e.requestRetryable) }
            }
        }
    }

    fun openPicker() = _state.update { it.copy(picker = true, draftCategoryId = it.operation?.categoryId, pickerError = null, pickerErrorPresentation = null, pickerRequestRetryable = null) }

    fun closePicker() = _state.update { it.copy(picker = false, draftCategoryId = null, pickerError = null, pickerErrorPresentation = null, pickerRequestRetryable = null) }

    fun choose(categoryId: String) {
        if (categoryId == _state.value.draftCategoryId) return
        pickerGeneration++
        pickerRetryGate.clear()
        _state.update { it.copy(draftCategoryId = categoryId, pickerError = null, pickerErrorPresentation = null, pickerRequestRetryable = null) }
    }

    fun save() {
        val s = _state.value
        val op = s.operation ?: return
        val target = s.draftCategoryId ?: return
        if (s.saving || !pickerRetryGate.isReady()) return
        if (target == op.categoryId) {
            closePicker()
            return
        }
        val generation = pickerGeneration
        viewModelScope.launch {
            _state.update { it.copy(saving = true, pickerError = null, pickerErrorPresentation = null, pickerRequestRetryable = null) }
            try {
                val response = api.changeCategory(op.id, ChangeCategoryRequest(target, op.occurredAt.toWire()))
                // A pair split in two or a debit that became a pair: keep showing the record that carries the choice.
                val shown = response.items.firstOrNull { it.categoryId == target } ?: response.items.firstOrNull() ?: op
                remember(shown)
                operations.updateRecords(response.items)
                val message = when {
                    response.replacedId != null && response.items.size > 1 -> "Перевод разделили на две операции"
                    response.replacedId != null && shown.kind == OperationKind.OWN_TRANSFER -> "Нашли вторую сторону — это перевод между своими счетами"
                    else -> "Категория изменена — аналитика пересчитается"
                }
                tracker.track(Events.OPERATIONS_CATEGORY_CHANGED, mapOf("split" to (response.replacedId != null).toString()))
                operations.notifyCategoryChanged()
                _state.update { it.copy(saving = false, picker = false, draftCategoryId = null, operation = shown, snackbar = message) }
            } catch (e: AppError) {
                // An old PATCH failure cannot restrict the different draft selected while it was pending.
                if (generation != pickerGeneration || _state.value.draftCategoryId != target) {
                    _state.update { it.copy(saving = false) }
                    return@launch
                }
                pickerRetryGate.record(e)
                val text = when {
                    e is AppError.Api && e.response?.context != ErrorContext.RESULT_EVENT && e.code == ErrorCodes.CATEGORY_NOT_ASSIGNABLE -> "Эту категорию нельзя назначить этой операции"
                    e is AppError.Offline -> "Категория не изменилась"
                    else -> "Не получилось сохранить. Попробуйте ещё раз"
                }
                _state.update { it.copy(saving = false, pickerError = text, pickerErrorPresentation = e.presentation, pickerRequestRetryable = e.requestRetryable) }
            }
        }
    }

    private fun remember(op: OperationDetails) {
        id = op.id
        occurredAt = op.occurredAt.toWire()
        savedState[KEY_ID] = id
        savedState[KEY_OCCURRED_AT] = occurredAt
    }

    fun consumeSnackbar() = _state.update { it.copy(snackbar = null) }

    private companion object {
        const val KEY_ID = "detail.current_id"
        const val KEY_OCCURRED_AT = "detail.current_occurred_at"
    }
}
