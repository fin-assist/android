package ru.finassist.pf.feature.operations.impl.ui

import ru.finassist.pf.core.designsystem.theme.PfInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.finassist.pf.core.api.model.CategoryKind
import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.countWithNoun
import ru.finassist.pf.core.designsystem.components.LocalSuppressPopups
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfChip
import ru.finassist.pf.core.designsystem.components.PfChipRow
import ru.finassist.pf.core.designsystem.components.PfEmptyState
import ru.finassist.pf.core.designsystem.components.PfIconButton
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfNotice
import ru.finassist.pf.core.designsystem.components.PfOptionRow
import ru.finassist.pf.core.designsystem.components.PfSearchField
import ru.finassist.pf.core.designsystem.components.PfTextField
import ru.finassist.pf.core.designsystem.components.PfTestTags
import ru.finassist.pf.core.designsystem.components.PfTransactionGroup
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.operations.impl.domain.OperationFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenOperation: (String) -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    SearchContent(
        state = state, zone = zone, today = today, onBack = onBack, onOpenOperation = onOpenOperation,
        actions = remember(vm) {
            SearchActions(
                setQuery = vm::setQuery, openSheet = vm::openSheet, toggleKind = vm::toggleKind,
                clearAnalyticsScope = vm::clearAnalyticsScope, reset = vm::reset, retry = vm::retry,
                searchAllTime = vm::searchAllTime, setPeriod = vm::setPeriod, setCategory = vm::setCategory,
                setAmount = vm::setAmount,
            )
        },
    )
}

/** Handlers of [SearchContent]; the screen binds them to [SearchViewModel]. */
@Immutable
internal class SearchActions(
    val setQuery: (String) -> Unit,
    val openSheet: (SearchSheet?) -> Unit,
    val toggleKind: () -> Unit,
    val clearAnalyticsScope: () -> Unit,
    val reset: () -> Unit,
    val retry: () -> Unit,
    val searchAllTime: () -> Unit,
    val setPeriod: (from: java.time.OffsetDateTime?, to: java.time.OffsetDateTime?, allTime: Boolean) -> Unit,
    val setCategory: (String?) -> Unit,
    val setAmount: (Money?, Money?) -> Unit,
)

/** Search drawn from a ready state: no ViewModel, so design-check snapshots render it with mockup data. */
@Composable
internal fun SearchContent(
    state: SearchUiState,
    zone: ZoneId,
    today: LocalDate,
    onBack: () -> Unit,
    onOpenOperation: (String) -> Unit,
    actions: SearchActions,
) {
    val d = PfTheme.dimens
    val f = state.filter

    Column(Modifier.fillMaxSize().windowInsetsPadding(PfInsets.statusBars).imePadding().testTag(OperationsTags.SEARCH)) {
        Row(Modifier.fillMaxWidth().padding(start = d.space2, end = d.space5, top = d.space2), verticalAlignment = Alignment.CenterVertically) {
            PfIconButton(PfIcons.ARROW_LEFT, contentDescription = "Назад", onClick = onBack, modifier = Modifier.testTag(PfTestTags.BACK))
            PfSearchField(f.q.orEmpty(), actions.setQuery, placeholder = "Описание, категория, сумма", modifier = Modifier.weight(1f), testTag = OperationsTags.SEARCH_INPUT)
        }
        PfChipRow(Modifier.padding(horizontal = d.space5, vertical = d.space2)) {
            // A filter switched off by `search.filter.*` but preset by Analytics or a chip is shown as a fixed,
            // non-editable chip (docs/flags.md); «Сбросить» still removes it.
            val period = f.from != null || f.to != null || state.allTime
            if (state.chips.period || period) {
                PfChip(periodLabel(f, state.allTime), onClick = { actions.openSheet(SearchSheet.PERIOD) }, selected = period, dropdown = state.chips.period, enabled = state.chips.period, modifier = Modifier.testTag(OperationsTags.CHIP_PERIOD))
            }
            if (state.chips.category || f.categoryId != null) {
                val name = state.categories.firstOrNull { it.id == f.categoryId }?.name
                PfChip(name ?: "Категория", onClick = { actions.openSheet(SearchSheet.CATEGORY) }, selected = f.categoryId != null, dropdown = state.chips.category, enabled = state.chips.category, modifier = Modifier.testTag(OperationsTags.CHIP_CATEGORY))
            }
            val amount = f.amountFrom != null || f.amountTo != null
            if (state.chips.amount || amount) {
                PfChip(amountLabel(f), onClick = { actions.openSheet(SearchSheet.AMOUNT) }, selected = amount, dropdown = state.chips.amount, enabled = state.chips.amount, modifier = Modifier.testTag(OperationsTags.CHIP_AMOUNT))
            }
            if (state.chips.kind || f.kind != null) {
                PfChip(if (f.kind == OperationKindFilter.INCOME) "Только доходы" else "Только расходы", onClick = actions.toggleKind, selected = f.kind != null, enabled = state.chips.kind, modifier = Modifier.testTag(OperationsTags.CHIP_KIND))
            }
            // Filters without their own chip (api.md: one chip, removed by tap or «Сбросить»).
            if (f.selection != null || f.transferMode != null) {
                PfChip((f.selectionName ?: "Как на «Аналитике»") + "  ✕", onClick = actions.clearAnalyticsScope, selected = true, modifier = Modifier.testTag(OperationsTags.CHIP_SCOPE))
            }
            if (state.allTime || !f.copy(q = null).isEmpty) {
                PfChip("Сбросить", onClick = actions.reset, modifier = Modifier.testTag(OperationsTags.CHIP_RESET))
            }
        }
        if (state.selectionDropped) {
            PfNotice("Выборка с «Аналитики» больше не действует — ищем без неё", tone = NoticeTone.INFO, modifier = Modifier.padding(horizontal = d.space5))
        }
        when (val r = state.result) {
            SearchResult.Initial -> PfEmptyState(PfIcons.SEARCH, "Поиск по операциям", "Например: «самокат», «Супермаркеты», «1520» или последние 4 цифры счёта", modifier = Modifier.testTag(OperationsTags.SEARCH_INITIAL))
            SearchResult.Loading -> Unit
            SearchResult.TooMany -> PfEmptyState(PfIcons.SLIDERS, "Слишком много операций", "Выберите период покороче или добавьте фильтр")
            SearchResult.Offline -> PfEmptyState(PfIcons.ALERT, "Нет сети", "Проверьте интернет и попробуйте ещё раз") {
                PfButton("Повторить", onClick = actions.retry, variant = ButtonVariant.PRIMARY)
            }
            SearchResult.Failed -> PfEmptyState(PfIcons.ALERT, "Не получилось найти", "Попробуйте ещё раз") {
                PfButton("Повторить", onClick = actions.retry, variant = ButtonVariant.PRIMARY)
            }
            is SearchResult.Found -> {
                val months = remember(r.items) { OperationFormat.groupByMonth(r.items, zone) }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = d.space5, end = d.space5, bottom = d.space6)) {
                    item(key = "count") {
                        Text(
                            if (r.totalCount == 0) "Ничего не нашли" else "Найдено ${countWithNoun(r.totalCount, "операция", "операции", "операций")}",
                            style = PfTheme.type.bodyStrong, color = PfTheme.colors.text,
                            modifier = Modifier.padding(vertical = d.space2).testTag(OperationsTags.SEARCH_COUNT),
                        )
                        if (r.range != null && f.from == null && f.to == null && !state.allTime) {
                            Text(
                                "За последние 12 месяцев: ${RussianDates.dayRange(r.range!!.from.toLocalDate(), RussianDates.lastDayOf(r.range!!))}",
                                style = PfTheme.type.caption, color = PfTheme.colors.textMuted,
                            )
                        }
                    }
                    months.forEach { (month, ops) ->
                        item(key = "m-$month") {
                            Text(
                                RussianDates.monthTitle(month),
                                style = PfTheme.type.title2, color = PfTheme.colors.text,
                                modifier = Modifier.padding(top = d.space5),
                            )
                        }
                        OperationFormat.groupByDay(ops, zone, today).forEach { (label, dayOps) ->
                            item(key = "d-$month-$label") {
                                PfTransactionGroup(label) { dayOps.forEach { op -> OperationRow(op, onClick = { onOpenOperation(op.id) }) } }
                            }
                        }
                    }
                    if (r.hasOlderData) {
                        item(key = "older") {
                            Column(Modifier.fillMaxWidth().padding(top = d.space4), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Есть операции раньше — их не проверяли", style = PfTheme.type.caption, color = PfTheme.colors.textMuted)
                                PfLink("Искать за всё время", onClick = actions.searchAllTime, modifier = Modifier.testTag(OperationsTags.SEARCH_ALL_TIME))
                            }
                        }
                    }
                }
            }
        }
    }

    when (state.sheet) {
        SearchSheet.PERIOD -> PeriodSheet(f, state.allTime, onDismiss = { actions.openSheet(null) }, onPick = actions.setPeriod)
        SearchSheet.CATEGORY -> PfBottomSheet("Категория", onDismiss = { actions.openSheet(null) }) {
            if (f.categoryId != null) {
                PfOptionRow("Все категории", selected = false, onClick = { actions.setCategory(null) }, modifier = Modifier.testTag(OperationsTags.CATEGORY_ALL))
            }
            CategoryList(
                categories = state.categories,
                kinds = when (f.kind) {
                    OperationKindFilter.EXPENSE -> setOf(CategoryKind.EXPENSE)
                    OperationKindFilter.INCOME -> setOf(CategoryKind.INCOME)
                    null -> setOf(CategoryKind.EXPENSE, CategoryKind.INCOME)
                },
                selectedId = f.categoryId,
                onSelect = { actions.setCategory(it.id) },
                onlyAssignable = false,
            )
        }
        SearchSheet.AMOUNT -> AmountSheet(f, onDismiss = { actions.openSheet(null) }, onApply = actions.setAmount)
        null -> Unit
    }
}

private fun periodLabel(f: OperationsFilter, allTime: Boolean): String {
    val from = f.from
    val to = f.to
    return when {
        allTime -> "За всё время"
        from != null && to != null -> RussianDates.dayRange(from.toLocalDate(), to.toLocalDate().minusDays(1))
        from != null -> "С ${RussianDates.day(from.toLocalDate())}"
        to != null -> "По ${RussianDates.day(to.toLocalDate().minusDays(1))}"
        else -> "Последние 12 месяцев"
    }
}

private fun amountLabel(f: OperationsFilter): String {
    val from = f.amountFrom?.format()
    val to = f.amountTo?.format()
    return when {
        from != null && to != null -> "$from — $to"
        from != null -> "от $from"
        to != null -> "до $to"
        else -> "Сумма"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSheet(
    f: OperationsFilter,
    allTime: Boolean,
    onDismiss: () -> Unit,
    onPick: (from: java.time.OffsetDateTime?, to: java.time.OffsetDateTime?, allTime: Boolean) -> Unit,
) {
    var pickDates by rememberSaveable { mutableStateOf(false) }
    val custom = f.from != null || f.to != null
    if (!pickDates) {
        PfBottomSheet("Период", onDismiss = onDismiss) {
            PfOptionRow("Последние 12 месяцев", selected = !custom && !allTime, onClick = { onPick(null, null, false) }, modifier = Modifier.testTag(OperationsTags.PERIOD_LAST_12))
            PfOptionRow("За всё время", selected = allTime, onClick = { onPick(null, null, true) }, modifier = Modifier.testTag(OperationsTags.PERIOD_ALL))
            PfOptionRow(
                "Выбрать даты", selected = custom, onClick = { pickDates = true }, description = if (custom) periodLabel(f, false) else null,
                modifier = Modifier.testTag(OperationsTags.PERIOD_CUSTOM),
            )
        }
    } else {
        val zone = ZoneId.systemDefault()
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = f.from?.toLocalDate()?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            initialSelectedEndDateMillis = f.to?.toLocalDate()?.minusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        // A raw Material dialog: not covered by PfDialog's suppression, so hidden here while the app is locked
        // (it would otherwise draw above the unlock overlay and take input). The picker state survives.
        if (!LocalSuppressPopups.current) DatePickerDialog(
            onDismissRequest = { pickDates = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedStartDateMillis != null,
                    onClick = {
                        // Picker millis are UTC midnights of calendar days; the range is [start, end + 1 day) in the local zone.
                        val start = pickerState.selectedStartDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        val end = (pickerState.selectedEndDateMillis ?: pickerState.selectedStartDateMillis)?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        val from = start?.atStartOfDay(zone)?.toOffsetDateTime()
                        val to = end?.plusDays(1)?.atStartOfDay(zone)?.toOffsetDateTime()
                        pickDates = false
                        onPick(from, to, false)
                    },
                ) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { pickDates = false }) { Text("Отмена") } },
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.height(480.dp))
        }
    }
}

@Composable
private fun AmountSheet(f: OperationsFilter, onDismiss: () -> Unit, onApply: (Money?, Money?) -> Unit) {
    var from by rememberSaveable { mutableStateOf(f.amountFrom?.let { (it.minor / 100).toString() }.orEmpty()) }
    var to by rememberSaveable { mutableStateOf(f.amountTo?.let { (it.minor / 100).toString() }.orEmpty()) }
    val fromValue = from.filter(Char::isDigit).toLongOrNull()
    val toValue = to.filter(Char::isDigit).toLongOrNull()
    val invalid = fromValue != null && toValue != null && fromValue > toValue
    PfBottomSheet(
        "Сумма",
        onDismiss = onDismiss,
        footer = {
            PfButton(
                "Показать",
                onClick = { onApply(fromValue?.let { Money.ofRubles(it) }, toValue?.let { Money.ofRubles(it) }) },
                variant = ButtonVariant.PRIMARY,
                block = true,
                enabled = !invalid,
                modifier = Modifier.testTag(OperationsTags.AMOUNT_APPLY),
            )
        },
    ) {
        Row(Modifier.padding(horizontal = PfTheme.dimens.space5), horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space3)) {
            PfTextField(from, { from = it.filter(Char::isDigit).take(9) }, label = "От, ₽", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f), testTag = OperationsTags.AMOUNT_FROM)
            PfTextField(
                to, { to = it.filter(Char::isDigit).take(9) }, label = "До, ₽", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f),
                error = if (invalid) "Меньше, чем «от»" else null,
                testTag = OperationsTags.AMOUNT_TO,
            )
        }
        Spacer(Modifier.height(PfTheme.dimens.space2))
        Box(Modifier.padding(horizontal = PfTheme.dimens.space5)) {
            Text("Сумма операции без знака: и расходы, и доходы", style = PfTheme.type.caption, color = PfTheme.colors.textMuted)
        }
    }
}
