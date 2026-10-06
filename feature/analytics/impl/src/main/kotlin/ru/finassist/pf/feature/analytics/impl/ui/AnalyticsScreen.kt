package ru.finassist.pf.feature.analytics.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.BreakdownStatus
import ru.finassist.pf.core.api.model.CategoryBreakdown
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.Metric
import ru.finassist.pf.core.api.model.MetricLike
import ru.finassist.pf.core.api.model.MetricStatus
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.countWithNoun
import ru.finassist.pf.core.common.time.toApiString
import ru.finassist.pf.core.designsystem.components.BarPoint
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.InsightState
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfAskCard
import ru.finassist.pf.core.designsystem.components.PfBarChart
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfCategoryBar
import ru.finassist.pf.core.designsystem.components.PfChip
import ru.finassist.pf.core.designsystem.components.PfChipRow
import ru.finassist.pf.core.designsystem.components.PfCoachmark
import ru.finassist.pf.core.designsystem.components.PfDataRow
import ru.finassist.pf.core.designsystem.components.PfEmptyState
import ru.finassist.pf.core.designsystem.components.PfInsightCard
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfNotice
import ru.finassist.pf.core.designsystem.components.PfOptionRow
import ru.finassist.pf.core.designsystem.components.PfPageHeader
import ru.finassist.pf.core.designsystem.components.PfPeriodNav
import ru.finassist.pf.core.designsystem.components.PfSectionTitle
import ru.finassist.pf.core.designsystem.components.PfSegmentedControl
import ru.finassist.pf.core.designsystem.components.PfSnackbar
import ru.finassist.pf.core.designsystem.components.PfStatTile
import ru.finassist.pf.core.designsystem.components.PfTabHeader
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.analytics.impl.domain.AnalyticsTexts
import java.time.YearMonth
import kotlin.math.roundToInt

/** Navigation callbacks of the Analytics screen. */
class AnalyticsActions(
    val openSearch: (OperationsFilter) -> Unit,
    val openChat: (TransferMode) -> Unit,
    val openUpload: () -> Unit,
    val openUnreadLines: (from: String, to: String) -> Unit,
    val back: (() -> Unit)?,
)

private val PERIOD_TYPES = listOf(PeriodTypeCode.MONTH, PeriodTypeCode.QUARTER, PeriodTypeCode.YEAR)

@Composable
fun AnalyticsScreen(params: AnalyticsParams?, actions: AnalyticsActions, vm: AnalyticsViewModel = hiltViewModel()) {
    LaunchedEffect(params) { vm.init(params) }
    val state by vm.state.collectAsStateWithLifecycle()
    val d = PfTheme.dimens
    Column(Modifier.fillMaxSize()) {
        if (actions.back != null) PfPageHeader("Аналитика", onBack = actions.back) else PfTabHeader("Аналитика")
        val a = state.data
        when {
            state.loading -> Unit
            state.offline -> PfEmptyState(PfIcons.ALERT, "Нет сети", "Проверьте интернет и попробуйте ещё раз") {
                PfButton("Повторить", onClick = { vm.load() }, variant = ButtonVariant.PRIMARY)
            }
            a == null || !a.hasData -> PfEmptyState(
                PfIcons.BAR_CHART,
                "Аналитики пока нет",
                if (state.blocks.upload) "Загрузите выписку Т-Банка — посчитаем расходы и доходы по категориям" else null,
            ) {
                if (state.blocks.upload) PfButton("Загрузить выписку", onClick = actions.openUpload, variant = ButtonVariant.PRIMARY, icon = PfIcons.UPLOAD)
            }
            else -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = d.space5).padding(bottom = d.space8),
                verticalArrangement = Arrangement.spacedBy(d.space4),
            ) {
                Content(a, state, vm, actions)
            }
        }
        state.snackbar?.let { text ->
            LaunchedEffect(text) {
                delay(3000)
                vm.consumeSnackbar()
            }
            PfSnackbar(text, Modifier.padding(d.space4))
        }
    }
    Sheets(state, vm)
}

@Composable
private fun ColumnScope.Content(a: Analytics, state: AnalyticsUiState, vm: AnalyticsViewModel, actions: AnalyticsActions) {
    val params = a.params ?: return
    val period = a.period ?: return
    val blocks = state.blocks
    val d = PfTheme.dimens

    a.state?.takeIf { it.stale }?.lastOperationAt?.let { last ->
        PfNotice(
            "Операции в приложении по ${RussianDates.day(last.toLocalDate())} — с тех пор прошло больше двух недель",
            tone = NoticeTone.WARNING,
            action = if (blocks.upload) ({ PfLink("Загрузить выписку", onClick = actions.openUpload, inline = true) }) else null,
        )
    }
    PfSegmentedControl(
        options = listOf("Месяц", "Квартал", "Год"),
        selected = PERIOD_TYPES.indexOf(params.period).coerceAtLeast(0),
        onSelect = { vm.setPeriodType(PERIOD_TYPES[it]) },
    )
    Column {
        PfPeriodNav(
            label = AnalyticsTexts.periodTitle(params.date),
            onPrev = a.navigation?.previous?.let { key -> { vm.goTo(key) } },
            onNext = a.navigation?.next?.let { key -> { vm.goTo(key) } },
            onPick = { vm.openSheet(AnalyticsSheet.PERIODS) },
            unitName = AnalyticsTexts.unitName(params.period),
        )
        AnalyticsTexts.coverage(period)?.let {
            Text(it, style = PfTheme.type.caption, color = PfTheme.colors.textMuted, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
    if (blocks.transfersFilter) {
        PfChipRow {
            val without = params.transferMode.effective == TransferMode.WITHOUT
            PfChip(if (without) "Без переводов" else "С переводами", onClick = { vm.openSheet(AnalyticsSheet.TRANSFERS) }, selected = without, dropdown = true)
        }
    }
    a.state?.let { s ->
        if (s.unreadLinesCount > 0) {
            PfNotice(
                "${countWithNoun(s.unreadLinesCount, "строку", "строки", "строк")} за период не прочитали — суммы могут быть неполными",
                tone = NoticeTone.WARNING,
                action = { PfLink("Подробнее", onClick = { actions.openUnreadLines(period.range.from.toApiString(), period.range.to.toApiString()) }, inline = true) },
            )
        }
        if (s.recalculating) PfNotice("Пересчитываем после изменений — цифры обновятся через несколько секунд", tone = NoticeTone.INFO)
    }

    if (blocks.tiles && a.tiles != null) Tiles(a, vm, actions)

    if (state.hint == AnalyticsHint.ASK) {
        PfCoachmark(
            "Спросите помощника о своих расходах — он посчитает по вашим операциям. 5 вопросов в день",
            onClose = { vm.nextHint(stop = false) },
            onNever = { vm.nextHint(stop = true) },
        )
    }
    if (blocks.assistant) {
        val limit = state.limit
        PfAskCard(
            onClick = { actions.openChat(params.transferMode.effective) },
            remaining = limit?.remaining ?: 5,
            total = limit?.dailyMax ?: 5,
            exhausted = limit != null && limit.remaining <= 0,
        )
    }

    if (state.hint == AnalyticsHint.CATEGORIES) {
        PfCoachmark(
            "Нажмите на категорию или любую цифру — откроются операции, из которых она сложилась. Категорию операции можно поменять",
            title = "Выписка разобрана",
            onClose = { vm.nextHint(stop = false) },
            onNever = { vm.nextHint(stop = true) },
        )
    }
    if (blocks.expenseCategories) a.expenseCategories?.let { b ->
        Breakdown("Расходы по категориям", "analytics.expense.categories", b, a.tiles?.expense?.value, periodCaption(period), state.allExpenseCategories, vm::toggleAllCategories, actions.openSearch, "Расходов за период нет")
    }
    if (blocks.incomeCategories) a.incomeCategories?.let { b ->
        Breakdown("Доходы по категориям", "analytics.income.categories", b, a.tiles?.income?.value, periodCaption(period), expanded = true, onToggle = {}, openSearch = actions.openSearch, emptyText = "Доходов за период нет")
    }
    if (blocks.monthlyChart) a.monthlyChart?.let { chart ->
        PfSectionTitle("Расходы по месяцам")
        if (!chart.isReady || chart.points.isNullOrEmpty()) {
            PfInsightCard(PfIcons.BAR_CHART, "График по месяцам", state = InsightState.LOCKED, lockedText = AnalyticsTexts.lock(chart.lock, a.state))
        } else {
            val points = chart.points!!
            // Expenses are signed (refunds can exceed purchases): scale by the largest magnitude, keep the sign.
            val maxValue = points.mapNotNull { it.expense?.minor?.let { m -> kotlin.math.abs(m) } }.maxOrNull()?.coerceAtLeast(1) ?: 1
            // Month mode highlights the selected month; quarter and year — the last month of the period.
            val selected = points.indexOfFirst { it.month == params.date.value }
            PfCard {
                PfBarChart(
                    chartId = "analytics.expense.monthly",
                    points = points.map { p ->
                        val ym = YearMonth.parse(p.month)
                        val note = AnalyticsTexts.barNote(p)
                        BarPoint(
                            key = p.month,
                            label = RussianDates.monthShort(ym.month),
                            spokenLabel = RussianDates.monthTitle(ym) + (note?.let { ", $it" } ?: ""),
                            value = p.expense?.minor?.toFloat()?.div(maxValue),
                            display = p.expense?.format() ?: "нет данных",
                            partial = p.coverage.effective == Coverage.PARTIAL,
                            note = note,
                        )
                    },
                    highlight = if (selected >= 0) selected else points.lastIndex,
                    height = 140.dp,
                )
                val expense = a.tiles?.expense
                listOfNotNull(
                    expense?.value?.let { AnalyticsTexts.comparison(it, expense.comparison, params.period) },
                    AnalyticsTexts.typical(expense?.typical),
                ).forEach { Text(it, style = PfTheme.type.caption, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = d.space2)) }
            }
            // «Доходы минус расходы по месяцам» as a share of income (api.md 6.1); a month where more was spent than
            // earned goes below the baseline.
            PfSectionTitle("Доходы минус расходы по месяцам")
            PfCard {
                val shares = points.map { AnalyticsTexts.balanceShare(it) }
                val maxShare = shares.filterNotNull().maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0 } ?: 1.0
                PfBarChart(
                    chartId = "analytics.balance.monthly",
                    points = points.mapIndexed { i, p ->
                        val ym = YearMonth.parse(p.month)
                        val share = shares[i]
                        val display = share?.let { "${if (it < 0) Money.MINUS else ""}${kotlin.math.abs((it * 100).roundToInt())}%" } ?: "нет данных"
                        BarPoint(
                            key = p.month,
                            label = RussianDates.monthShort(ym.month),
                            spokenLabel = RussianDates.monthTitle(ym),
                            value = share?.let { (it / maxShare).toFloat() },
                            display = display,
                            partial = p.coverage.effective == Coverage.PARTIAL,
                            note = AnalyticsTexts.barNote(p),
                        )
                    },
                    highlight = if (selected >= 0) selected else points.lastIndex,
                    height = 120.dp,
                    showValues = true,
                )
                Text("Доля дохода, которая осталась после расходов", style = PfTheme.type.caption, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = d.space2))
            }
        }
    }
    a.insights?.let { insights -> Insights(insights, a, blocks, vm, actions) }
}

@Composable
private fun Tiles(a: Analytics, vm: AnalyticsViewModel, actions: AnalyticsActions) {
    val tiles = a.tiles ?: return
    val params = a.params ?: return
    val d = PfTheme.dimens
    fun open(tile: String, f: OperationsFilter?): (() -> Unit)? = f?.let { { vm.onTileOpened(tile); actions.openSearch(it) } }
    Column(verticalArrangement = Arrangement.spacedBy(d.space3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.space3)) {
            Tile("Расходы", tiles.expense, a, Modifier.weight(1f), note = tiles.expense.value?.let { AnalyticsTexts.comparison(it, tiles.expense.comparison, params.period) }, onClick = open("expense", tiles.expense.filters), chartId = "analytics.tile.expense")
            Tile("Доходы", tiles.income, a, Modifier.weight(1f), onClick = open("income", tiles.income.filters), chartId = "analytics.tile.income")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(d.space3)) {
            Tile("Доходы минус расходы", tiles.balance, a, Modifier.weight(1f), note = AnalyticsTexts.share(tiles.balance), sign = Money.Sign.ALWAYS, chartId = "analytics.tile.balance")
            Tile("В день", tiles.dailyExpense, a, Modifier.weight(1f), note = AnalyticsTexts.dailyComparison(tiles.dailyExpense.comparison), chartId = "analytics.tile.daily")
        }
        tiles.forecast?.let { f ->
            // With stale data the forecast is replaced by the fact (api.md 6.1 `stale_data`).
            val staleFact = f.status.effective == MetricStatus.LOCKED && f.lock?.reason == LockReason.STALE_DATA
            if (staleFact) {
                PfStatTile("Расходы за период", value = tiles.expense.value?.format(), note = "Прогноз не считаем — выписка устарела", chartId = "analytics.tile.forecast")
            } else {
                Tile("Прогноз на конец периода", f, a, Modifier.fillMaxWidth(), chartId = "analytics.tile.forecast")
            }
        }
    }
}

@Composable
private fun Tile(
    label: String,
    metric: MetricLike,
    a: Analytics,
    modifier: Modifier = Modifier,
    note: String? = null,
    sign: Money.Sign = Money.Sign.AUTO,
    onClick: (() -> Unit)? = null,
    chartId: String,
) {
    val value = (metric as? Metric)?.value
    if (!metric.isReady) {
        PfStatTile(label, modifier, locked = true, lockedText = AnalyticsTexts.lock(metric.lock, a.state), chartId = chartId)
    } else {
        PfStatTile(label, modifier, value = value?.format(sign), note = note, chartId = chartId, onClick = onClick)
    }
}

private fun periodCaption(p: ru.finassist.pf.core.api.model.AnalyticsPeriodInfo): String {
    val from = p.dataFrom?.toLocalDate() ?: p.range.from.toLocalDate()
    val to = p.dataTo?.toLocalDate() ?: RussianDates.lastDayOf(p.range)
    return RussianDates.dayRange(from, to)
}

/** Category bars: top 5 + «Все категории»; bar length relative to the largest positive amount (api.md 6.1). */
@Composable
private fun Breakdown(
    title: String,
    chartId: String,
    b: CategoryBreakdown,
    total: Money?,
    caption: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    openSearch: (OperationsFilter) -> Unit,
    emptyText: String,
) {
    val d = PfTheme.dimens
    PfSectionTitle(title)
    if (b.status != BreakdownStatus.READY || b.items.isEmpty()) {
        Text("$emptyText · $caption", style = PfTheme.type.body, color = PfTheme.colors.textMuted)
        return
    }
    val maxPositive = b.items.maxOf { it.amount.minor }.coerceAtLeast(1)
    val visible = if (expanded) b.items else b.items.take(5)
    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(d.space3)) {
            visible.forEachIndexed { i, item ->
                PfCategoryBar(
                    chartId = chartId,
                    index = i,
                    icon = item.categoryIcon,
                    label = item.categoryName + (item.note?.let { " · $it" } ?: ""),
                    amount = item.amount.format(),
                    percent = if (item.amount.minor > 0) item.amount.minor.toFloat() / maxPositive else 0f,
                    share = item.share?.let { "${(it * 100).toInt()}%" },
                    spoken = "${item.categoryName}: ${item.amount.format()}, ${countWithNoun(item.operationCount, "операция", "операции", "операций")}",
                    onClick = { openSearch(item.filters) },
                )
            }
        }
        if (b.items.size > 5) {
            PfLink(if (expanded) "Свернуть" else "Все категории (${b.items.size})", onClick = onToggle)
        }
        total?.let { PfDataRow("Всего · $caption", it.format(), total = true, divider = false) }
    }
}

@Composable
private fun Insights(
    insights: ru.finassist.pf.core.api.model.Insights,
    a: Analytics,
    blocks: AnalyticsBlocks,
    vm: AnalyticsViewModel,
    actions: AnalyticsActions,
) {
    val anyOn = blocks.regularPayments || blocks.notableSpending || blocks.bankFees || blocks.smallFrequent
    if (!anyOn) return
    PfSectionTitle("Регулярные платежи и заметные траты")
    fun stateOf(m: MetricLike) = when (m.status.effective) {
        MetricStatus.READY -> InsightState.READY
        MetricStatus.TENTATIVE -> InsightState.TENTATIVE
        MetricStatus.NONE -> InsightState.EMPTY
        else -> InsightState.LOCKED
    }
    val basisText: (MetricLike) -> String? = { m ->
        m.basis?.range?.let { r -> "с ${RussianDates.monthGenitive(r.from.month)}" }
    }
    if (blocks.regularPayments) {
        val r = insights.regularPayments
        PfInsightCard(
            icon = PfIcons.REPEAT,
            title = "Подписки и регулярные платежи",
            amount = r.value?.let { "${it.format()} в месяц" },
            description = when (stateOf(r)) {
                InsightState.EMPTY -> "Регулярных платежей не нашли"
                else -> listOfNotNull(r.count?.let { countWithNoun(it, "списание", "списания", "списаний") + " каждый месяц" }, basisText(r)).joinToString(" · ").ifEmpty { null }
            },
            state = stateOf(r),
            lockedText = AnalyticsTexts.lock(r.lock, a.state),
            chartId = "analytics.insight.regular",
            onClick = r.filters?.let { f -> { actions.openSearch(f) } },
        ) {
            r.items.orEmpty().take(3).forEach { p ->
                PfDataRow(
                    p.title, p.amount.format(), sublabel = p.scheduleName,
                    onClick = { actions.openSearch(p.filters) },
                )
                PfLink("Не подписка", onClick = { vm.dismissRegular(p.id) }, inline = true)
            }
            val rest = r.items.orEmpty().drop(3)
            if (rest.isNotEmpty()) {
                val sum = rest.fold(Money.ZERO) { acc, p -> acc + p.monthlyAmount }
                PfDataRow("Ещё ${rest.size}", sum.format(), divider = false)
            }
        }
    }
    if (blocks.notableSpending) {
        val n = insights.notableSpending
        PfInsightCard(
            icon = PfIcons.TRENDING_UP,
            title = "Заметные траты",
            description = if (stateOf(n) == InsightState.EMPTY) "Всё в пределах обычного" else basisText(n)?.let { "Сравниваем с обычными тратами $it" },
            state = stateOf(n),
            lockedText = AnalyticsTexts.lock(n.lock, a.state),
            chartId = "analytics.insight.notable",
        ) {
            n.items.orEmpty().forEach { item ->
                val ratio = if (item.typicalAmount.minor > 0) item.amount.minor.toDouble() / item.typicalAmount.minor else null
                PfDataRow(
                    item.categoryName, item.amount.format(),
                    sublabel = "обычно около ${item.typicalAmount.format()}" + (ratio?.let { " · в ${"%.1f".format(it).replace('.', ',')} раза больше" } ?: ""),
                    onClick = { actions.openSearch(item.filters) },
                )
            }
        }
    }
    if (blocks.bankFees) {
        val f = insights.bankFees
        PfInsightCard(
            icon = PfIcons.LANDMARK,
            title = "Комиссии и проценты банку",
            amount = f.value?.format(),
            description = if (stateOf(f) == InsightState.EMPTY) "Комиссий за период нет" else f.kindsName,
            state = stateOf(f),
            lockedText = AnalyticsTexts.lock(f.lock, a.state),
            chartId = "analytics.insight.fees",
            onClick = f.filters?.let { filter -> { actions.openSearch(filter) } },
        )
    }
    if (blocks.smallFrequent) {
        val s = insights.smallFrequent
        PfInsightCard(
            icon = PfIcons.RECEIPT,
            title = "Мелкие частые траты",
            amount = s.value?.let { "≈${it.format()} в месяц" },
            description = if (stateOf(s) == InsightState.EMPTY) "Мелких частых трат не нашли" else null,
            state = stateOf(s),
            lockedText = AnalyticsTexts.lock(s.lock, a.state),
            chartId = "analytics.insight.small",
        ) {
            s.items.orEmpty().forEach { g ->
                PfDataRow(
                    g.title, "${g.monthlyAmount.format()} в месяц",
                    sublabel = "${countWithNoun(g.count, "раз", "раза", "раз")} в месяц · средний чек ${g.averageAmount.format()} · около ${g.yearlyAmount.format()} в год",
                    onClick = { actions.openSearch(g.filters) },
                )
            }
        }
    }
}

@Composable
private fun Sheets(state: AnalyticsUiState, vm: AnalyticsViewModel) {
    when (state.sheet) {
        AnalyticsSheet.PERIODS -> PfBottomSheet("Период", onDismiss = { vm.openSheet(null) }) {
            val current = state.data?.params?.date
            val periods = state.periods
            if (state.periodsFailed) {
                Column(Modifier.padding(PfTheme.dimens.space5)) {
                    Text("Не получилось загрузить периоды", style = PfTheme.type.body, color = PfTheme.colors.textMuted)
                    PfLink("Повторить", onClick = { vm.openSheet(AnalyticsSheet.PERIODS) })
                }
            } else if (periods == null) {
                Text("Загружаем…", style = PfTheme.type.body, color = PfTheme.colors.textMuted, modifier = Modifier.padding(PfTheme.dimens.space5))
            } else {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    periods.forEach { p ->
                        PfOptionRow(
                            AnalyticsTexts.periodTitle(p.key),
                            selected = p.key == current,
                            onClick = { vm.goTo(p.key) },
                            description = if (p.coverage.effective != Coverage.COMPLETE) "неполный" else null,
                        )
                    }
                }
            }
        }
        AnalyticsSheet.TRANSFERS -> PfBottomSheet("Переводы людям", onDismiss = { vm.openSheet(null) }) {
            val mode = vm.currentTransferMode()
            PfOptionRow("С переводами", selected = mode == TransferMode.WITH, onClick = { vm.setTransferMode(TransferMode.WITH) }, description = "Переводы людям считаем тратами и доходами")
            PfOptionRow("Без переводов", selected = mode == TransferMode.WITHOUT, onClick = { vm.setTransferMode(TransferMode.WITHOUT) }, description = "Убираем переводы людям в обе стороны — остаются покупки и зарплата")
            Text(
                "Переводы между своими счетами не считаем в обоих режимах",
                style = PfTheme.type.caption, color = PfTheme.colors.textMuted,
                modifier = Modifier.padding(horizontal = PfTheme.dimens.space5, vertical = PfTheme.dimens.space2),
            )
        }
        null -> Unit
    }
}

