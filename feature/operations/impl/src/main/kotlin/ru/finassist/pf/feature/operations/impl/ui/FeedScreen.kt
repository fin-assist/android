package ru.finassist.pf.feature.operations.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.platform.testTag
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
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, cursor = state.nextBefore, onLoadMore = vm::loadMore)
    FeedContent(
        state = state, today = today, zone = zone, listState = listState,
        onOpenSearch = onOpenSearch, onOpenOperation = onOpenOperation, onUpload = onUpload,
        onRetry = vm::load, onLoadMore = vm::loadMore,
    )
}

/** The feed drawn from a ready state: no ViewModel, so design-check snapshots render it with mockup data. */
@Composable
internal fun FeedContent(
    state: FeedUiState,
    today: LocalDate,
    zone: ZoneId,
    onOpenSearch: () -> Unit,
    onOpenOperation: (id: String) -> Unit,
    onUpload: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val d = PfTheme.dimens
    Column(Modifier.fillMaxSize().testTag(OperationsTags.FEED)) {
        PfTabHeader("Операции") {
            PfIconButton(PfIcons.SEARCH, contentDescription = "Поиск операций", onClick = onOpenSearch, modifier = Modifier.testTag(OperationsTags.FEED_SEARCH))
        }
        when {
            state.loading -> Unit
            state.offline -> PfEmptyState(PfIcons.ALERT, "Нет сети", "Проверьте интернет и попробуйте ещё раз", modifier = Modifier.testTag(OperationsTags.FEED_OFFLINE)) {
                PfButton("Повторить", onClick = onRetry, variant = ButtonVariant.PRIMARY, modifier = Modifier.testTag(OperationsTags.FEED_RETRY))
            }
            state.isEmpty -> PfEmptyState(
                PfIcons.LIST,
                "Пока нет операций",
                if (state.uploadEnabled) "Загрузите выписку Т-Банка — разберём операции по категориям" else null,
                modifier = Modifier.testTag(OperationsTags.FEED_EMPTY),
            ) {
                if (state.uploadEnabled) PfButton("Загрузить выписку", onClick = onUpload, variant = ButtonVariant.PRIMARY, icon = PfIcons.UPLOAD, modifier = Modifier.testTag(OperationsTags.FEED_UPLOAD))
            }
            else -> {
                val groups = remember(state.items) { OperationFormat.groupByDay(state.items, zone, today) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = d.space5, end = d.space5, top = d.space2, bottom = d.space6),
                ) {
                    val feedState = state.state
                    val lastOperation = feedState?.lastOperationAt?.toLocalDate()
                    state.summary.firstOrNull()?.let { s -> item(key = "summary") { SummaryCard(s, today) } }
                    if (feedState?.stale == true && lastOperation != null) {
                        item(key = "stale") {
                            PfNotice(
                                "Операции в приложении по ${RussianDates.day(lastOperation, today.year)} — с тех пор прошло больше двух недель",
                                tone = NoticeTone.INFO,
                                modifier = Modifier.padding(top = d.space3),
                                action = if (state.uploadEnabled) ({ PfLink("Загрузить новую выписку", onClick = onUpload, inline = true, modifier = Modifier.testTag(OperationsTags.FEED_UPLOAD_NEW)) }) else null,
                            )
                        }
                    } else if (lastOperation != null) {
                        // «Операции по 25 сентября · Загрузить новую выписку» under the summary (mockup `Main`).
                        item(key = "fresh") {
                            Row(Modifier.defaultMinSize(minHeight = d.touch), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Операции по ${RussianDates.day(lastOperation, today.year)}" + if (state.uploadEnabled) " · " else "",
                                    style = PfTheme.type.caption, color = PfTheme.colors.textMuted,
                                )
                                if (state.uploadEnabled) PfLink("Загрузить новую выписку", onClick = onUpload, inline = true, modifier = Modifier.testTag(OperationsTags.FEED_UPLOAD_NEW))
                            }
                        }
                    }
                    groups.forEach { (label, ops) ->
                        item(key = "day-$label-${ops.first().id}") {
                            PfTransactionGroup(label) {
                                ops.forEach { op -> OperationRow(op, onClick = { onOpenOperation(op.id) }, divider = true) }
                            }
                        }
                    }
                    if (state.loadingMore) {
                        item(key = "more") { Text("Загружаем…", style = PfTheme.type.caption, color = PfTheme.colors.textMuted, modifier = Modifier.padding(vertical = d.space4)) }
                    } else if (state.moreFailed) {
                        item(key = "more-failed") {
                            Box(Modifier.fillMaxWidth().padding(vertical = d.space3), contentAlignment = Alignment.Center) {
                                PfLink("Не загрузилось — повторить", onClick = onLoadMore)
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
internal fun OperationRow(op: OperationItem, onClick: () -> Unit, divider: Boolean = false) {
    val row = remember(op) { OperationFormat.row(op) }
    PfTransactionRow(
        icon = row.icon, title = row.title, subtitle = row.subtitle, amount = row.amount,
        income = row.income, muted = row.muted, note = row.note, onClick = onClick, divider = divider,
        modifier = Modifier.testTag(OperationsTags.ROW),
    )
}

/**
 * «Сентябрь · по 25 сентября»: expenses as the main amount, then income and «Доходы минус расходы · 46% дохода»
 * (mockup `Main`, api.md 4.1 summary).
 */
@Composable
private fun SummaryCard(s: MonthSummary, today: LocalDate) {
    val month = runCatching { YearMonth.parse(s.month) }.getOrNull()
    val name = month?.let { RussianDates.monthNominative(it.month).replaceFirstChar { c -> c.uppercase() } + if (it.year != today.year) " ${it.year}" else "" } ?: s.month
    val title = s.dataTo?.let { "$name · по ${RussianDates.day(it.toLocalDate())}" } ?: name
    val balance = s.income - s.expense
    val share = if (s.income.minor > 0) (balance.minor * 100.0 / s.income.minor).roundToInt() else null
    PfMonthSummary(
        title = title,
        amountLabel = "Расходы",
        // Expenses are positive in the summary; shown as an outflow «−84 320 ₽».
        amount = (Money.ZERO - s.expense).format(),
        items = listOf(
            SummaryItem("Доходы", s.income.format(Money.Sign.ALWAYS), positive = true),
            SummaryItem("Доходы минус расходы" + (share?.let { " · $it% дохода" } ?: ""), balance.format()),
        ),
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

/** Test tags of the operations screens (UI tests in `.maestro/`, convention in docs/e2e.md). */
internal object OperationsTags {
    const val FEED = "operations.feed"
    const val FEED_SEARCH = "operations.feed.search"
    const val FEED_OFFLINE = "operations.feed.offline"
    const val FEED_RETRY = "operations.feed.retry"
    const val FEED_EMPTY = "operations.feed.empty"
    const val FEED_UPLOAD = "operations.feed.upload"
    const val FEED_UPLOAD_NEW = "operations.feed.upload_new"
    /** A row in the feed or in search results. */
    const val ROW = "operations.row"

    const val DETAIL = "operations.detail"
    const val DETAIL_TITLE = "operations.detail.title"
    const val DETAIL_CATEGORY = "operations.detail.category"
    const val DETAIL_SAVE = "operations.detail.save"

    const val CATEGORY_SEARCH = "operations.category.search"
    const val CATEGORY_OPTION = "operations.category.option"
    const val CATEGORY_ALL = "operations.category.all"

    const val SEARCH = "operations.search"
    const val SEARCH_INPUT = "operations.search.input"
    const val SEARCH_COUNT = "operations.search.count"
    const val SEARCH_INITIAL = "operations.search.initial"
    const val CHIP_PERIOD = "operations.search.chip.period"
    const val CHIP_CATEGORY = "operations.search.chip.category"
    const val CHIP_AMOUNT = "operations.search.chip.amount"
    const val CHIP_KIND = "operations.search.chip.kind"
    const val CHIP_SCOPE = "operations.search.chip.scope"
    const val CHIP_RESET = "operations.search.chip.reset"
    const val PERIOD_LAST_12 = "operations.search.period.last12"
    const val PERIOD_ALL = "operations.search.period.all"
    const val PERIOD_CUSTOM = "operations.search.period.custom"
    const val AMOUNT_FROM = "operations.search.amount.from"
    const val AMOUNT_TO = "operations.search.amount.to"
    const val AMOUNT_APPLY = "operations.search.amount.apply"
    const val SEARCH_ALL_TIME = "operations.search.all_time"
}
