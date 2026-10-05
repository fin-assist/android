package ru.finassist.pf.feature.operations.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.ChipRow
import ru.finassist.pf.core.designsystem.components.EmptyState
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.OptionList
import ru.finassist.pf.core.designsystem.components.OptionRow
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfChip
import ru.finassist.pf.core.designsystem.components.PfIconButton
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfTextField
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.components.SearchField
import ru.finassist.pf.core.designsystem.components.SearchSummary
import ru.finassist.pf.core.designsystem.components.TransactionGroup
import ru.finassist.pf.core.designsystem.components.TransactionRow
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.operations.impl.domain.Grouping
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Search (mockups Search / SearchInitial / SearchEmpty / SearchOlder / SearchPeriod). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(state: SearchViewModel.UiState, vm: SearchViewModel, onBack: () -> Unit, onDetail: (String) -> Unit) {
    val c = PfTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    val f = state.filter
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(start = PfSpace.s3, end = ScreenPadding, top = PfSpace.s2), verticalAlignment = Alignment.CenterVertically) {
            PfIconButton("arrow-left", contentDescription = "Назад", onClick = onBack)
            SearchField(value = f.q.orEmpty(), onValueChange = vm::onQuery, placeholder = "Магазин, категория или сумма", onSearch = vm::search, autoFocus = f.q.isNullOrEmpty(), modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(PfSpace.s3))
        ChipRow(Modifier.padding(horizontal = ScreenPadding)) {
            if (state.presetName != null) PfChip(state.presetName, onClick = {}, selected = true)
            if (state.showPeriod) PfChip(
                when (state.periodChoice) {
                    SearchViewModel.PeriodChoice.Last12 -> "Последние 12 месяцев"
                    SearchViewModel.PeriodChoice.AllTime -> "За всё время"
                    SearchViewModel.PeriodChoice.Custom -> customPeriodLabel(f.from, f.to, zone)
                },
                onClick = { vm.onSheet(SearchViewModel.Sheet.Period) }, dropdown = true, selected = state.periodChoice != SearchViewModel.PeriodChoice.Last12,
            )
            if (state.showCategory) PfChip(state.categories.firstOrNull { it.id == f.categoryId }?.name ?: "Категория", onClick = { vm.onSheet(SearchViewModel.Sheet.Category) }, dropdown = true, selected = f.categoryId != null)
            if (state.showAmount) PfChip(amountLabel(f.amountFrom, f.amountTo), onClick = { vm.onSheet(SearchViewModel.Sheet.Amount) }, dropdown = true, selected = f.amountFrom != null || f.amountTo != null)
            if (state.showKind) PfChip(if (f.kind == "income") "Только доходы" else "Только расходы", onClick = vm::onToggleExpensesOnly, selected = f.kind != null)
            if (state.hasFilters) PfChip("Сбросить", onClick = vm::onReset, action = true)
        }
        Spacer(Modifier.height(PfSpace.s4))
        val r = state.result
        when {
            state.error != null -> Column(Modifier.padding(horizontal = ScreenPadding)) {
                Notice(
                    when (state.error) {
                        is AppError.TooManyResults -> "Слишком много операций — сузьте период"
                        is AppError.SelectionNotFound -> "Эта выборка больше недоступна — поиск без неё"
                        is AppError.Offline -> "Нет сети. Проверьте интернет и повторите"
                        else -> "Не получилось найти операции"
                    },
                    tone = NoticeTone.Warning, alert = true, action = { PfLink("Повторить", onClick = { if (state.error is AppError.SelectionNotFound) vm.onReset() else vm.search() }) },
                )
            }
            !state.started -> Text("Введите магазин, категорию, сумму или последние 4 цифры счёта", style = PfTheme.type.body, color = c.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(ScreenPadding))
            r == null -> Text(if (state.loading) "Ищем…" else "", style = PfTheme.type.caption, color = c.textMuted, modifier = Modifier.padding(horizontal = ScreenPadding))
            r.items.isEmpty() -> Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState("search", "По запросу ничего не найдено")
                if (state.hasFilters) PfButton("Сбросить фильтры", onClick = vm::onReset, variant = ButtonVariant.Secondary)
                if (r.hasOlderData) OlderLink(r.rangeFrom, zone, vm::onAllTime)
            }
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = ScreenPadding)) {
                item { SearchSummary(RussianDates.plural(r.totalCount.toLong(), "Найдена", "Найдено", "Найдено").substringAfter(' ') + " " + RussianDates.plural(r.totalCount.toLong(), "операция", "операции", "операций")) }
                Grouping.byMonth(r.items, zone).forEach { g ->
                    item(key = "m-" + g.label) {
                        TransactionGroup(label = g.label) {
                            g.items.forEach { op ->
                                val day = op.occurredAt.atZoneSameInstant(zone).toLocalDate()
                                TransactionRow(icon = op.categoryIcon, title = op.title, subtitle = RussianDates.dayMonth(day), amount = op.amountText, income = op.isIncome, muted = op.isPair, note = op.note, onClick = { onDetail(op.id) })
                            }
                        }
                    }
                }
                if (r.hasOlderData) item { OlderLink(r.rangeFrom, zone, vm::onAllTime) }
                item { Spacer(Modifier.height(PfSpace.s6)) }
            }
        }
    }

    when (state.sheet) {
        SearchViewModel.Sheet.Period -> PeriodSheet(state, vm)
        SearchViewModel.Sheet.Category -> PfBottomSheet(title = "Категория", onDismiss = { vm.onSheet(SearchViewModel.Sheet.None) }) {
            LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
                item { OptionRow("Любая категория", selected = f.categoryId == null, onClick = { vm.onCategory(null) }) }
                items(state.categories.size) { i -> val cat = state.categories[i]; OptionRow(cat.name, selected = f.categoryId == cat.id, onClick = { vm.onCategory(cat.id) }, icon = cat.icon, description = cat.note) }
            }
        }
        SearchViewModel.Sheet.Amount -> AmountSheet(f.amountFrom, f.amountTo, onDismiss = { vm.onSheet(SearchViewModel.Sheet.None) }, onApply = vm::onAmount)
        SearchViewModel.Sheet.None -> Unit
    }
}

@Composable
private fun OlderLink(rangeFrom: java.time.OffsetDateTime?, zone: ZoneId, onAllTime: () -> Unit) {
    val c = PfTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = PfSpace.s4), horizontalAlignment = Alignment.CenterHorizontally) {
        val from = rangeFrom?.atZoneSameInstant(zone)?.toLocalDate()
        Text("Искали за последние 12 месяцев" + (from?.let { " — с ${RussianDates.monthGenitive[it.monthValue - 1]} ${it.year}" } ?: "") + ". Есть операции и раньше", style = PfTheme.type.hint, color = c.textMuted, textAlign = TextAlign.Center)
        PfLink("Искать за всё время", onClick = onAllTime)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSheet(state: SearchViewModel.UiState, vm: SearchViewModel) {
    var pickDates by remember { mutableStateOf(false) }
    if (pickDates) {
        val picker = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { pickDates = false },
            confirmButton = {
                TextButton(onClick = {
                    val start = picker.selectedStartDateMillis; val end = picker.selectedEndDateMillis
                    if (start != null && end != null) {
                        val zone = ZoneId.systemDefault()
                        val from = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toOffsetDateTime()
                        val to = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(zone).toOffsetDateTime()
                        vm.onPeriod(SearchViewModel.PeriodChoice.Custom, from.toString(), to.toString())
                    }
                    pickDates = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { pickDates = false }) { Text("Отмена") } },
        ) { DateRangePicker(state = picker, title = { Text("Период", modifier = Modifier.padding(PfSpace.s4)) }) }
        return
    }
    PfBottomSheet(title = "Период", onDismiss = { vm.onSheet(SearchViewModel.Sheet.None) }) {
        OptionList(label = "Период") {
            OptionRow("Последние 12 месяцев", selected = state.periodChoice == SearchViewModel.PeriodChoice.Last12, onClick = { vm.onPeriod(SearchViewModel.PeriodChoice.Last12) })
            OptionRow("За всё время", selected = state.periodChoice == SearchViewModel.PeriodChoice.AllTime, onClick = { vm.onPeriod(SearchViewModel.PeriodChoice.AllTime) }, description = "Все загруженные операции")
            OptionRow("Выбрать даты", selected = state.periodChoice == SearchViewModel.PeriodChoice.Custom, onClick = { pickDates = true }, divider = false)
        }
    }
}

@Composable
private fun AmountSheet(from: Long?, to: Long?, onDismiss: () -> Unit, onApply: (Long?, Long?) -> Unit) {
    var fromText by remember { mutableStateOf(from?.let { (it / 100).toString() } ?: "") }
    var toText by remember { mutableStateOf(to?.let { (it / 100).toString() } ?: "") }
    PfBottomSheet(title = "Сумма", onDismiss = onDismiss, footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
            PfButton("Сбросить", onClick = { onApply(null, null) }, variant = ButtonVariant.Ghost, modifier = Modifier.weight(1f))
            PfButton("Применить", onClick = { onApply(fromText.toLongOrNull()?.times(100), toText.toLongOrNull()?.times(100)) }, variant = ButtonVariant.Primary, modifier = Modifier.weight(1f))
        }
    }) {
        Spacer(Modifier.height(PfSpace.s2))
        PfTextField(value = fromText, onValueChange = { fromText = it.filter(Char::isDigit) }, label = "От, ₽", keyboardType = KeyboardType.Number)
        Spacer(Modifier.height(PfSpace.s3))
        PfTextField(value = toText, onValueChange = { toText = it.filter(Char::isDigit) }, label = "До, ₽", keyboardType = KeyboardType.Number)
    }
}

/** «Найдено 9 операций» / «Найдена 1 операция». */
private fun foundLabel(n: Int): String {
    val verb = if (n % 10 == 1 && n % 100 != 11) "Найдена" else "Найдено"
    return "$verb " + RussianDates.plural(n.toLong(), "операция", "операции", "операций")
}

private fun amountLabel(from: Long?, to: Long?): String = when {
    from != null && to != null -> "${from / 100}–${to / 100} ₽"
    from != null -> "от ${from / 100} ₽"
    to != null -> "до ${to / 100} ₽"
    else -> "Сумма"
}

private fun customPeriodLabel(from: String?, to: String?, zone: ZoneId): String {
    val f = from?.let { runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDate() }.getOrNull() }
    val t = to?.let { runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDate() }.getOrNull() }
    return when {
        f != null && t != null -> RussianDates.dayRange(f, t)
        f != null -> "с ${RussianDates.dayMonth(f)}"
        t != null -> "по ${RussianDates.dayMonth(t.minusDays(1))}"
        else -> "Период"
    }
}
