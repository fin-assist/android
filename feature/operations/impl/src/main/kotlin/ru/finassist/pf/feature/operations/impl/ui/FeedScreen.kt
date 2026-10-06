package ru.finassist.pf.feature.operations.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import ru.finassist.pf.core.api.model.MonthSummary
import ru.finassist.pf.core.api.model.OperationItem
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfEmptyState
import ru.finassist.pf.core.designsystem.components.PfIconButton
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfMonthSummary
import ru.finassist.pf.core.designsystem.components.PfNotice
import ru.finassist.pf.core.designsystem.components.PfTabHeader
import ru.finassist.pf.core.designsystem.components.PfTransactionGroup
import ru.finassist.pf.core.designsystem.components.PfTransactionRow
import ru.finassist.pf.core.designsystem.components.SummaryItem
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.operations.impl.domain.OperationFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun FeedScreen(
    onOpenSearch: () -> Unit,
    onOpenOperation: (id: String) -> Unit,
    onUpload: () -> Unit,
    vm: FeedViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val d = PfTheme.dimens
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, cursor = state.nextBefore, onLoadMore = vm::loadMore)

    Column(Modifier.fillMaxSize()) {
        PfTabHeader("Операции") {
            PfIconButton(PfIcons.SEARCH, contentDescription = "Поиск операций", onClick = onOpenSearch)
        }
        when {
            state.loading -> Unit
            state.offline -> PfEmptyState(PfIcons.ALERT, "Нет сети", "Проверьте интернет и попробуйте ещё раз") {
                PfButton("Повторить", onClick = vm::load, variant = ButtonVariant.PRIMARY)
            }
            state.isEmpty -> PfEmptyState(
                PfIcons.LIST,
                "Пока нет операций",
                if (state.uploadEnabled) "Загрузите выписку Т-Банка — разберём операции по категориям" else null,
            ) {
                if (state.uploadEnabled) PfButton("Загрузить выписку", onClick = onUpload, variant = ButtonVariant.PRIMARY, icon = PfIcons.UPLOAD)
            }
            else -> {
                val groups = remember(state.items) { OperationFormat.groupByDay(state.items, zone, today) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = d.space5, end = d.space5, bottom = d.space6),
                ) {
                    val feedState = state.state
                    if (feedState?.stale == true && feedState.lastOperationAt != null) {
                        item(key = "stale") {
                            PfNotice(
                                "Операции в приложении по ${RussianDates.day(feedState.lastOperationAt!!.toLocalDate(), today.year)} — с тех пор прошло больше двух недель",
                                tone = NoticeTone.WARNING,
                                modifier = Modifier.padding(bottom = d.space3),
                                action = if (state.uploadEnabled) ({ PfLink("Загрузить выписку", onClick = onUpload, inline = true) }) else null,
                            )
                        }
                    }
                    state.summary.firstOrNull()?.let { s -> item(key = "summary") { SummaryCard(s, today) } }
                    groups.forEach { (label, ops) ->
                        item(key = "day-$label-${ops.first().id}") {
                            PfTransactionGroup(label) {
                                ops.forEach { op -> OperationRow(op, onClick = { onOpenOperation(op.id) }) }
                            }
                        }
                    }
                    if (state.loadingMore) {
                        item(key = "more") { Text("Загружаем…", style = PfTheme.type.caption, color = PfTheme.colors.textMuted, modifier = Modifier.padding(vertical = d.space4)) }
                    } else if (state.moreFailed) {
                        item(key = "more-failed") {
                            Box(Modifier.fillMaxWidth().padding(vertical = d.space3), contentAlignment = Alignment.Center) {
                                PfLink("Не загрузилось — повторить", onClick = vm::loadMore)
                            }
                        }
                    } else if (state.nextBefore == null && state.items.isNotEmpty()) {
                        item(key = "end") {
                            Text(
                                "Раньше операций нет",
                                style = PfTheme.type.caption, color = PfTheme.colors.textFaint,
                                modifier = Modifier.padding(vertical = d.space4),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun OperationRow(op: OperationItem, onClick: () -> Unit) {
    val row = remember(op) { OperationFormat.row(op) }
    PfTransactionRow(
        icon = row.icon, title = row.title, subtitle = row.subtitle, amount = row.amount,
        income = row.income, muted = row.muted, note = row.note, onClick = onClick,
    )
}

/** «Сентябрь · по 25 сентября», «Доходы минус расходы», income / expense / share of income (api.md 4.1 summary). */
@Composable
private fun SummaryCard(s: MonthSummary, today: LocalDate) {
    val month = runCatching { YearMonth.parse(s.month) }.getOrNull()
    val name = month?.let { RussianDates.monthNominative(it.month).replaceFirstChar { c -> c.uppercase() } + if (it.year != today.year) " ${it.year}" else "" } ?: s.month
    val title = s.dataTo?.let { "$name · по ${RussianDates.day(it.toLocalDate())}" } ?: name
    val balance = s.income - s.expense
    val share = if (s.income.minor > 0) (balance.minor * 100.0 / s.income.minor).roundToInt() else null
    PfMonthSummary(
        title = title,
        amountLabel = "Доходы минус расходы",
        amount = balance.format(Money.Sign.ALWAYS),
        items = listOfNotNull(
            SummaryItem("Доходы", s.income.format(), positive = true),
            SummaryItem("Расходы", s.expense.format()),
            share?.let { SummaryItem("Доля дохода", "$it%") },
        ),
        modifier = Modifier.padding(bottom = PfTheme.dimens.space2),
    )
}

/**
 * Fires [onLoadMore] when the last visible item is within 5 of the end. Re-arms on every new [cursor], so a
 * portion that brought no rows (a gap of empty months) immediately asks for the next one.
 */
@Composable
internal fun LoadMoreEffect(listState: LazyListState, cursor: Any?, onLoadMore: () -> Unit) {
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(listState, cursor) {
        if (cursor == null) return@LaunchedEffect
        snapshotFlow { nearEnd }.distinctUntilChanged().filter { it }.collect { onLoadMore() }
    }
}
