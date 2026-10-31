package ru.finassist.pf.mock.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsPeriodInfo
import ru.finassist.pf.core.api.model.AnalyticsState
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.BlockLock
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.BlockValue
import ru.finassist.pf.core.api.model.ChartData
import ru.finassist.pf.core.api.model.ChartKind
import ru.finassist.pf.core.api.model.ChartNote
import ru.finassist.pf.core.api.model.ChartPoint
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.HeadingData
import ru.finassist.pf.core.api.model.InsightCardData
import ru.finassist.pf.core.api.model.InsightItem
import ru.finassist.pf.core.api.model.ListStyle
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.NoteTone
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.RankedItem
import ru.finassist.pf.core.api.model.RankedListData
import ru.finassist.pf.core.api.model.Tile
import ru.finassist.pf.core.api.model.TileGroupData
import ru.finassist.pf.core.api.model.ValueKind
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.countWithNoun
import java.time.YearMonth
import kotlin.math.roundToInt

/**
 * Lays out the computed screen as blocks (api.md 6.1 «Блоки экрана», FIN-4) — what the real `analytics` service does
 * (FIN-5): composition and order here, all texts from [AnalyticsServerTexts], only blocks the client [supports], the
 * order of an experiment variant, and a block that fails to build comes as `unavailable` instead of failing the screen.
 */
object AnalyticsComposer {

    /** `supports=type@v,…`: the newest version of each type the client draws; null — version 1 of every type. */
    fun parseSupports(raw: String?): Map<String, Int>? = raw?.split(',')?.mapNotNull { part ->
        val (type, v) = part.trim().split('@').takeIf { it.size == 2 } ?: return@mapNotNull null
        v.toIntOrNull()?.let { type to it }
    }?.toMap()

    /** `exp=key:variant,…`. */
    fun parseExperiments(raw: String?): Map<String, String> = raw?.split(',')?.mapNotNull { part ->
        part.trim().split(':').takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }?.toMap().orEmpty()

    /** Variant `b` of [ORDER_EXPERIMENT]: the categories go first. */
    const val ORDER_EXPERIMENT = "analytics_order"
    const val UNAVAILABLE_TEXT = "Не получилось посчитать — обновите экран позже"

    fun compose(
        s: AnalyticsSnapshot,
        supports: Map<String, Int>? = null,
        experiments: Map<String, String> = emptyMap(),
        failing: Set<String> = emptySet(),
        currency: String = Money.RUB,
    ): Analytics {
        if (!s.hasData) return Analytics(hasData = false)
        val params = s.params!!
        val period = s.period!!
        val type = params.period
        // Early state (docs/calc.md, section 4): one incomplete covered month in all history — no monthly chart.
        val early = s.state?.fullMonths == 0 &&
            s.monthlyChart?.lock?.let { it.reason == LockReason.NEED_MONTHS_WITH_DATA && it.available == 1 } == true

        fun guarded(id: String, build: () -> List<Block>): List<Block> =
            if (id in failing) listOf(unavailable(id)) else try {
                build()
            } catch (e: RuntimeException) {
                listOf(unavailable(id))
            }

        val tiles = guarded("tiles") { listOfNotNull(s.tiles?.let { tiles(it, s, period) }) }
        val chart = guarded("monthly_chart") { if (early) emptyList() else listOfNotNull(s.monthlyChart?.let { monthlyChart(it, s) }) }
        val expense = guarded("expense_categories") { listOfNotNull(s.expenseCategories?.let { expenseCategories(it, s, period) }) }
        val income = guarded("income_categories") { listOfNotNull(s.incomeCategories?.let { incomeCategories(it, type, period) }) }
        val insights = s.insights?.let { i ->
            listOf(Block.of(BlockType.HEADING, HeadingData.serializer(), HeadingData("Регулярные платежи и заметные траты"))) +
                guarded("regular_payments") { listOf(regularPayments(i.regularPayments, s)) } +
                guarded("notable_spending") { notableSpending(i.notableSpending, s) } +
                guarded("bank_fees") { listOf(bankFees(i.bankFees, period, s)) } +
                guarded("small_frequent") { listOf(smallFrequent(i.smallFrequent, s)) }
        }.orEmpty()
        val balance = guarded("balance") { if (type == PeriodTypeCode.YEAR) emptyList() else listOfNotNull(balance(s)) }

        val ordered = if (experiments[ORDER_EXPERIMENT] == "b") {
            expense + income + tiles + chart + insights + balance
        } else {
            tiles + chart + expense + income + insights + balance
        }
        val compatible = ordered.filter { b -> supports == null || (supports[b.type.code] ?: 0) >= b.v }
        return Analytics(
            hasData = true, params = params, period = period, navigation = s.navigation, currency = currency,
            blocks = compatible, state = s.state,
        )
    }

    private fun unavailable(id: String) = Block(
        id = id, type = typeOf(id), status = BlockStatus.UNAVAILABLE, title = titleOf(id), emptyText = UNAVAILABLE_TEXT,
        altText = UNAVAILABLE_TEXT,
        data = when (id) {
            "regular_payments", "notable_spending", "bank_fees", "small_frequent" ->
                Block.of(BlockType.INSIGHT_CARD, InsightCardData.serializer(), InsightCardData(iconOf(id), titleOf(id) ?: "")).data
            "tiles" -> Block.of(BlockType.TILE_GROUP, TileGroupData.serializer(), TileGroupData(emptyList())).data
            else -> null
        },
    )

    private fun typeOf(id: String) = when (id) {
        "tiles" -> BlockType.TILE_GROUP
        "monthly_chart", "balance" -> BlockType.CHART
        "expense_categories", "income_categories" -> BlockType.RANKED_LIST
        else -> BlockType.INSIGHT_CARD
    }

    private fun titleOf(id: String) = when (id) {
        "monthly_chart" -> "Расходы по месяцам"
        "balance" -> "Доходы минус расходы"
        "expense_categories" -> "Расходы по категориям"
        "income_categories" -> "Доходы"
        "regular_payments" -> "Подписки и регулярные платежи"
        "notable_spending" -> "Заметные траты"
        "bank_fees" -> "Комиссии и проценты банку"
        "small_frequent" -> "Мелкие частые траты"
        else -> null
    }

    private fun iconOf(id: String) = when (id) {
        "regular_payments" -> "repeat"
        "bank_fees" -> "percent"
        "small_frequent" -> "coffee"
        else -> "bar-chart"
    }

    // ---- primitives ----

    private fun amount(m: Money?) = m?.minor?.toDouble()

    private fun open(f: OperationsFilter?) = f?.let { BlockAction(ActionType.OPEN_OPERATIONS, filter = it) }

    private fun status(m: MetricLike): BlockStatus = when (m.status.effective) {
        MetricStatus.READY -> BlockStatus.READY
        MetricStatus.TENTATIVE -> BlockStatus.TENTATIVE
        MetricStatus.NONE -> BlockStatus.NONE
        else -> BlockStatus.LOCKED
    }

    private fun lock(m: MetricLike, state: AnalyticsState?): BlockLock? = m.lock?.let { l ->
        BlockLock(l.reason, AnalyticsServerTexts.lock(l, state), l.required, l.available, l.availableFrom)
    } ?: if (status(m) == BlockStatus.LOCKED) {
        BlockLock(ru.finassist.pf.core.api.model.LockReason.UNKNOWN, AnalyticsServerTexts.lock(null, state))
    } else {
        null
    }

    private fun periodCaption(p: AnalyticsPeriodInfo): String {
        val from = p.dataFrom?.toLocalDate() ?: p.range.from.toLocalDate()
        val to = p.dataTo?.toLocalDate() ?: RussianDates.lastDayOf(p.range)
        return RussianDates.dayRange(from, to)
    }

    private fun tile(
        id: String, title: String, m: Metric, s: AnalyticsSnapshot, caption: String? = null,
        approx: Boolean = false, showPlus: Boolean = false, action: BlockAction? = null,
    ): Tile = if (!m.isReady) {
        Tile(id, title, status = BlockStatus.LOCKED, lock = lock(m, s.state)?.let { value ->
            if (id == "forecast" && m.lock?.reason == LockReason.NEED_FULL_MONTHS) {
                value.copy(text = AnalyticsServerTexts.forecastLock(m.lock, s.state, s.forecastHistory))
            } else value
        })
    } else {
        Tile(
            id, title, status = status(m), value = amount(m.value), valueKind = ValueKind.AMOUNT, approx = approx,
            showPlus = showPlus, caption = caption, action = action,
        )
    }

    private fun tiles(t: AnalyticsTiles, s: AnalyticsSnapshot, period: AnalyticsPeriodInfo): Block {
        if (period.coverage == Coverage.NO_DATA) return Block.of(
            BlockType.TILE_GROUP, TileGroupData.serializer(), TileGroupData(emptyList()),
        ).copy(id = "tiles", status = BlockStatus.NONE, emptyText = "За этот период данных нет", altText = "За этот период данных нет")
        val params = s.params!!
        val share = AnalyticsServerTexts.share(t.balance)
        val balance = tile(
            "balance", "Доходы минус расходы", t.balance, s,
            caption = if (t.balance.shareOfIncome != null && share.endsWith("дохода")) "$share · по выписке" else share,
        )
        val expense = tile("expense", "Расходы", t.expense, s, caption = periodCaption(period), action = open(t.expense.filters))
        val forecast = t.forecast?.takeUnless { it.lock?.reason == LockReason.STALE_DATA }?.let { f ->
            tile("forecast", AnalyticsServerTexts.forecastLabel(params.period), f, s, caption = AnalyticsServerTexts.forecastNote(params.date), approx = true)
        }
        val dailyCaption = if (params.period == PeriodTypeCode.MONTH) {
            AnalyticsServerTexts.dailyComparison(t.dailyExpense.comparison)
        } else {
            AnalyticsServerTexts.dailyAverageOver(period)
        }
        val daily = tile("daily_expense", "Расходы в день", t.dailyExpense, s, caption = dailyCaption)
        val income = tile("income", "Доходы", t.income, s, showPlus = true, action = open(t.income.filters))
        val all = listOf(balance, expense, daily, income) + listOfNotNull(forecast)
        return Block.of(BlockType.TILE_GROUP, TileGroupData.serializer(), TileGroupData(all)).copy(
            id = "tiles",
            altText = all.joinToString(", ") { tl -> tl.title + (tl.value?.let { " " + Money(it.toLong()).format() } ?: " — " + (tl.lock?.text ?: "")) },
        )
    }

    /** A bar of a chart; `data_from` / `data_to` only for an incomplete month (api.md 6.1, docs/calc.md 4). */
    private fun point(p: MonthlyPoint, value: Double?): ChartPoint = ChartPoint(
        range = p.range, value = value, coverage = p.coverage,
        dataFrom = p.dataFrom.takeIf { p.coverage == Coverage.PARTIAL }, dataTo = p.dataTo.takeIf { p.coverage == Coverage.PARTIAL },
        note = AnalyticsServerTexts.barNote(p),
    )

    private fun highlight(points: List<MonthlyPoint>, s: AnalyticsSnapshot): Int? {
        val selected = points.indexOfFirst { it.month == s.params!!.date.value }
        return when {
            selected >= 0 -> selected
            s.params!!.period == PeriodTypeCode.YEAR -> null
            else -> points.lastIndex
        }
    }

    private fun monthlyChart(c: MonthlyChart, s: AnalyticsSnapshot): Block {
        val title = "Расходы по месяцам"
        if (!c.isReady || c.points.isNullOrEmpty()) {
            return Block(id = "monthly_chart", type = BlockType.CHART, status = BlockStatus.LOCKED, lock = lock(c, s.state), title = title, altText = "График по месяцам")
        }
        val params = s.params!!
        val points = c.points!!
        val expense = s.tiles?.expense
        val main = if (params.period == PeriodTypeCode.MONTH) {
            expense?.value?.let { AnalyticsServerTexts.comparison(it, expense.comparison, params.period, params.date) }
        } else {
            AnalyticsServerTexts.peakMonth(points)
        }
        val secondary = if (params.period == PeriodTypeCode.YEAR) {
            AnalyticsServerTexts.yearChartNote(points, s.period?.isCurrent == true)
        } else {
            AnalyticsServerTexts.typical(expense?.typical)
        }
        val data = ChartData(
            kind = ChartKind.BAR, valueKind = ValueKind.AMOUNT,
            points = points.map { point(it, amount(it.expense)) },
            highlightIndex = highlight(points, s),
            notes = listOfNotNull(main?.let { ChartNote(it, NoteTone.PRIMARY) }, secondary?.let { ChartNote(it, NoteTone.MUTED) }),
        )
        // The lines under the chart are always there, an empty list too (docs/calc.md, section 10).
        return withEmptyList(Block.of(BlockType.CHART, ChartData.serializer(), data), "notes").copy(
            id = "monthly_chart", title = title,
            altText = "$title: " + points.joinToString(", ") { p ->
                RussianDates.monthNominative(YearMonth.parse(p.month).month) + " " + (p.expense?.format() ?: "нет данных")
            },
        )
    }

    private fun expenseCategories(b: CategoryBreakdown, s: AnalyticsSnapshot, period: AnalyticsPeriodInfo): Block {
        val title = "Расходы по категориям"
        if (b.status != BreakdownStatus.READY || b.items.isEmpty()) {
            return Block(id = "expense_categories", type = BlockType.RANKED_LIST, status = BlockStatus.NONE, title = title, emptyText = "Расходов за ${periodCaption(period)} нет", altText = "Расходов нет")
        }
        val total = s.tiles?.expense?.value?.let {
            BlockValue(
                label = "Всего · ${AnalyticsServerTexts.totalCaption(s.params!!.period, period)}", value = it.minor.toDouble(),
                valueKind = ValueKind.AMOUNT, action = open(s.tiles.expense.filters),
            )
        }
        val data = RankedListData(
            total = total,
            items = b.items.map { i ->
                RankedItem(
                    id = i.categoryId, icon = i.categoryIcon, title = i.categoryName, note = i.note, value = i.amount.minor.toDouble(),
                    valueKind = ValueKind.AMOUNT, share = i.share, action = open(i.filters),
                )
            },
            visibleCount = TOP_CATEGORIES, moreLabel = "Все категории",
        )
        return Block.of(BlockType.RANKED_LIST, RankedListData.serializer(), data).copy(
            id = "expense_categories", title = title,
            altText = "$title: " + b.items.joinToString(", ") { "${it.categoryName} ${it.amount.format()}" },
        )
    }

    private fun incomeCategories(b: CategoryBreakdown, type: PeriodTypeCode, period: AnalyticsPeriodInfo): Block {
        val months = if (type != PeriodTypeCode.MONTH) {
            val from = period.dataFrom?.let(YearMonth::from)
            val to = period.dataTo?.let { YearMonth.from(it.minusSeconds(1)) }
            if (from != null && to != null) " · ${RussianDates.monthRange(from, to)}" else ""
        } else {
            ""
        }
        val title = "Доходы$months"
        if (b.status != BreakdownStatus.READY || b.items.isEmpty()) {
            return Block(id = "income_categories", type = BlockType.RANKED_LIST, status = BlockStatus.NONE, title = title, emptyText = "Доходов за ${periodCaption(period)} нет", altText = "Доходов нет")
        }
        val data = RankedListData(
            items = b.items.map { i ->
                RankedItem(
                    id = i.categoryId, icon = i.categoryIcon, title = i.categoryName, note = i.note.takeIf { type == PeriodTypeCode.MONTH },
                    value = i.amount.minor.toDouble(), valueKind = ValueKind.AMOUNT, share = i.share, showPlus = true, action = open(i.filters),
                )
            },
            style = ListStyle.PLAIN,
        )
        return Block.of(BlockType.RANKED_LIST, RankedListData.serializer(), data).copy(
            id = "income_categories", title = title,
            altText = "$title: " + b.items.joinToString(", ") { "${it.categoryName} ${it.amount.format()}" },
        )
    }

    private fun card(id: String, m: MetricLike, data: InsightCardData, s: AnalyticsSnapshot, emptyText: String? = null, action: BlockAction? = null): Block {
        val st = status(m)
        // A locked or empty card carries only its icon and title; the lock or `empty_text` says the rest.
        val shown = if (st.hasData) data else InsightCardData(icon = data.icon, title = data.title)
        return Block.of(BlockType.INSIGHT_CARD, InsightCardData.serializer(), shown).copy(
            id = id, status = st, lock = if (st == BlockStatus.LOCKED) lock(m, s.state) else null, emptyText = emptyText.takeIf { st == BlockStatus.NONE },
            action = action.takeIf { st.hasData },
            altText = listOfNotNull(data.title, data.value?.let { Money(it.toLong()).format() }, data.description ?: emptyText).joinToString(": "),
        )
    }

    private fun basisMonths(m: MetricLike): String? =
        m.basis?.range?.let { r -> RussianDates.monthRange(YearMonth.from(r.from), YearMonth.from(r.to.minusDays(1))) }

    private fun regularPayments(r: RegularPaymentsCard, s: AnalyticsSnapshot): Block {
        val shown = r.items.orEmpty().take(3)
        val rest = r.items.orEmpty().drop(3)
        val description = listOfNotNull(
            "в месяц",
            r.count?.let { countWithNoun(it, "списание", "списания", "списаний") + " каждый месяц" + (r.basis?.range?.let { b -> " с ${RussianDates.monthGenitive(b.from.month)}" } ?: "") },
        ).joinToString(" · ")
        val data = InsightCardData(
            icon = "repeat", title = "Подписки и регулярные платежи", value = amount(r.value), valueKind = ValueKind.AMOUNT,
            description = description.takeIf { r.isReady },
            items = shown.map { p ->
                InsightItem(
                    p.title, sublabel = p.scheduleName, value = p.amount.minor.toDouble(), valueKind = ValueKind.AMOUNT,
                    action = open(p.filters),
                    button = BlockAction(ActionType.DISMISS_REGULAR_PAYMENT, paymentId = p.id, label = "Не подписка"),
                )
            },
            itemsMore = rest.takeIf { it.isNotEmpty() }?.let { list ->
                BlockValue(label = "Ещё ${list.size}", value = list.fold(Money.ZERO) { acc, p -> acc + p.monthlyAmount }.minor.toDouble(), valueKind = ValueKind.AMOUNT)
            },
        )
        val empty = listOfNotNull("Регулярных платежей не нашли", basisMonths(r)?.let { "за $it" }).joinToString(" ")
        return card("regular_payments", r, data, s, emptyText = empty, action = open(r.filters))
    }

    private fun notableSpending(n: NotableSpendingCard, s: AnalyticsSnapshot): List<Block> {
        val items = n.items.orEmpty()
        if (n.isReady && n.status.effective == MetricStatus.READY && items.isNotEmpty()) {
            // One card per category («Заправки 7 200 ₽ · В 2,3 раза больше обычного…»).
            return items.map { item ->
                card(
                    "notable_spending", n,
                    InsightCardData(
                        icon = item.categoryIcon, title = item.categoryName, value = item.amount.minor.toDouble(), valueKind = ValueKind.AMOUNT,
                        description = AnalyticsServerTexts.notable(item.amount, item.typicalAmount, n.basis?.range),
                    ),
                    s, action = open(item.filters),
                )
            }
        }
        val empty = "Заметных трат не нашли — расходы по категориям в пределах обычного" + (basisMonths(n)?.let { " за $it" } ?: "")
        return listOf(card("notable_spending", n, InsightCardData(icon = "bar-chart", title = "Заметные траты"), s, emptyText = empty))
    }

    private fun bankFees(f: BankFeesCard, period: AnalyticsPeriodInfo, s: AnalyticsSnapshot): Block {
        // Month names have the same accusative and nominative form: «за сентябрь».
        val forPeriod = "за ${RussianDates.monthNominative(period.range.from.month)}"
        val data = InsightCardData(
            icon = "percent", title = "Комиссии и проценты банку", value = amount(f.value), valueKind = ValueKind.AMOUNT,
            description = listOfNotNull(forPeriod, f.kindsName).joinToString(" · ").takeIf { f.isReady },
        )
        return card("bank_fees", f, data, s, emptyText = "Комиссий $forPeriod нет", action = open(f.filters))
    }

    private fun smallFrequent(sf: SmallFrequentCard, s: AnalyticsSnapshot): Block {
        val groups = sf.items.orEmpty()
        val first = groups.firstOrNull()
        val data = InsightCardData(
            icon = "coffee", title = "Мелкие частые траты", value = amount(sf.value), valueKind = ValueKind.AMOUNT, approx = true,
            description = first?.let {
                val selected = YearMonth.from(s.period!!.range.from)
                AnalyticsServerTexts.smallFrequent(it.title, it.count, it.averageAmount, it.yearlyAmount, sf.month, selected)
            },
            // The first group is the description; any further groups stay as rows.
            items = groups.drop(1).map { g ->
                InsightItem(
                    g.title, sublabel = "${countWithNoun(g.count, "раз", "раза", "раз")} в месяц · по ${g.averageAmount.format()}",
                    value = g.monthlyAmount.minor.toDouble(), valueKind = ValueKind.AMOUNT, valueSuffix = "в месяц", action = open(g.filters),
                )
            },
        )
        // The card shows the first group, so it opens that group.
        val block = card("small_frequent", sf, data, s, emptyText = "Мелких частых трат не нашли", action = open(first?.filters))
        // One group: `items` is an empty list on the wire, not absent (docs/calc.md, section 10).
        return if (block.status.hasData) withEmptyList(block, "items") else block
    }

    /** Writes [key] as `[]` when the model left an empty list out of the block's data (its default is not encoded). */
    private fun withEmptyList(block: Block, key: String): Block {
        val data = block.data ?: return block
        return if (key in data) block else block.copy(data = JsonObject(data + (key to JsonArray(emptyList()))))
    }

    private fun balance(s: AnalyticsSnapshot): Block? {
        val balance = s.tiles?.balance?.takeIf { it.isReady } ?: return null
        val points = s.monthlyChart?.takeIf { it.isReady }?.points.orEmpty()
        val withChart = points.isNotEmpty()
        val title = if (withChart) "Доходы минус расходы по месяцам" else "Доходы минус расходы"
        val data = ChartData(
            kind = ChartKind.BAR, valueKind = ValueKind.PERCENT,
            // Share of income kept per month; a month where more was spent than earned goes below the baseline.
            points = points.map { point(it, AnalyticsServerTexts.balanceShare(it)) },
            highlightIndex = if (withChart) (points.indexOfFirst { it.month == s.params!!.date.value }.takeIf { it >= 0 } ?: points.lastIndex) else null,
            showValues = true,
            summary = balance.value?.let { BlockValue(value = it.minor.toDouble(), valueKind = ValueKind.AMOUNT) },
            caption = AnalyticsServerTexts.balanceExplained(balance, withChart),
        )
        return Block.of(BlockType.CHART, ChartData.serializer(), data).copy(
            id = "balance", title = title,
            altText = "$title: " + (balance.value?.format() ?: "") + points.joinToString("") { p ->
                AnalyticsServerTexts.balanceShare(p)?.let { v -> ", ${RussianDates.monthNominative(YearMonth.parse(p.month).month)} ${(v * 100).roundToInt()}%" } ?: ""
            },
        )
    }

    private const val TOP_CATEGORIES = 4

}
