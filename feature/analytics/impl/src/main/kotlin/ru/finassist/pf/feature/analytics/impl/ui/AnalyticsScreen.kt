package ru.finassist.pf.feature.analytics.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.countWithNoun
import ru.finassist.pf.core.common.time.toApiString
import ru.finassist.pf.core.designsystem.components.BarPoint
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.InsightState
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfAmountText
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
import ru.finassist.pf.core.designsystem.components.ValueTone
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
    AnalyticsContent(state, actions, vm)
}

/**
 * User actions of the analytics screen. Implemented by [AnalyticsViewModel]; design-check snapshots pass a
 * no-op to render [AnalyticsContent] from a ready state.
 */
interface AnalyticsHandlers {
    fun load(quiet: Boolean = false)
    fun consumeSnackbar()
    fun setPeriodType(type: PeriodTypeCode)
    fun goTo(key: PeriodKey)
    fun openSheet(sheet: AnalyticsSheet?)
    fun nextHint(stop: Boolean)
    fun onTileOpened(tile: String)
    fun dismissRegular(id: String)
    fun setTransferMode(mode: TransferMode)
    fun currentTransferMode(): TransferMode
    fun toggleAllCategories()
}

@Composable
internal fun AnalyticsContent(state: AnalyticsUiState, actions: AnalyticsActions, vm: AnalyticsHandlers) {
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
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = d.space5).padding(top = d.space2, bottom = d.space6),
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

/**
 * Screen body in the mockup order (FIN-32): assistant card → period → period type → transfers chip → tiles →
 * expense chart → categories → income → insights → «Доходы минус расходы по месяцам».
 */
@Composable
private fun ColumnScope.Content(a: Analytics, state: AnalyticsUiState, vm: AnalyticsHandlers, actions: AnalyticsActions) {
    val params = a.params ?: return
    val period = a.period ?: return
    val blocks = state.blocks
    val d = PfTheme.dimens

    if (blocks.assistant) {
        val limit = state.limit
        PfAskCard(
            onClick = { actions.openChat(params.transferMode.effective) },
            remaining = limit?.remaining ?: 5,
            total = limit?.dailyMax ?: 5,
            exhausted = limit != null && limit.remaining <= 0,
        )
    }
    if (state.hint == AnalyticsHint.ASK) {
        Gap(d.space3)
        PfCoachmark(
            "Спросите помощника о своих расходах — он посчитает по вашим операциям. 5 вопросов в день",
            onClose = { vm.nextHint(stop = false) },
            onNever = { vm.nextHint(stop = true) },
        )
    }
    a.state?.takeIf { it.stale }?.lastOperationAt?.let { last ->
        Gap(d.space3)
        PfNotice(
            "Операции в приложении по ${RussianDates.day(last.toLocalDate())} — с тех пор прошло больше двух недель",
            tone = NoticeTone.WARNING,
            action = if (blocks.upload) ({ PfLink("Загрузить новую выписку", onClick = actions.openUpload, inline = true) }) else null,
        )
    }
    Gap(d.space3)
    PfPeriodNav(
        label = AnalyticsTexts.periodTitle(params.date),
        onPrev = a.navigation?.previous?.let { key -> { vm.goTo(key) } },
        onNext = a.navigation?.next?.let { key -> { vm.goTo(key) } },
        onPick = { vm.openSheet(AnalyticsSheet.PERIODS) },
        unitName = AnalyticsTexts.unitName(params.period),
    )
    Gap(d.space2)
    PfSegmentedControl(
        options = listOf("Месяц", "Квартал", "Год"),
        selected = PERIOD_TYPES.indexOf(params.period).coerceAtLeast(0),
        onSelect = { vm.setPeriodType(PERIOD_TYPES[it]) },
    )
    if (blocks.transfersFilter) {
        Gap(d.space3)
        PfChipRow {
            val without = params.transferMode.effective == TransferMode.WITHOUT
            PfChip(if (without) "Без переводов" else "С переводами", onClick = { vm.openSheet(AnalyticsSheet.TRANSFERS) }, selected = without, dropdown = true)
        }
    }
    a.state?.let { s ->
        if (s.unreadLinesCount > 0) {
            Gap(d.space3)
            PfNotice(
                "${countWithNoun(s.unreadLinesCount, "строку", "строки", "строк")} за период не прочитали — суммы могут быть неполными",
                tone = NoticeTone.WARNING,
                action = { PfLink("Подробнее", onClick = { actions.openUnreadLines(period.range.from.toApiString(), period.range.to.toApiString()) }, inline = true) },
            )
        }
        if (s.recalculating) {
            Gap(d.space3)
            PfNotice("Пересчитываем после изменений — цифры обновятся через несколько секунд", tone = NoticeTone.INFO)
        }
    }
    // A past month or an incomplete quarter/year says which part of it the numbers cover.
    (AnalyticsTexts.partialPeriod(period, params.period) ?: AnalyticsTexts.coverage(period).takeIf { !period.isCurrent })?.let {
        Gap(d.space4)
        Text(it, style = PfTheme.type.caption, color = PfTheme.colors.textMuted)
    }

    if (blocks.tiles && a.tiles != null) {
        Gap(d.space4)
        Tiles(a, vm, actions)
    }

    if (blocks.monthlyChart) a.monthlyChart?.let { chart -> ExpenseChart(a, chart) }

    if (blocks.expenseCategories) a.expenseCategories?.let { b ->
        Gap(d.space6)
        ExpenseBreakdown(b, a.tiles?.expense?.value, periodCaption(period), state, vm, actions.openSearch)
    }
    if (blocks.incomeCategories) a.incomeCategories?.let { b ->
        Gap(d.space4)
        IncomeBreakdown(b, params.period, period, actions.openSearch)
    }
    a.insights?.let { insights -> Insights(insights, a, blocks, vm, actions) }
    if (blocks.monthlyChart && params.period != PeriodTypeCode.YEAR) BalanceSection(a)
}

@Composable
private fun Gap(height: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(height))

/** 2×2 tiles as on the mockup: «Доходы минус расходы», forecast, «Расходы в день», «Доходы». */
@Composable
private fun Tiles(a: Analytics, vm: AnalyticsHandlers, actions: AnalyticsActions) {
    val tiles = a.tiles ?: return
    val params = a.params ?: return
    val period = a.period ?: return
    val d = PfTheme.dimens
    fun open(tile: String, f: OperationsFilter?): (() -> Unit)? = f?.let { { vm.onTileOpened(tile); actions.openSearch(it) } }
    val balance: @Composable (Modifier) -> Unit = { m ->
        Tile("Доходы минус расходы", tiles.balance, a, m, note = AnalyticsTexts.share(tiles.balance).let { if (tiles.balance.shareOfIncome != null && it.endsWith("дохода")) "$it · по выписке" else it }, chartId = "analytics.tile.balance")
    }
    val forecast: (@Composable (Modifier) -> Unit)? = tiles.forecast?.let { f ->
        { m ->
            // With stale data the forecast is replaced by the fact (api.md 6.1 `stale_data`).
            val staleFact = f.status.effective == MetricStatus.LOCKED && f.lock?.reason == LockReason.STALE_DATA
            if (staleFact) {
                PfStatTile("Расходы", m, value = tiles.expense.value?.format(), note = periodCaption(period), chartId = "analytics.tile.forecast", onClick = open("expense", tiles.expense.filters))
            } else {
                Tile(
                    "Прогноз расходов", f, a, m, note = AnalyticsTexts.forecastNote(params.date),
                    approx = true, chartId = "analytics.tile.forecast",
                )
            }
        }
    }
    val daily: @Composable (Modifier) -> Unit = { m ->
        val note = if (params.period == PeriodTypeCode.MONTH) AnalyticsTexts.dailyComparison(tiles.dailyExpense.comparison) else AnalyticsTexts.dailyAverageOver(period)
        Tile("Расходы в день", tiles.dailyExpense, a, m, note = note, chartId = "analytics.tile.daily")
    }
    val income: @Composable (Modifier) -> Unit = { m ->
        Tile("Доходы", tiles.income, a, m, sign = Money.Sign.ALWAYS, onClick = open("income", tiles.income.filters), chartId = "analytics.tile.income")
    }
    // Without a forecast (past periods) the expense of the period takes its place, so the grid stays 2×2.
    val second: @Composable (Modifier) -> Unit = forecast ?: { m ->
        Tile("Расходы", tiles.expense, a, m, note = periodCaption(period), onClick = open("expense", tiles.expense.filters), chartId = "analytics.tile.expense")
    }
    Column(verticalArrangement = Arrangement.spacedBy(d.space4)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(d.space4)) {
            balance(Modifier.weight(1f).fillMaxHeight())
            second(Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(d.space4)) {
            daily(Modifier.weight(1f).fillMaxHeight())
            income(Modifier.weight(1f).fillMaxHeight())
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
    approx: Boolean = false,
    onClick: (() -> Unit)? = null,
    chartId: String,
) {
    val value = (metric as? Metric)?.value
    if (!metric.isReady) {
        PfStatTile(label, modifier, locked = true, lockedText = AnalyticsTexts.lock(metric.lock, a.state), chartId = chartId)
    } else {
        val shown = value?.format(sign)
        PfStatTile(
            label, modifier,
            value = shown?.let { if (approx) "≈$it" else it },
            valueLabel = shown?.let { if (approx) "около $it" else it },
            note = note, chartId = chartId, onClick = onClick,
        )
    }
}

/** «Расходы по месяцам» right after the tiles, with the comparison and the average under the bars. */
@Composable
private fun ExpenseChart(a: Analytics, chart: ru.finassist.pf.core.api.model.MonthlyChart) {
    val params = a.params ?: return
    val d = PfTheme.dimens
    Gap(d.space6)
    PfSectionTitle("Расходы по месяцам")
    Gap(d.space2)
    if (!chart.isReady || chart.points.isNullOrEmpty()) {
        PfInsightCard(PfIcons.BAR_CHART, "График по месяцам", state = InsightState.LOCKED, lockedText = AnalyticsTexts.lock(chart.lock, a.state))
        return
    }
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
            height = 96.dp,
        )
        val expense = a.tiles?.expense
        val main = if (params.period == PeriodTypeCode.MONTH) {
            expense?.value?.let { AnalyticsTexts.comparison(it, expense.comparison, params.period, params.date) }
        } else {
            AnalyticsTexts.peakMonth(points)
        }
        main?.let { Text(it, style = PfTheme.type.body, color = PfTheme.colors.text, modifier = Modifier.padding(top = d.space4)) }
        AnalyticsTexts.typical(expense?.typical)?.let {
            Text(it, style = PfTheme.type.hint, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = d.space1))
        }
    }
}

/** The closing section: «Доходы минус расходы» as an amount, an explanation and the share of income by month. */
@Composable
private fun BalanceSection(a: Analytics) {
    val balance = a.tiles?.balance?.takeIf { it.isReady } ?: return
    val params = a.params ?: return
    val d = PfTheme.dimens
    val points = a.monthlyChart?.takeIf { it.isReady }?.points.orEmpty()
    val withChart = points.isNotEmpty()
    Gap(d.space6)
    PfSectionTitle(if (withChart) "Доходы минус расходы по месяцам" else "Доходы минус расходы")
    Gap(d.space2)
    PfCard {
        balance.value?.let { PfAmountText(it.format(), style = PfTheme.type.amountLg, color = PfTheme.colors.text) }
        Text(AnalyticsTexts.balanceExplained(balance, withChart), style = PfTheme.type.caption, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = d.space1))
        if (withChart) {
            // Share of income kept per month (api.md 6.1); a month where more was spent than earned goes below the baseline.
            val shares = points.map { AnalyticsTexts.balanceShare(it) }
            val maxShare = shares.filterNotNull().maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0 } ?: 1.0
            val selected = points.indexOfFirst { it.month == params.date.value }
            Gap(d.space4)
            PfBarChart(
                chartId = "analytics.balance.monthly",
                points = points.mapIndexed { i, p ->
                    val ym = YearMonth.parse(p.month)
                    val share = shares[i]
                    BarPoint(
                        key = p.month,
                        label = RussianDates.monthShort(ym.month),
                        spokenLabel = RussianDates.monthTitle(ym),
                        value = share?.let { (it / maxShare).toFloat() },
                        display = share?.let { "${if (it < 0) Money.MINUS else ""}${kotlin.math.abs((it * 100).roundToInt())}%" } ?: "нет данных",
                        partial = p.coverage.effective == Coverage.PARTIAL,
                        note = AnalyticsTexts.barNote(p),
                    )
                },
                highlight = if (selected >= 0) selected else points.lastIndex,
                height = 64.dp,
                showValues = true,
            )
        }
    }
}

private fun periodCaption(p: ru.finassist.pf.core.api.model.AnalyticsPeriodInfo): String {
    val from = p.dataFrom?.toLocalDate() ?: p.range.from.toLocalDate()
    val to = p.dataTo?.toLocalDate() ?: RussianDates.lastDayOf(p.range)
    return RussianDates.dayRange(from, to)
}

private const val TOP_CATEGORIES = 4

/**
 * «Расходы по категориям»: «Всего · 1–25 сентября» with the amount above the card, top 4 bars, «Все категории»
 * under the card. Bar length is relative to the largest positive amount (api.md 6.1).
 */
@Composable
private fun ExpenseBreakdown(
    b: CategoryBreakdown,
    total: Money?,
    caption: String,
    state: AnalyticsUiState,
    vm: AnalyticsHandlers,
    openSearch: (OperationsFilter) -> Unit,
) {
    val d = PfTheme.dimens
    PfSectionTitle("Расходы по категориям")
    Gap(d.space2)
    if (b.status != BreakdownStatus.READY || b.items.isEmpty()) {
        Text("Расходов за $caption нет", style = PfTheme.type.body, color = PfTheme.colors.textMuted)
        return
    }
    if (total != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.space3)) {
            Text("Всего · $caption", style = PfTheme.type.body, color = PfTheme.colors.textMuted, modifier = Modifier.weight(1f))
            PfAmountText(total.format(), style = PfTheme.type.amountMd, color = PfTheme.colors.text)
        }
    }
    if (state.hint == AnalyticsHint.CATEGORIES) {
        Gap(d.space3)
        PfCoachmark(
            "Нажмите на категорию или любую цифру — откроются операции, из которых она сложилась. Категорию операции можно поменять",
            title = "Выписка разобрана",
            onClose = { vm.nextHint(stop = false) },
            onNever = { vm.nextHint(stop = true) },
        )
    }
    Gap(d.space2)
    val maxPositive = b.items.maxOf { it.amount.minor }.coerceAtLeast(1)
    val expanded = state.allExpenseCategories
    val visible = if (expanded) b.items else b.items.take(TOP_CATEGORIES)
    PfCard {
        Column(verticalArrangement = Arrangement.spacedBy(d.space2)) {
            visible.forEachIndexed { i, item ->
                PfCategoryBar(
                    chartId = "analytics.expense.categories",
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
    }
    if (b.items.size > TOP_CATEGORIES) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            PfLink(if (expanded) "Свернуть" else "Все категории", onClick = vm::toggleAllCategories)
        }
    }
}

/** «Доходы»: plain rows with the note under the name, no bars or shares (mockup). */
@Composable
private fun IncomeBreakdown(
    b: CategoryBreakdown,
    periodType: PeriodTypeCode,
    period: ru.finassist.pf.core.api.model.AnalyticsPeriodInfo,
    openSearch: (OperationsFilter) -> Unit,
) {
    val d = PfTheme.dimens
    val months = if (periodType != PeriodTypeCode.MONTH) {
        val from = period.dataFrom?.let(YearMonth::from)
        val to = period.dataTo?.let { YearMonth.from(it.minusSeconds(1)) }
        if (from != null && to != null) " · ${RussianDates.monthRange(from, to)}" else ""
    } else ""
    PfSectionTitle("Доходы$months")
    Gap(d.space2)
    if (b.status != BreakdownStatus.READY || b.items.isEmpty()) {
        Text("Доходов за ${periodCaption(period)} нет", style = PfTheme.type.body, color = PfTheme.colors.textMuted)
        return
    }
    PfCard {
        b.items.forEachIndexed { i, item ->
            PfDataRow(
                item.categoryName,
                item.amount.format(Money.Sign.ALWAYS),
                sublabel = item.note.takeIf { periodType == PeriodTypeCode.MONTH },
                tone = ValueTone.POSITIVE,
                onClick = { openSearch(item.filters) },
                divider = i < b.items.lastIndex,
            )
        }
    }
}

@Composable
private fun Insights(
    insights: ru.finassist.pf.core.api.model.Insights,
    a: Analytics,
    blocks: AnalyticsBlocks,
    vm: AnalyticsHandlers,
    actions: AnalyticsActions,
) {
    val anyOn = blocks.regularPayments || blocks.notableSpending || blocks.bankFees || blocks.smallFrequent
    if (!anyOn) return
    val d = PfTheme.dimens
    val period = a.period ?: return
    Gap(d.space6)
    PfSectionTitle("Регулярные платежи и заметные траты")
    Gap(d.space2)
    fun stateOf(m: MetricLike) = when (m.status.effective) {
        MetricStatus.READY -> InsightState.READY
        MetricStatus.TENTATIVE -> InsightState.TENTATIVE
        MetricStatus.NONE -> InsightState.EMPTY
        else -> InsightState.LOCKED
    }
    val basisMonths: (MetricLike) -> String? = { m ->
        m.basis?.range?.let { r -> RussianDates.monthRange(YearMonth.from(r.from), YearMonth.from(r.to.minusDays(1))) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(d.space3)) {
        if (blocks.regularPayments) {
            val r = insights.regularPayments
            PfInsightCard(
                icon = PfIcons.REPEAT,
                title = "Подписки и регулярные платежи",
                amount = r.value?.format(),
                description = when (stateOf(r)) {
                    InsightState.EMPTY -> listOfNotNull("Регулярных платежей не нашли", basisMonths(r)?.let { "за $it" }).joinToString(" ")
                    else -> listOfNotNull(
                        "в месяц",
                        r.count?.let { countWithNoun(it, "списание", "списания", "списаний") + " каждый месяц" + (r.basis?.range?.let { b -> " с ${RussianDates.monthGenitive(b.from.month)}" } ?: "") },
                    ).joinToString(" · ")
                },
                state = stateOf(r),
                lockedText = AnalyticsTexts.lock(r.lock, a.state),
                chartId = "analytics.insight.regular",
                onClick = r.filters?.let { f -> { actions.openSearch(f) } },
            ) {
                // «Не подписка» stays per row: each link names the payment it removes.
                r.items.orEmpty().take(3).forEach { p ->
                    PfDataRow(p.title, p.amount.format(), sublabel = p.scheduleName, onClick = { actions.openSearch(p.filters) }, divider = false)
                    PfLink("Не подписка", onClick = { vm.dismissRegular(p.id) }, inline = true)
                }
                val rest = r.items.orEmpty().drop(3)
                if (rest.isNotEmpty()) {
                    val sum = rest.fold(Money.ZERO) { acc, p -> acc + p.monthlyAmount }
                    PfDataRow("Ещё ${rest.size}", sum.format(), tone = ValueTone.MUTED, divider = false)
                }
            }
        }
        if (blocks.notableSpending) {
            val n = insights.notableSpending
            val items = n.items.orEmpty()
            if (stateOf(n) == InsightState.READY && items.isNotEmpty()) {
                // One card per category, as on the mockup («Заправки 7 200 ₽ · В 2,3 раза больше обычного…»).
                items.forEach { item ->
                    PfInsightCard(
                        icon = item.categoryIcon,
                        title = item.categoryName,
                        amount = item.amount.format(),
                        description = AnalyticsTexts.notable(item.amount, item.typicalAmount, n.basis?.range),
                        chartId = "analytics.insight.notable",
                        onClick = { actions.openSearch(item.filters) },
                    )
                }
            } else {
                PfInsightCard(
                    icon = PfIcons.BAR_CHART,
                    title = "Заметные траты",
                    description = if (stateOf(n) == InsightState.EMPTY) {
                        "Заметных трат не нашли — расходы по категориям в пределах обычного" + (basisMonths(n)?.let { " за $it" } ?: "")
                    } else null,
                    state = stateOf(n),
                    lockedText = AnalyticsTexts.lock(n.lock, a.state),
                    chartId = "analytics.insight.notable",
                )
            }
        }
        if (blocks.bankFees) {
            val f = insights.bankFees
            // Month names have the same accusative and nominative form: «за сентябрь».
            val forPeriod = "за ${RussianDates.monthNominative(period.range.from.month)}"
            PfInsightCard(
                icon = PfIcons.PERCENT,
                title = "Комиссии и проценты банку",
                amount = f.value?.format(),
                description = if (stateOf(f) == InsightState.EMPTY) "Комиссий $forPeriod нет" else listOfNotNull(forPeriod, f.kindsName).joinToString(" · "),
                state = stateOf(f),
                lockedText = AnalyticsTexts.lock(f.lock, a.state),
                chartId = "analytics.insight.fees",
                onClick = f.filters?.let { filter -> { actions.openSearch(filter) } },
            )
        }
        if (blocks.smallFrequent) {
            val s = insights.smallFrequent
            val groups = s.items.orEmpty()
            val first = groups.firstOrNull()
            PfInsightCard(
                icon = "coffee",
                title = "Мелкие частые траты",
                amount = s.value?.let { "≈${it.format()}" },
                description = when {
                    stateOf(s) == InsightState.EMPTY -> "Мелких частых трат не нашли"
                    first != null -> AnalyticsTexts.smallFrequent(first.title, first.count, first.averageAmount, first.yearlyAmount)
                    else -> null
                },
                state = stateOf(s),
                lockedText = AnalyticsTexts.lock(s.lock, a.state),
                chartId = "analytics.insight.small",
                // The card shows the first group, so it opens that group; the other groups have their own rows.
                onClick = first?.let { g -> { actions.openSearch(g.filters) } },
            ) {
                // The first group is the description; any further groups stay as rows.
                groups.drop(1).forEach { g ->
                    PfDataRow(
                        g.title, "${g.monthlyAmount.format()} в месяц",
                        sublabel = "${countWithNoun(g.count, "раз", "раза", "раз")} в месяц · по ${g.averageAmount.format()}",
                        onClick = { actions.openSearch(g.filters) },
                        divider = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun Sheets(state: AnalyticsUiState, vm: AnalyticsHandlers) {
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

