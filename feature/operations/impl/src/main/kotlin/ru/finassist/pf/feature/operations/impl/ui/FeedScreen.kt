package ru.finassist.pf.feature.operations.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.money.MoneyFormat
import ru.finassist.pf.core.common.money.SignStyle
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.EmptyState
import ru.finassist.pf.core.designsystem.components.MonthSummary
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfIconButton
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.components.SummaryItem
import ru.finassist.pf.core.designsystem.components.TabHeader
import ru.finassist.pf.core.designsystem.components.TransactionGroup
import ru.finassist.pf.core.designsystem.components.TransactionRow
import ru.finassist.pf.core.designsystem.components.ValueTone
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.operations.impl.domain.Grouping
import java.time.ZoneId
import kotlin.math.roundToInt

/** «Операции» tab (mockups Main / MainStale / MainEmpty). */
@Composable
fun FeedScreen(state: FeedViewModel.UiState, onSearch: () -> Unit, onUpload: () -> Unit, onDetail: (String) -> Unit, onLoadMore: () -> Unit, onRetry: () -> Unit) {
    val c = PfTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { val info = listState.layoutInfo; (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, state.page?.nextBefore) { if (nearEnd && state.page?.nextBefore != null) onLoadMore() }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        TabHeader(title = "Операции") {
            if (!state.isEmpty) PfIconButton("search", contentDescription = "Поиск", onClick = onSearch)
        }
        when {
            state.isEmpty -> Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.Center) {
                EmptyState("receipt", "Пока нет операций", "Загрузите выписку из Т-Банка за несколько месяцев — лучше за год. Разложим траты по категориям, сравним месяцы и найдём регулярные платежи")
                if (state.uploadEnabled) PfButton("Загрузить выписку", onClick = onUpload, variant = ButtonVariant.Primary, block = true)
            }
            state.error != null && state.items.isEmpty() -> Column(Modifier.padding(ScreenPadding)) {
                Notice(if (state.error is AppError.Offline) "Нет сети. Проверьте интернет и повторите" else "Не получилось загрузить операции", tone = NoticeTone.Warning, alert = true, action = { PfLink("Повторить", onClick = onRetry) })
            }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = ScreenPadding)) {
                val page = state.page
                val first = page?.summary?.firstOrNull()
                if (first != null) item(key = "summary") {
                    val ym = (PeriodKey.parse(first.month) as? PeriodKey.Month)?.yearMonth
                    val dataTo = first.dataTo?.atZoneSameInstant(zone)?.toLocalDate()
                    val title = (ym?.let { RussianDates.monthTitle(it, withYear = false) } ?: first.month) + (dataTo?.let { " · по ${RussianDates.dayMonth(it)}" } ?: "")
                    val balance = first.income - first.expense
                    val share = if (first.income.kopecks > 0) "${(balance.kopecks * 100.0 / first.income.kopecks).roundToInt()}% дохода" else "Доходов за период нет"
                    MonthSummary(
                        title = title,
                        amountLabel = "Расходы",
                        amount = MoneyFormat.rub(first.expense, SignStyle.Expense),
                        items = listOf(
                            SummaryItem("Доходы", MoneyFormat.rub(first.income, SignStyle.Income), ValueTone.Positive),
                            SummaryItem("Доходы минус расходы · $share", MoneyFormat.signed(balance)),
                        ),
                        footer = {
                            val lastOp = page.lastOperationAt?.atZoneSameInstant(zone)?.toLocalDate()
                            if (page.stale) {
                                Spacer(Modifier.height(PfSpace.s3))
                                Notice("Операции в приложении по ${lastOp?.let(RussianDates::dayMonth) ?: "…"} — с тех пор прошло больше двух недель", tone = NoticeTone.Info,
                                    action = if (state.uploadEnabled) { { PfLink("Загрузить новую выписку", onClick = onUpload, inline = true) } } else null)
                            } else {
                                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Операции по ${lastOp?.let(RussianDates::dayMonth) ?: "…"}" + if (state.uploadEnabled) " · " else "", style = PfTheme.type.caption, color = c.textMuted)
                                        if (state.uploadEnabled) PfLink("Загрузить новую выписку", onClick = onUpload, inline = true)
                                    }
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(PfSpace.s2))
                }
                Grouping.byDay(state.items, zone).forEach { group ->
                    item(key = "g-" + group.label) {
                        TransactionGroup(label = group.label) {
                            group.items.forEach { op ->
                                TransactionRow(
                                    icon = op.categoryIcon, title = op.title, subtitle = op.categoryName, amount = op.amountText,
                                    income = op.isIncome, muted = op.isPair, note = op.note, onClick = { onDetail(op.id) },
                                )
                            }
                        }
                    }
                }
                if (state.loadingMore) item { Text("Загружаем…", style = PfTheme.type.caption, color = c.textMuted, modifier = Modifier.padding(vertical = PfSpace.s4)) }
                item { Spacer(Modifier.height(PfSpace.s6)) }
            }
        }
    }
}
