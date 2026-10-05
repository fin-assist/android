package ru.finassist.pf.feature.analytics.impl.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.charts.CategoryBar
import ru.finassist.pf.core.designsystem.components.DataRow
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.OptionList
import ru.finassist.pf.core.designsystem.components.OptionRow
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.ValueTone
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.navigation.OperationsFilter
import ru.finassist.pf.core.network.codes.Coverage
import ru.finassist.pf.core.network.codes.TransferMode
import ru.finassist.pf.feature.analytics.impl.domain.expenseMoney
import ru.finassist.pf.feature.analytics.impl.domain.incomeMoney
import ru.finassist.pf.feature.analytics.impl.domain.percentText
import ru.finassist.pf.feature.analytics.impl.domain.periodItemLabel
import ru.finassist.pf.feature.analytics.impl.domain.shortPeriod
import ru.finassist.pf.feature.analytics.impl.domain.toNav

/** Sheets of «Аналитика»: period picker, transfers mode, full category lists. */
@Composable
fun AnalyticsSheets(state: AnalyticsViewModel.UiState, vm: AnalyticsViewModel, onSearch: (OperationsFilter) -> Unit) {
    val c = PfTheme.colors
    val data = state.data

    if (state.showPeriodSheet) PfBottomSheet(title = "Период", onDismiss = { vm.onPeriodSheet(false) }) {
        val current = data?.params?.date
        when {
            state.periodsError -> Notice("Не получилось загрузить список периодов", tone = NoticeTone.Warning, action = { PfLink("Повторить", onClick = { vm.onPeriodSheet(true) }, inline = true) })
            state.periods == null -> Text("Загружаем…", style = PfTheme.type.body, color = c.textMuted)
            state.periods.isEmpty() -> Text("Периодов с данными пока нет", style = PfTheme.type.body, color = c.textMuted)
            else -> Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OptionList(label = "Периоды с данными") {
                    state.periods.forEachIndexed { i, p ->
                        OptionRow(
                            title = periodItemLabel(p),
                            description = if (Coverage.fromWire(p.coverage) != Coverage.Complete) "неполный" else null,
                            selected = p.key == current,
                            onClick = { vm.onPeriodPicked(p.key) },
                            divider = i < state.periods.lastIndex,
                        )
                    }
                }
            }
        }
    }

    if (state.showTransfersSheet) PfBottomSheet(title = "Переводы людям", onDismiss = { vm.onTransfersSheet(false) }) {
        OptionList(label = "Режим переводов") {
            OptionRow(title = "С переводами", description = "Переводы людям считаем тратами и доходами", selected = state.transferMode == TransferMode.With, onClick = { vm.onTransferMode(TransferMode.With) })
            OptionRow(title = "Без переводов", description = "Переводы по номеру телефона и на карту — часто не покупки и не зарплата, а долги, подарки или общие траты", selected = state.transferMode == TransferMode.Without, onClick = { vm.onTransferMode(TransferMode.Without) }, divider = false)
        }
        Spacer(Modifier.height(PfSpace.s3))
        Text("Переводы между своими счетами не считаем ни в одном режиме. Выбор действует на всю «Аналитику», цифры ведут в Поиск с тем же правилом", style = PfTheme.type.hint, color = c.textMuted)
    }

    state.allCategories?.let { which ->
        val expense = which == AnalyticsViewModel.AllCategories.Expense
        val list = (if (expense) data?.expenseCategories else data?.incomeCategories)?.items.orEmpty()
        val total = if (expense) data?.tiles?.expense?.value else data?.tiles?.income?.value
        PfBottomSheet(title = if (expense) "Расходы по категориям" else "Доходы по категориям", onDismiss = { vm.onAllCategories(null) }) {
            data?.let { Text(shortPeriod(it).replaceFirstChar { ch -> ch.uppercase() }, style = PfTheme.type.caption, color = c.textMuted) }
            Spacer(Modifier.height(PfSpace.s2))
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                val maxAmount = list.maxOfOrNull { it.amount }?.takeIf { it > 0 } ?: 1L
                list.forEach { item ->
                    if (expense) CategoryBar(
                        icon = item.categoryIcon, label = item.categoryName, amount = expenseMoney(item.amount),
                        percent = if (item.amount > 0) (item.amount * 100 / maxAmount).toInt() else 0, share = percentText(item.share), note = item.note,
                        onClick = { vm.onAllCategories(null); onSearch(item.filters.toNav()) },
                    ) else DataRow(label = item.categoryName, sublabel = item.note, value = incomeMoney(item.amount), tone = ValueTone.Positive, onClick = { vm.onAllCategories(null); onSearch(item.filters.toNav()) })
                }
                DataRow(label = "Всего", value = if (expense) expenseMoney(total) else incomeMoney(total), total = true, tone = if (expense) ValueTone.Default else ValueTone.Positive)
            }
        }
    }
}
