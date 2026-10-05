package ru.finassist.pf.feature.analytics.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.PeriodType
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.RussianDates.plural
import ru.finassist.pf.core.designsystem.charts.Bar
import ru.finassist.pf.core.designsystem.charts.BarChart
import ru.finassist.pf.core.designsystem.charts.CategoryBar
import ru.finassist.pf.core.designsystem.charts.StatTile
import ru.finassist.pf.core.designsystem.components.AskCard
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.ChipRow
import ru.finassist.pf.core.designsystem.components.Coachmark
import ru.finassist.pf.core.designsystem.components.DataRow
import ru.finassist.pf.core.designsystem.components.EmptyState
import ru.finassist.pf.core.designsystem.components.InsightCard
import ru.finassist.pf.core.designsystem.components.InsightState
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PeriodNav
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfChip
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.components.SectionTitle
import ru.finassist.pf.core.designsystem.components.SegmentedControl
import ru.finassist.pf.core.designsystem.components.TabHeader
import ru.finassist.pf.core.designsystem.components.ValueTone
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.navigation.OperationsFilter
import ru.finassist.pf.core.network.codes.Coverage
import ru.finassist.pf.core.network.codes.LockReason
import ru.finassist.pf.core.network.codes.MetricStatus
import ru.finassist.pf.core.network.codes.TransferMode
import ru.finassist.pf.core.network.dto.AnalyticsDto
import ru.finassist.pf.core.network.dto.CategoryBreakdownDto
import ru.finassist.pf.core.network.dto.MetricDto
import ru.finassist.pf.feature.analytics.impl.domain.balanceNote
import ru.finassist.pf.feature.analytics.impl.domain.chartPoint
import ru.finassist.pf.feature.analytics.impl.domain.comparisonText
import ru.finassist.pf.feature.analytics.impl.domain.dailyComparisonText
import ru.finassist.pf.feature.analytics.impl.domain.expenseMoney
import ru.finassist.pf.feature.analytics.impl.domain.incomeMoney
import ru.finassist.pf.feature.analytics.impl.domain.lockText
import ru.finassist.pf.feature.analytics.impl.domain.money
import ru.finassist.pf.feature.analytics.impl.domain.notableText
import ru.finassist.pf.feature.analytics.impl.domain.peakText
import ru.finassist.pf.feature.analytics.impl.domain.percentText
import ru.finassist.pf.feature.analytics.impl.domain.periodGenitive
import ru.finassist.pf.feature.analytics.impl.domain.periodLabel
import ru.finassist.pf.feature.analytics.impl.domain.savingsShare
import ru.finassist.pf.feature.analytics.impl.domain.shortPeriod
import ru.finassist.pf.feature.analytics.impl.domain.sinceText
import ru.finassist.pf.feature.analytics.impl.domain.smallFrequentItemText
import ru.finassist.pf.feature.analytics.impl.domain.toLocalDate
import ru.finassist.pf.feature.analytics.impl.domain.toNav
import ru.finassist.pf.feature.analytics.impl.domain.typicalText
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.money.MoneyFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/** «Аналитика» tab: empty / early / stale / regular states (mockups Analytics*). */
@Composable
fun AnalyticsScreen(
    state: AnalyticsViewModel.UiState,
    vm: AnalyticsViewModel,
    onUpload: () -> Unit,
    onAsk: () -> Unit,
    onSearch: (OperationsFilter) -> Unit,
    onUnreadLines: (from: String, to: String) -> Unit,
) {
    val c = PfTheme.colors
    val data = state.data
    Column(Modifier.fillMaxSize().background(c.bg)) {
        TabHeader(title = "Аналитика")
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = PfSpace.s2),
            verticalArrangement = Arrangement.spacedBy(PfSpace.s5),
        ) {
            when {
                data == null && state.error != null -> {
                    Notice(if (state.error is AppError.Offline) "Нет сети. Проверьте интернет и повторите" else "Не получилось загрузить аналитику", tone = NoticeTone.Warning, action = { PfLink("Повторить", onClick = vm::load, inline = true) })
                }
                data == null -> Text("Считаем…", style = PfTheme.type.body, color = c.textMuted)
                !data.hasData -> {
                    EmptyState(icon = "bar-chart", title = "Аналитики пока нет", text = "Загрузите выписку — покажем расходы по категориям, доходы и разницу между ними, и помощник сможет ответить на вопросы о ваших деньгах")
                    if (state.blocks.upload) PfButton("Загрузить выписку", onClick = onUpload, variant = ButtonVariant.Primary, block = true)
                }
                else -> Content(state, data, vm, onUpload, onAsk, onSearch, onUnreadLines)
            }
            Spacer(Modifier.height(PfSpace.s6))
        }
    }
    AnalyticsSheets(state, vm, onSearch)
}

@Composable
private fun Content(
    state: AnalyticsViewModel.UiState,
    data: AnalyticsDto,
    vm: AnalyticsViewModel,
    onUpload: () -> Unit,
    onAsk: () -> Unit,
    onSearch: (OperationsFilter) -> Unit,
    onUnreadLines: (String, String) -> Unit,
) {
    val c = PfTheme.colors
    val key = PeriodKey.parse(data.params?.date ?: "")
    val period = data.period
    val st = data.state
    val blocks = state.blocks

    if (state.error is AppError.Offline) Notice("Нет сети — показываем то, что было посчитано", tone = NoticeTone.Info)
    if (st?.stale == true) {
        val last = st.lastOperationAt?.let { runCatching { RussianDates.dayMonth(it.toLocalDate()) }.getOrNull() }
        Notice(
            "Операции в приложении по ${last ?: "последней загрузки"} — с тех пор прошло больше двух недель",
            tone = NoticeTone.Warning,
            action = if (blocks.upload) ({ PfLink("Загрузить новую выписку", onClick = onUpload, inline = true) }) else null,
        )
    }
    if (blocks.assistant) {
        Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
            AskCard(
                remaining = state.limitRemaining ?: state.limitTotal, total = state.limitTotal, onClick = onAsk,
                exhausted = state.limitRemaining == 0,
            )
            if (state.hintAsk) Coachmark("Помощник отвечает по вашим операциям, ${plural(state.limitTotal.toLong(), "вопрос", "вопроса", "вопросов")} в день", onClose = { vm.dismissHint(AnalyticsViewModel.HINT_ASK, false) }, onNever = { vm.dismissHint(AnalyticsViewModel.HINT_ASK, true) })
        }
    }

    // Period: segmented type, «‹ Сентябрь 2026 ›», transfers chip.
    Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s3)) {
        SegmentedControl(
            options = listOf("Месяц", "Квартал", "Год"),
            selected = PeriodType.entries.indexOf(state.periodType),
            onSelect = { vm.onPeriodType(PeriodType.entries[it]) },
            label = "Период",
        )
        PeriodNav(
            label = if (period != null) periodLabel(key, Coverage.fromWire(period.coverage), period.dataFrom, period.dataTo, period.gaps) else "…",
            onPrev = data.navigation?.previous?.let { { vm.onPrevious() } },
            onNext = data.navigation?.next?.let { { vm.onNext() } },
            onPick = { vm.onPeriodSheet(true) },
            unitName = when (state.periodType) { PeriodType.Month -> "месяц"; PeriodType.Quarter -> "квартал"; PeriodType.Year -> "год" },
        )
        if (blocks.transfersFilter) ChipRow {
            PfChip(
                text = if (state.transferMode == TransferMode.Without) "Без переводов" else "С переводами",
                onClick = { vm.onTransfersSheet(true) },
                selected = state.transferMode == TransferMode.Without,
                dropdown = true,
            )
        }
    }

    if ((st?.unreadLinesCount ?: 0) > 0 && period != null) {
        Notice(
            "${plural(st!!.unreadLinesCount.toLong(), "строку", "строки", "строк")} ${periodGenitive(key)} не прочитали — суммы могут быть неполными",
            tone = NoticeTone.Warning,
            action = { PfLink("Подробнее", onClick = { onUnreadLines(period.range.from, period.range.to) }, inline = true) },
        )
    }
    if (period != null && Coverage.fromWire(period.coverage) == Coverage.NoData) {
        Notice("За этот период операций нет", tone = NoticeTone.Info)
    }

    if (blocks.tiles) data.tiles?.let { Tiles(it, data, key, st?.stale == true, state, onSearch) }

    if (blocks.monthlyChart) data.monthlyChart?.let { chart ->
        val tiles = data.tiles
        val ready = MetricStatus.fromWire(chart.status) == MetricStatus.Ready && !chart.points.isNullOrEmpty()
        if (ready) {
            val points = chart.points!!
            val suffix = "${state.periodType.wire}.${data.params?.date}.${state.transferMode.wire}"
            Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                SectionTitle("Расходы по месяцам")
                PfCard {
                    val bars = points.map { p -> chartPoint(p, p.expense?.let { abs(it) }) { v -> expenseMoney(v) } }
                    BarChart(bars = bars.map { Bar(it.label, it.spoken, it.value, it.display, it.partialFill, it.partialNote) }, introId = "analytics.expense.$suffix", formatValue = { MoneyFormat.rub(Money(it.toLong())) })
                    val compare = if (state.periodType == PeriodType.Month) comparisonText(tiles?.expense?.value, tiles?.expense?.comparison, key, period?.dataTo) else peakText(points)
                    val avg = typicalText(tiles?.expense?.typical)
                    val missing = points.filter { it.expense == null }
                    val missingText = if (missing.isNotEmpty() && state.periodType != PeriodType.Month) {
                        val names = missing.map { RussianDates.monthNominative[java.time.YearMonth.parse(it.month).monthValue - 1] }
                        "${(if (names.size > 1) "${names.first()} — ${names.last()}" else names.first()).replaceFirstChar { ch -> ch.uppercase() }}: в выписке нет операций"
                    } else null
                    listOfNotNull(compare, avg, missingText).forEach { Spacer(Modifier.height(PfSpace.s2)); Text(it, style = PfTheme.type.caption, color = c.textMuted) }
                }
                val shares = points.map { p -> chartPoint(p, savingsShare(p)?.let { (it * 100).roundToInt().toLong() }) { v -> "$v%" } }
                if (shares.any { it.value != null }) {
                    SectionTitle(if (state.periodType == PeriodType.Month) "Доходы минус расходы по месяцам" else "Доходы минус расходы")
                    PfCard {
                        BarChart(bars = shares.map { Bar(it.label, it.spoken, it.value, it.display, it.partialFill, it.partialNote) }, introId = "analytics.savings.$suffix", formatValue = { "${it.roundToInt()}%" })
                        tiles?.balance?.shareOfIncome?.let { share ->
                            Spacer(Modifier.height(PfSpace.s2))
                            Text(if (share >= 0) "${(share * 100).roundToInt()}% дохода: на столько доходы по выписке больше расходов. На графике — та же доля по месяцам" else "Потратили больше, чем получили. На графике — доля по месяцам", style = PfTheme.type.caption, color = c.textMuted)
                        }
                    }
                }
            }
        } else if (MetricStatus.fromWire(chart.status) == MetricStatus.Locked) {
            InsightCard(icon = "bar-chart", title = "Расходы по месяцам", state = InsightState.Locked, lockedText = lockText(chart.lock, st))
        }
    }

    if (blocks.expenseCategories) data.expenseCategories?.let { cats ->
        Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
            SectionTitle("Расходы по категориям")
            if (state.hintCategories) Coachmark("Категории проставили мы — любую можно поправить в операции", onClose = { vm.dismissHint(AnalyticsViewModel.HINT_CATEGORIES, false) }, onNever = { vm.dismissHint(AnalyticsViewModel.HINT_CATEGORIES, true) })
            if (MetricStatus.fromWire(cats.status) != MetricStatus.Ready || cats.items.isEmpty()) {
                PfCard { Text("Расходов за ${shortPeriod(data)} нет", style = PfTheme.type.body, color = c.textMuted) }
            } else {
                PfCard {
                    val maxAmount = cats.items.maxOf { it.amount }.takeIf { it > 0 } ?: 1L
                    cats.items.take(TOP_CATEGORIES).forEachIndexed { i, item ->
                        CategoryBar(
                            icon = item.categoryIcon, label = item.categoryName, amount = expenseMoney(item.amount),
                            percent = if (item.amount > 0) (item.amount * 100 / maxAmount).toInt() else 0,
                            share = percentText(item.share), note = item.note,
                            introId = "analytics.cat.${data.params?.date}.${state.transferMode.wire}", introIndex = i,
                            onClick = { onSearch(item.filters.toNav()) },
                        )
                    }
                    DataRow(label = "Всего · ${shortPeriod(data)}", value = expenseMoney(data.tiles?.expense?.value), total = true, onClick = data.tiles?.expense?.filters?.let { f -> { onSearch(f.toNav()) } })
                    if (cats.items.size > TOP_CATEGORIES) PfLink("Все категории", onClick = { vm.onAllCategories(AnalyticsViewModel.AllCategories.Expense) })
                }
            }
        }
    }

    if (blocks.incomeCategories) data.incomeCategories?.let { inc ->
        Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
            SectionTitle(if (state.periodType == PeriodType.Month) "Доходы" else "Доходы · ${shortPeriod(data)}")
            if (MetricStatus.fromWire(inc.status) != MetricStatus.Ready || inc.items.isEmpty()) {
                PfCard { Text("Доходов за ${shortPeriod(data)} нет", style = PfTheme.type.body, color = c.textMuted) }
            } else PfCard {
                inc.items.take(TOP_CATEGORIES).forEach { item ->
                    DataRow(label = item.categoryName, value = incomeMoney(item.amount), sublabel = item.note, tone = ValueTone.Positive, onClick = { onSearch(item.filters.toNav()) })
                }
                DataRow(label = "Всего", value = incomeMoney(data.tiles?.income?.value), total = true, tone = ValueTone.Positive, onClick = data.tiles?.income?.filters?.let { f -> { onSearch(f.toNav()) } })
                if (inc.items.size > TOP_CATEGORIES) PfLink("Все категории", onClick = { vm.onAllCategories(AnalyticsViewModel.AllCategories.Income) })
            }
        }
    }

    data.insights?.let { ins -> Insights(ins, data, state, vm, onSearch) }
}

private const val TOP_CATEGORIES = 5

@Composable
private fun Tiles(tiles: ru.finassist.pf.core.network.dto.AnalyticsTilesDto, data: AnalyticsDto, key: PeriodKey, stale: Boolean, state: AnalyticsViewModel.UiState, onSearch: (OperationsFilter) -> Unit) {
    val st = data.state
    val suffix = "${data.params?.date}.${state.transferMode.wire}"
    val periodShort = shortPeriod(data)
    Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpace.s3)) {
            Metric(tiles.expense, "Расходы", st, Modifier.weight(1f), note = periodShort, format = ::expenseMoney, introId = "tile.expense.$suffix", onClick = tiles.expense.filters?.let { f -> { onSearch(f.toNav()) } })
            Metric(tiles.income, "Доходы", st, Modifier.weight(1f), note = periodShort, format = ::incomeMoney, introId = "tile.income.$suffix", onClick = tiles.income.filters?.let { f -> { onSearch(f.toNav()) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpace.s3)) {
            Metric(tiles.balance, "Доходы минус расходы", st, Modifier.weight(1f), note = if (MetricStatus.fromWire(tiles.balance.status) == MetricStatus.Ready) balanceNote(tiles.balance.shareOfIncome) else null, format = { v -> if (v != null && v > 0) "+${money(v)}" else money(v) }, introId = "tile.balance.$suffix")
            Metric(tiles.dailyExpense, "Расходы в день", st, Modifier.weight(1f), note = dailyComparisonText(tiles.dailyExpense.comparison) ?: (if (state.periodType != PeriodType.Month) "в среднем за $periodShort" else null), format = ::money, introId = "tile.daily.$suffix")
        }
        tiles.forecast?.let { f ->
            val status = MetricStatus.fromWire(f.status)
            val staleLock = status == MetricStatus.Locked && LockReason.fromWire(f.lock?.reason) == LockReason.StaleData
            if (staleLock || (stale && status == MetricStatus.Locked)) {
                // Stale statement: the forecast tile shows the fact instead (review-states assumptions).
                StatTile(label = "Расходы", value = expenseMoney(tiles.expense.value), note = periodShort, introId = "tile.forecast.$suffix")
            } else {
                val label = if (key is PeriodKey.Year) "Прогноз на год" else "Прогноз расходов"
                val note = when (key) { is PeriodKey.Month -> "на ${RussianDates.monthNominative[key.yearMonth.monthValue - 1]}"; is PeriodKey.Year -> "на ${key.year} год"; else -> "на период" }
                Metric(f, label, st, Modifier.fillMaxWidth(), note = note, format = { v -> v?.let { "около ${money(it)}" } ?: "—" }, introId = "tile.forecast.$suffix")
            }
        }
    }
}

@Composable
private fun Metric(m: MetricDto, label: String, st: ru.finassist.pf.core.network.dto.AnalyticsStateDto?, modifier: Modifier, note: String?, format: (Long?) -> String, introId: String, onClick: (() -> Unit)? = null) {
    when (MetricStatus.fromWire(m.status)) {
        MetricStatus.Ready, MetricStatus.Tentative -> StatTile(label = label, value = format(m.value), modifier = modifier, note = note, introId = introId)
        MetricStatus.None -> StatTile(label = label, value = "—", modifier = modifier, note = note, introId = introId)
        MetricStatus.Locked -> StatTile(label = label, value = null, modifier = modifier, lockedText = lockText(m.lock, st))
    }
}

@Composable
private fun Insights(ins: ru.finassist.pf.core.network.dto.InsightsDto, data: AnalyticsDto, state: AnalyticsViewModel.UiState, vm: AnalyticsViewModel, onSearch: (OperationsFilter) -> Unit) {
    val c = PfTheme.colors
    val st = data.state
    val b = state.blocks
    val key = PeriodKey.parse(data.params?.date ?: "")
    if (!b.regularPayments && !b.notableSpending && !b.bankFees && !b.smallFrequent) return
    Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s3)) {
        SectionTitle("Регулярные платежи и заметные траты")
        if (b.regularPayments) ins.regularPayments.let { m ->
            when (val s = MetricStatus.fromWire(m.status)) {
                MetricStatus.Locked -> InsightCard(icon = "repeat", title = "Подписки и регулярные платежи", state = InsightState.Locked, lockedText = lockText(m.lock, st))
                MetricStatus.None -> InsightCard(icon = "repeat", title = "Подписки и регулярные платежи", state = InsightState.Empty, description = "Регулярных платежей не нашли")
                else -> {
                    val items = m.items.orEmpty()
                    val shown = items.take(3)
                    val rest = items.drop(3)
                    InsightCard(
                        icon = "repeat", title = "Подписки и регулярные платежи",
                        amount = m.value?.let { "${money(it)} в месяц" },
                        description = listOfNotNull(m.count?.let { "${plural(it.toLong(), "списание", "списания", "списаний")} каждый месяц" }, sinceText(m.basis?.range)).joinToString(" · "),
                        state = if (s == MetricStatus.Tentative) InsightState.Tentative else InsightState.Ready,
                        content = {
                            shown.forEach { item ->
                                val id = item.id
                                DataRow(
                                    label = item.title ?: "Платёж", sublabel = item.scheduleName, value = money(item.amount),
                                    onClick = { onSearch(item.filters.toNav()) },
                                )
                                if (id != null) PfLink(if (id in state.dismissing) "Убираем…" else "Не подписка", onClick = { if (id !in state.dismissing) vm.notSubscription(id) }, inline = true)
                            }
                            if (rest.isNotEmpty()) DataRow(label = "Ещё ${rest.size}", value = money(rest.sumOf { it.monthlyAmount ?: it.amount ?: 0L }), tone = ValueTone.Muted, divider = false)
                        },
                    )
                }
            }
        }
        if (b.notableSpending) ins.notableSpending.let { m ->
            when (MetricStatus.fromWire(m.status)) {
                MetricStatus.Locked -> InsightCard(icon = "trending-up", title = "Заметные траты", state = InsightState.Locked, lockedText = lockText(m.lock, st))
                MetricStatus.None -> InsightCard(icon = "trending-up", title = "Заметные траты", state = InsightState.Empty, description = "Ничего необычного ${periodGenitive(key)}")
                else -> m.items.orEmpty().forEach { item ->
                    InsightCard(
                        icon = item.categoryIcon ?: "trending-up", title = item.categoryName ?: "Категория",
                        amount = expenseMoney(item.amount), description = notableText(item.amount, item.typicalAmount, m.basis?.range),
                        onClick = { onSearch(item.filters.toNav()) },
                    )
                }
            }
        }
        if (b.bankFees) ins.bankFees.let { m ->
            when (MetricStatus.fromWire(m.status)) {
                MetricStatus.Locked -> InsightCard(icon = "percent", title = "Комиссии и проценты банку", state = InsightState.Locked, lockedText = lockText(m.lock, st))
                MetricStatus.None -> InsightCard(icon = "percent", title = "Комиссии и проценты банку", state = InsightState.Empty, description = "Комиссий ${periodGenitive(key)} не было")
                else -> InsightCard(
                    icon = "percent", title = "Комиссии и проценты банку", amount = expenseMoney(m.value),
                    description = listOfNotNull(periodGenitive(key), m.kindsName).joinToString(" · "),
                    onClick = m.filters?.let { f -> { onSearch(f.toNav()) } },
                )
            }
        }
        if (b.smallFrequent) ins.smallFrequent.let { m ->
            when (MetricStatus.fromWire(m.status)) {
                MetricStatus.Locked -> InsightCard(icon = "coffee", title = "Мелкие частые траты", state = InsightState.Locked, lockedText = lockText(m.lock, st))
                MetricStatus.None -> InsightCard(icon = "coffee", title = "Мелкие частые траты", state = InsightState.Empty, description = "Мелких частых трат не нашли")
                else -> InsightCard(
                    icon = "coffee", title = "Мелкие частые траты", amount = m.value?.let { "≈${money(it)}" },
                    description = "в месяц",
                    content = {
                        m.items.orEmpty().take(3).forEach { item ->
                            DataRow(label = item.title ?: "Траты", sublabel = smallFrequentItemText(item.count, item.averageAmount, item.yearlyAmount), value = item.monthlyAmount?.let { money(it) } ?: "", onClick = { onSearch(item.filters.toNav()) })
                        }
                    },
                )
            }
        }
        Text("Переводы между своими счетами не считаем ни в одном режиме", style = PfTheme.type.hint, color = c.textFaint, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
