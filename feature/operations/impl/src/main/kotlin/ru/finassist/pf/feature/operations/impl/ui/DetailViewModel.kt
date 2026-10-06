package ru.finassist.pf.feature.operations.impl.ui

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
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.operations.api.CategoriesRepository
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import ru.finassist.pf.feature.operations.impl.data.OperationsRepositoryImpl
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
    private val operations: OperationsRepositoryImpl,
    private val tracker: Tracker,
) : ViewModel() {
    /** After a split/merge the record id changes; kept in the saved state so process death reopens the new one. */
    private var id: String = savedState.get<String>(KEY_ID) ?: savedState.toRoute<OperationsRoutes.Detail>().id
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state

    init {
        tracker.track(Events.OPERATIONS_DETAIL_OPENED)
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.operation == null, error = false, notFound = false) }
            try {
                val op = api.getOperation(id)
                val cats = runCatching { categories.categories() }.getOrDefault(emptyList())
                _state.update { it.copy(loading = false, operation = op, categories = cats) }
            } catch (e: AppError) {
                val notFound = e is AppError.Api && e.code == ErrorCodes.NOT_FOUND
                _state.update { it.copy(loading = false, error = !notFound, notFound = notFound) }
            }
        }
    }

    fun openPicker() = _state.update { it.copy(picker = true, draftCategoryId = it.operation?.categoryId, pickerError = null) }

    fun closePicker() = _state.update { it.copy(picker = false, draftCategoryId = null, pickerError = null) }

    fun choose(categoryId: String) = _state.update { it.copy(draftCategoryId = categoryId, pickerError = null) }

    fun save() {
        val s = _state.value
        val op = s.operation ?: return
        val target = s.draftCategoryId ?: return
        if (target == op.categoryId) {
            closePicker()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true, pickerError = null) }
            try {
                val response = api.changeCategory(op.id, ChangeCategoryRequest(target))
                // A pair split in two or a debit that became a pair: keep showing the record that carries the choice.
                val shown = response.items.firstOrNull { it.categoryId == target } ?: response.items.firstOrNull() ?: op
                id = shown.id
                savedState[KEY_ID] = shown.id
                val message = when {
                    response.replacedId != null && response.items.size > 1 -> "Перевод разделили на две операции"
                    response.replacedId != null && shown.kind == OperationKind.OWN_TRANSFER -> "Нашли вторую сторону — это перевод между своими счетами"
                    else -> "Категория изменена — аналитика пересчитается"
                }
                tracker.track(Events.OPERATIONS_CATEGORY_CHANGED, mapOf("split" to (response.replacedId != null).toString()))
                operations.notifyCategoryChanged()
                _state.update { it.copy(saving = false, picker = false, draftCategoryId = null, operation = shown, snackbar = message) }
            } catch (e: AppError) {
                val text = when {
                    e is AppError.Api && e.code == ErrorCodes.CATEGORY_NOT_ASSIGNABLE -> "Эту категорию нельзя назначить этой операции"
                    e is AppError.Offline -> "Нет сети — категория не изменилась"
                    else -> "Не получилось сохранить. Попробуйте ещё раз"
                }
                _state.update { it.copy(saving = false, pickerError = text) }
            }
        }
    }

    fun consumeSnackbar() = _state.update { it.copy(snackbar = null) }

    private companion object {
        const val KEY_ID = "detail.current_id"
    }
}
