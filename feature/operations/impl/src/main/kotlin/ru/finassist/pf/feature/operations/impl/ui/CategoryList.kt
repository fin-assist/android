package ru.finassist.pf.feature.operations.impl.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.CategoryKind
import ru.finassist.pf.core.designsystem.components.PfOptionGroupTitle
import ru.finassist.pf.core.designsystem.components.PfOptionRow
import ru.finassist.pf.core.designsystem.components.PfSearchField
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Category groups of the picker: «Системные» on top, then «Расходы» / «Доходы» (api.md 5.1). */
data class CategoryGroups(val system: List<Category>, val expense: List<Category>, val income: List<Category>)

object CategoryGrouping {
    /**
     * [kinds] — which kinds the record accepts (`expense` for a debit, `income` for a credit, both for an own
     * transfer pair or for the search filter). [onlyAssignable] — manual change shows only assignable ones.
     */
    fun group(all: List<Category>, kinds: Set<CategoryKind>, onlyAssignable: Boolean, query: String): CategoryGroups {
        val q = query.trim().lowercase().replace('ё', 'е')
        val visible = all.filter { c ->
            (!onlyAssignable || c.assignable) &&
                c.kinds.any { it in kinds } &&
                (q.isEmpty() || c.name.lowercase().replace('ё', 'е').contains(q))
        }
        return CategoryGroups(
            system = visible.filter { it.isSystem },
            expense = visible.filter { !it.isSystem && CategoryKind.EXPENSE in it.kinds && CategoryKind.EXPENSE in kinds },
            // A category for both kinds is listed once — under «Расходы» when expenses are shown at all.
            income = visible.filter {
                !it.isSystem && CategoryKind.INCOME in it.kinds && CategoryKind.INCOME in kinds &&
                    !(CategoryKind.EXPENSE in it.kinds && CategoryKind.EXPENSE in kinds)
            },
        )
    }
}

/** Searchable single-choice category list for sheets. */
@Composable
fun CategoryList(
    categories: List<Category>,
    kinds: Set<CategoryKind>,
    selectedId: String?,
    onSelect: (Category) -> Unit,
    onlyAssignable: Boolean,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val groups = remember(categories, kinds, query) { CategoryGrouping.group(categories, kinds, onlyAssignable, query) }
    Column(modifier) {
        PfSearchField(query, { query = it }, placeholder = "Найти категорию", modifier = Modifier.padding(horizontal = PfTheme.dimens.space5, vertical = PfTheme.dimens.space2))
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            fun section(title: String, list: List<Category>) {
                if (list.isEmpty()) return
                item(key = "t-$title") { PfOptionGroupTitle(title, Modifier.padding(horizontal = PfTheme.dimens.space4, vertical = PfTheme.dimens.space2)) }
                items(list, key = { "$title-${it.id}" }) { c ->
                    PfOptionRow(c.name, selected = c.id == selectedId, onClick = { onSelect(c) }, icon = c.icon, description = c.note)
                }
            }
            section("Системные", groups.system)
            section("Расходы", groups.expense)
            section("Доходы", groups.income)
            if (groups.system.isEmpty() && groups.expense.isEmpty() && groups.income.isEmpty()) {
                item { Text("Ничего не нашли", style = PfTheme.type.body, color = PfTheme.colors.textMuted, modifier = Modifier.padding(PfTheme.dimens.space5)) }
            }
        }
    }
}
