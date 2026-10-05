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
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.network.codes.OperationKind
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.operations.api.CategoryInfo
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import ru.finassist.pf.feature.operations.impl.data.HintsStore
import ru.finassist.pf.feature.operations.impl.data.OperationsRepository
import ru.finassist.pf.feature.operations.impl.domain.Operation
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: OperationsRepository,
    private val hints: HintsStore,
    private val tracker: Tracker,
) : ViewModel() {

    data class UiState(
        val operation: Operation? = null,
        val error: AppError? = null,
        val showHint: Boolean = false,
        val pickerOpen: Boolean = false,
        val categories: List<CategoryInfo> = emptyList(),
        val pickerQuery: String = "",
        val pickerSelected: String? = null,
        val saving: Boolean = false,
        val saveError: AppError? = null,
        /** The record was replaced (pair split / joined): the screen cannot show it any more. */
        val replaced: Boolean = false,
    ) {
        /** Groups for the picker: system first, then expense or income categories by the operation's kind (README «OptionList»). */
        val pickerGroups: List<Pair<String, List<CategoryInfo>>> get() {
            val op = operation ?: return emptyList()
            val kind = if (op.kind == OperationKind.Income) "income" else "expense"
            val q = pickerQuery.trim().lowercase().replace('ё', 'е')
            val visible = categories.filter { it.assignable && (op.isPair || kind in it.kinds) && (q.isEmpty() || it.name.lowercase().replace('ё', 'е').contains(q)) }
            return listOf("Системные" to visible.filter { it.isSystem }, (if (kind == "income") "Доходы" else "Расходы") to visible.filter { !it.isSystem }).filter { it.second.isNotEmpty() }
        }
    }

    private val id = savedState.toRoute<OperationsRoutes.Detail>().id
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        load()
        viewModelScope.launch { hints.dismissed(HINT_CATEGORY).collect { d -> _state.update { it.copy(showHint = !d) } } }
    }

    fun load() = viewModelScope.launch {
        try { _state.update { it.copy(operation = repo.details(id), error = null) } } catch (e: AppError) { _state.update { it.copy(error = e) } }
    }

    fun dismissHint(forever: Boolean) = viewModelScope.launch { if (forever) hints.dismiss(HINT_CATEGORY) else _state.update { it.copy(showHint = false) } }

    fun openPicker() = viewModelScope.launch {
        val cats = runCatching { repo.categoriesOrLoad() }.getOrDefault(emptyList())
        _state.update { it.copy(pickerOpen = true, categories = cats, pickerQuery = "", pickerSelected = it.operation?.categoryId, saveError = null) }
    }

    fun closePicker() = _state.update { it.copy(pickerOpen = false) }
    fun onPickerQuery(q: String) = _state.update { it.copy(pickerQuery = q) }
    fun onPickerSelect(id: String) = _state.update { it.copy(pickerSelected = id) }

    fun save() {
        val s = _state.value
        val target = s.pickerSelected ?: return
        if (target == s.operation?.categoryId) { closePicker(); return }
        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                val r = repo.changeCategory(id, target)
                tracker.track("operations.category.changed")
                val same = r.items.firstOrNull { it.id == id }
                _state.update { it.copy(saving = false, pickerOpen = false, operation = same ?: it.operation, replaced = r.replacedId == id && same == null) }
            } catch (e: AppError) {
                _state.update { it.copy(saving = false, saveError = e) }
            }
        }
    }

    companion object { const val HINT_CATEGORY = "detail_category" }
}
