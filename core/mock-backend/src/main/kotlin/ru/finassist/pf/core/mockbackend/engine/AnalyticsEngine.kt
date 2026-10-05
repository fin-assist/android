package ru.finassist.pf.core.mockbackend.engine

import ru.finassist.pf.core.network.dto.*
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Computes the «Аналитика» response from the ledger on demand (the data is small; no pre-aggregation needed in
 * a mock). Thresholds follow api.md §6 and the review decisions; the client never knows them.
 */
class AnalyticsEngine(private val ledger: Ledger, private val zone: ZoneId, private val wire: Wire) {

    private val staleAfterDays = 14L

    data class Period(val type: String, val key: String, val from: LocalDate, val toInclusive: LocalDate) {
        val months: List<YearMonth> get() = generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { it <= YearMonth.from(toInclusive) }.toList()
    }

    fun period(type: String, key: String): Period? = when (type) {
        "month" -> parseMonth(key)?.let { Period("month", wire.month(it), it.atDay(1), it.atEndOfMonth()) }
        "quarter" -> {
            val (year, q) = parseQuarter(key) ?: parseMonth(key)?.let { it.year to ((it.monthValue - 1) / 3 + 1) } ?: parseYear(key)?.let { it to 1 } ?: return null
            val start = YearMonth.of(year, (q - 1) * 3 + 1)
            Period("quarter", "$year-Q$q", start.atDay(1), start.plusMonths(2).atEndOfMonth())
        }
        "year" -> {
            val year = parseYear(key) ?: parseMonth(key)?.year ?: parseQuarter(key)?.first ?: return null
            Period("year", year.toString(), LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
        }
        else -> null
    }

    private fun parseMonth(k: String) = Regex("""^(\d{4})-(\d{2})$""").matchEntire(k)?.let { YearMonth.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    private fun parseQuarter(k: String) = Regex("""^(\d{4})-Q([1-4])$""").matchEntire(k)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    private fun parseYear(k: String) = Regex("""^(\d{4})$""").matchEntire(k)?.groupValues?.get(1)?.toInt()

    private fun today(now: OffsetDateTime) = now.atZoneSameInstant(zone).toLocalDate()

    /** Periods with at least one operation, newest first. */
    fun periodsWithData(type: String, now: OffsetDateTime): List<AnalyticsPeriodItemDto> {
        val coverage = Coverage(ledger.uploads, zone, today(now))
        // "Periods with data" means periods with at least one operation, not merely covered by a file.
        val months = ledger.operations.values.map { YearMonth.from(wire.localDate(it.tx.postedAt)) }.distinct().sorted()
        val keys = months.map { ym ->
            when (type) {
                "quarter" -> "${ym.year}-Q${(ym.monthValue - 1) / 3 + 1}"
                "year" -> ym.year.toString()
                else -> wire.month(ym)
            }
        }.distinct()
        return keys.mapNotNull { key -> period(type, key) }.sortedByDescending { it.from }.map { p ->
            AnalyticsPeriodItemDto(p.key, wire.dayRange(p.from, p.toInclusive), coverageOf(p, coverage, now))
        }
    }

    private fun coverageOf(p: Period, coverage: Coverage, now: OffsetDateTime): String {
        val lastDay = minOf(p.toInclusive, today(now))
        if (coverage.dataFrom == null || p.from > coverage.dataTo!! || lastDay < coverage.dataFrom!!) return "no_data"
        val gaps = coverage.gaps(p.from, lastDay)
        return if (gaps.isEmpty() && p.toInclusive < today(now)) "complete" else "partial"
    }

    fun analytics(type: String, dateKey: String?, transferMode: String, now: OffsetDateTime): AnalyticsDto {
        val view = AccountingView(ledger, zone, transferMode != "without")
        val coverage = Coverage(ledger.uploads, zone, today(now))
        if (ledger.operations.isEmpty() || coverage.isEmpty) return AnalyticsDto(hasData = false)

        val periods = periodsWithData(type, now)
        val p = (dateKey?.let { period(type, it) } ?: periods.firstOrNull()?.let { period(type, it.key) }) ?: return AnalyticsDto(hasData = false)
        val tdy = today(now)
        val lastDay = minOf(p.toInclusive, tdy)
        val lines = view.inRange(p.from, p.toInclusive)
        val expense = view.expense(lines)
        val income = view.income(lines)
        val isCurrent = !p.toInclusive.isBefore(tdy) && !p.from.isAfter(tdy)
        val dataFrom = lines.minOfOrNull { it.op.tx.postedAt }
        val dataTo = lines.maxOfOrNull { it.op.tx.postedAt }
        val fullMonths = coverage.fullMonths()
        val lastOp = ledger.operations.values.maxOfOrNull { it.tx.postedAt }
        val stale = lastOp == null || ChronoUnit.DAYS.between(wire.localDate(lastOp), tdy) > staleAfterDays
        val periodFilterBase = OperationsFilterDto(from = wire.dayStartStr(p.from), to = wire.dayStartStr(p.toInclusive.plusDays(1)), transferMode = transferMode)

        // ---- tiles
        val prev = previousPeriod(p)
        val prevFull = prev != null && prev.months.all { it in fullMonths }
        val dayOfPeriod = ChronoUnit.DAYS.between(p.from, lastDay)
        val comparison = if (prevFull && prev != null) {
            val prevLines = view.inRange(prev.from, minOf(prev.toInclusive, prev.from.plusDays(dayOfPeriod)))
            MetricDto(status = "ready", value = view.expense(prevLines), range = wire.dayRange(prev.from, minOf(prev.toInclusive, prev.from.plusDays(dayOfPeriod))))
        } else MetricDto(status = "locked", lock = MetricLockDto("need_full_months", 1, if (prevFull) 1 else 0))
        val typicalBase = fullPreviousPeriods(p, fullMonths)
        val typical = if (typicalBase.size >= 2) {
            val avg = typicalBase.map { view.expense(view.inRange(it.from, it.toInclusive)) }.average().roundToLong()
            MetricDto(status = "ready", value = avg, basis = MetricBasisDto(wire.dayRange(typicalBase.first().from, typicalBase.last().toInclusive), typicalBase.sumOf { it.months.size }))
        } else MetricDto(status = "locked", lock = MetricLockDto("need_full_months", 2, typicalBase.size))
        val expenseTile = MetricDto(status = "ready", value = expense, filters = periodFilterBase.copy(kind = OperationKindFilterDto.expense), comparison = comparison, typical = typical)
        val incomeTile = MetricDto(status = "ready", value = income, filters = periodFilterBase.copy(kind = OperationKindFilterDto.income))
        val balanceTile = MetricDto(status = "ready", value = income - expense, shareOfIncome = if (income > 0) (income - expense).toDouble() / income else null)
        val daysWithData = if (dataFrom != null && dataTo != null) ChronoUnit.DAYS.between(wire.localDate(dataFrom), wire.localDate(dataTo)) + 1 else 0
        val daily = if (daysWithData > 0) expense / daysWithData else 0
        val dailyComparison = if (prevFull && prev != null) {
            val prevLines = view.inRange(prev.from, prev.toInclusive)
            val prevDays = ChronoUnit.DAYS.between(prev.from, prev.toInclusive) + 1
            MetricDto(status = "ready", value = view.expense(prevLines) / prevDays, range = wire.dayRange(prev.from, prev.toInclusive))
        } else MetricDto(status = "locked", lock = MetricLockDto("need_full_months", 1, 0))
        val dailyTile = MetricDto(status = "ready", value = daily, comparison = dailyComparison)
        val forecast = if (!isCurrent) null else forecast(p, view, fullMonths, expense, stale, tdy)

        // ---- categories
        fun breakdown(isExpense: Boolean, total: Long): CategoryBreakdownDto {
            val sel = lines.filter { it.isExpense == isExpense }
            if (sel.isEmpty()) return CategoryBreakdownDto("none", emptyList())
            val groups = sel.groupBy { it.categoryId }
            val items = groups.map { (catId, g) ->
                val amount = g.sumOf { it.signedAmount }
                val cat = catId?.let { Categories.byId(it) }
                CategoryAmountDto(
                    categoryId = catId,
                    categoryName = cat?.name ?: "Без категории",
                    categoryIcon = cat?.icon ?: "circle",
                    note = if (catId == Categories.TRANSFERS) "${g.size} " + plural(g.size.toLong(), "перевод", "перевода", "переводов") else null,
                    amount = amount,
                    share = if (total > 0 && amount > 0) amount.toDouble() / total else null,
                    operationCount = g.size,
                    filters = periodFilterBase.copy(categoryId = catId, kind = if (isExpense) OperationKindFilterDto.expense else OperationKindFilterDto.income, selection = if (catId == null) Selections.UNCATEGORIZED else null, selectionName = if (catId == null) "Без категории" else null),
                )
            }.sortedByDescending { it.amount }
            return CategoryBreakdownDto("ready", items)
        }
        val expenseCategories = breakdown(true, expense)
        val incomeCategories = breakdown(false, income)

        // ---- monthly chart
        val chartMonths = when (p.type) {
            "month" -> (5 downTo 0).map { YearMonth.from(p.from).minusMonths(it.toLong()) }
            else -> p.months
        }
        val monthsWithData = coverage.monthsWithData()
        val monthlyChart = if (monthsWithData.size < 2) MonthlyChartDto("locked", MetricLockDto("need_months_with_data", 2, monthsWithData.size))
        else MonthlyChartDto("ready", points = chartMonths.map { ym -> monthlyPoint(ym, view, coverage, tdy) })

        // ---- insights (month only)
        val insights = if (p.type == "month") insights(p, view, fullMonths, lines, periodFilterBase) else null

        val periodGaps = if (dataFrom != null) coverage.gaps(maxOf(p.from, coverage.dataFrom!!), minOf(lastDay, coverage.dataTo!!)) else emptyList()
        val coverageCode = when {
            lines.isEmpty() && dataFrom == null -> "no_data"
            periodGaps.isEmpty() && !isCurrent && coverage.covers(p.from) && coverage.covers(p.toInclusive) -> "complete"
            else -> "partial"
        }
        val idx = periods.indexOfFirst { it.key == p.key }
        return AnalyticsDto(
            hasData = true,
            params = AnalyticsParamsDto(p.type, p.key, transferMode),
            period = AnalyticsPeriodInfoDto(
                range = wire.dayRange(p.from, p.toInclusive),
                isCurrent = isCurrent,
                coverage = coverageCode,
                dataFrom = dataFrom?.let(wire::time),
                dataTo = dataTo?.let(wire::time),
                gaps = periodGaps.map { wire.dayRange(it.start, it.endInclusive) },
            ),
            navigation = AnalyticsNavigationDto(
                previous = periods.getOrNull(idx + 1)?.key,
                next = if (idx > 0) periods.getOrNull(idx - 1)?.key else null,
            ),
            tiles = AnalyticsTilesDto(expenseTile, incomeTile, balanceTile, dailyTile, forecast),
            expenseCategories = expenseCategories,
            incomeCategories = incomeCategories,
            monthlyChart = monthlyChart,
            insights = insights,
            state = AnalyticsStateDto(
                stale = stale,
                lastOperationAt = lastOp?.let(wire::time),
                dataFrom = wire.dayStartStr(coverage.dataFrom!!),
                dataTo = wire.time(lastOp ?: now),
                fullMonths = fullMonths.size,
                unreadLinesCount = ledger.uploads.flatMap { it.unreadLines }.count { l -> l.date?.let { d -> wire.localDate(d) in p.from..p.toInclusive } ?: false },
                calculatedAt = wire.time(ledger.lastUploadChangeAt ?: now),
                recalculating = false,
            ),
        )
    }

    private fun previousPeriod(p: Period): Period? = when (p.type) {
        "month" -> YearMonth.from(p.from).minusMonths(1).let { period("month", wire.month(it)) }
        "quarter" -> YearMonth.from(p.from).minusMonths(3).let { period("quarter", wire.month(it)) }
        "year" -> period("year", (p.from.year - 1).toString())
        else -> null
    }

    /** Previous periods of the same type consisting only of full months, oldest first (up to 12). */
    private fun fullPreviousPeriods(p: Period, fullMonths: List<YearMonth>): List<Period> {
        val out = mutableListOf<Period>()
        var cur = previousPeriod(p)
        while (cur != null && out.size < 12) {
            if (cur.months.all { it in fullMonths }) out += cur else if (out.isNotEmpty()) break
            cur = previousPeriod(cur)
            if (cur != null && fullMonths.isNotEmpty() && cur.toInclusive < fullMonths.first().atDay(1)) break
        }
        return out.reversed()
    }

    private fun forecast(p: Period, view: AccountingView, fullMonths: List<YearMonth>, expenseSoFar: Long, stale: Boolean, tdy: LocalDate): MetricDto = when (p.type) {
        "month" -> when {
            stale -> MetricDto(status = "locked", lock = MetricLockDto("stale_data"))
            tdy.dayOfMonth < 10 -> MetricDto(status = "locked", lock = MetricLockDto("too_early_in_month", availableFrom = wire.dayStartStr(tdy.withDayOfMonth(10))))
            else -> {
                val elapsed = tdy.dayOfMonth
                val total = YearMonth.from(p.from).lengthOfMonth()
                MetricDto(status = "ready", value = expenseSoFar * total / elapsed)
            }
        }
        else -> {
            val need = if (p.type == "year") 12 else 3
            val history = fullMonths.filter { it < YearMonth.from(p.from).plusMonths(0) || it in p.months }.takeLast(need)
            if (history.size < need) MetricDto(status = "locked", lock = MetricLockDto("need_full_months", need, history.size))
            else {
                val avg = history.map { view.expense(view.inMonth(it)) }.average()
                val fullInPeriod = p.months.filter { it in fullMonths }
                val remaining = p.months.size - fullInPeriod.size
                val done = fullInPeriod.sumOf { view.expense(view.inMonth(it)) }
                MetricDto(status = "ready", value = done + (avg * remaining).roundToLong())
            }
        }
    }

    private fun monthlyPoint(ym: YearMonth, view: AccountingView, coverage: Coverage, tdy: LocalDate): MonthlyPointDto {
        val lines = view.inMonth(ym)
        val has = coverage.hasData(ym)
        val last = minOf(ym.atEndOfMonth(), tdy)
        val gaps = if (has) coverage.gaps(maxOf(ym.atDay(1), coverage.dataFrom!!), minOf(last, coverage.dataTo!!)) else emptyList()
        val dataFrom = lines.minOfOrNull { it.op.tx.postedAt }
        val dataTo = lines.maxOfOrNull { it.op.tx.postedAt }
        return MonthlyPointDto(
            month = wire.month(ym),
            range = wire.monthRange(ym),
            expense = if (has) view.expense(lines) else null,
            income = if (has) view.income(lines) else null,
            coverage = when { !has -> "no_data"; coverage.isFullMonth(ym) -> "complete"; else -> "partial" },
            dataFrom = dataFrom?.let(wire::time),
            dataTo = dataTo?.let(wire::time),
            gaps = gaps.map { wire.dayRange(it.start, it.endInclusive) },
        )
    }

    // ---- insights ----------------------------------------------------------------------------------------------

    private fun insights(p: Period, view: AccountingView, fullMonths: List<YearMonth>, lines: List<AccountingView.Line>, base: OperationsFilterDto): InsightsDto {
        val ym = YearMonth.from(p.from)
        val history = fullMonths.filter { it <= ym }
        val basis = history.takeIf { it.isNotEmpty() }?.let { MetricBasisDto(wire.dayRange(it.first().atDay(1), it.last().atEndOfMonth()), it.size) }

        // Regular payments: same merchant, similar amount, every month for the last N full months (+ current).
        val regular = if (history.size < 2) MetricDto(status = "locked", lock = MetricLockDto("need_full_months", 2, history.size)) else {
            val window = history.takeLast(6)
            val debits = view.lines.filter { it.isExpense && it.signedAmount > 0 && YearMonth.from(it.day) in window }
            val items = debits.groupBy { it.op.tx.name.lowercase().trim() }.mapNotNull { (key, g) ->
                val byMonth = g.groupBy { YearMonth.from(it.day) }
                if (byMonth.size < window.size || window.any { it !in byMonth }) return@mapNotNull null
                val amounts = byMonth.values.map { m -> m.maxOf { it.signedAmount } }
                val median = amounts.sorted()[amounts.size / 2]
                if (amounts.any { abs(it - median) > median * 0.15 }) return@mapNotNull null
                val days = byMonth.values.map { m -> m.maxBy { it.signedAmount }.day.dayOfMonth }
                val medianDay = days.sorted()[days.size / 2]
                if (days.any { abs(it - medianDay) > 3 }) return@mapNotNull null
                val id = "rp_" + Selections.merchantKey(key)
                if (id in ledger.dismissedRegularPayments) return@mapNotNull null
                val sample = g.first().op
                MetricItemDto(
                    id = id, title = sample.tx.name, amount = median, monthlyAmount = median,
                    scheduleName = "$medianDay-го числа", categoryId = sample.categoryId,
                    filters = OperationsFilterDto(selection = Selections.regularPayment(id), selectionName = sample.tx.name, transferMode = base.transferMode, from = wire.dayStartStr(window.first().atDay(1)), to = wire.dayStartStr(ym.plusMonths(1).atDay(1))),
                )
            }.sortedByDescending { it.amount }
            if (items.isEmpty()) MetricDto(status = "none", basis = basis)
            else MetricDto(status = if (history.size >= 3) "ready" else "tentative", basis = basis, value = items.sumOf { it.monthlyAmount ?: 0 }, count = items.size, items = items)
        }

        // Notable spending: category ≥ 1.5× its average over full months and ≥ 1 000 ₽.
        val notable = if (history.size < 3) MetricDto(status = "locked", lock = MetricLockDto("need_full_months", 3, history.size)) else {
            val cur = lines.filter { it.isExpense }.groupBy { it.categoryId }.mapValues { e -> e.value.sumOf { it.signedAmount } }
            val items = cur.mapNotNull { (catId, amount) ->
                catId ?: return@mapNotNull null
                val typical = history.map { m -> view.inMonth(m).filter { it.isExpense && it.categoryId == catId }.sumOf { it.signedAmount } }.average().roundToLong()
                if (typical <= 0 || amount < 100_000 || amount < typical * 1.5) return@mapNotNull null
                val cat = Categories.byId(catId)
                MetricItemDto(categoryId = catId, categoryName = cat?.name ?: "Без категории", categoryIcon = cat?.icon ?: "circle", amount = amount, typicalAmount = typical,
                    filters = base.copy(categoryId = catId, kind = OperationKindFilterDto.expense))
            }.sortedByDescending { (it.amount ?: 0) - (it.typicalAmount ?: 0) }
            if (items.isEmpty()) MetricDto(status = "none", basis = basis) else MetricDto(status = "ready", basis = basis, items = items)
        }

        // Bank fees: «Услуги банка» plus anything that says «комисс».
        val feeLines = lines.filter { it.isExpense && it.signedAmount > 0 && (it.categoryId == Categories.BANK_FEES || it.op.tx.name.contains("комисс", true)) }
        val bankFees = if (feeLines.isEmpty()) MetricDto(status = "none") else MetricDto(
            status = "ready", value = feeLines.sumOf { it.signedAmount },
            kindsName = feeLines.map { it.op.tx.name.lowercase() }.distinct().take(3).joinToString(", "),
            filters = base.copy(selection = Selections.BANK_FEES, selectionName = "Комиссии и проценты банку"),
        )

        // Small frequent: expenses ≤ 500 ₽, grouped by category, ≥ 6 a month.
        val small = if (history.isEmpty()) MetricDto(status = "locked", lock = MetricLockDto("need_full_months", 1, 0)) else {
            val groups = lines.filter { it.isExpense && it.signedAmount in 1..50_000 }.groupBy { it.categoryId }.filter { it.value.size > 8 && it.key != null }
            val items = groups.map { (catId, g) ->
                val sum = g.sumOf { it.signedAmount }
                MetricItemDto(title = Categories.byId(catId!!)?.name ?: "Без категории", count = g.size, averageAmount = sum / g.size, monthlyAmount = sum, yearlyAmount = sum * 12,
                    filters = base.copy(categoryId = catId, kind = OperationKindFilterDto.expense, amountTo = 50_000, selection = Selections.smallFrequent(catId), selectionName = "Мелкие частые траты · " + (Categories.byId(catId)?.name ?: "")))
            }.sortedByDescending { it.monthlyAmount }
            if (items.isEmpty()) MetricDto(status = "none") else MetricDto(status = "ready", value = items.sumOf { it.monthlyAmount ?: 0 }, items = items)
        }
        return InsightsDto(regular, notable, bankFees, small)
    }

    private fun plural(n: Long, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        return when { m100 in 11..14 -> many; m10 == 1L -> one; m10 in 2..4 -> few; else -> many }
    }
}
