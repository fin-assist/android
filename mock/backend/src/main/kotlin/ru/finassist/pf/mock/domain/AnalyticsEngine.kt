package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsNavigation
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.AnalyticsPeriodInfo
import ru.finassist.pf.core.api.model.AnalyticsPeriodItem
import ru.finassist.pf.core.api.model.AnalyticsPeriodsList
import ru.finassist.pf.core.api.model.AnalyticsState
import ru.finassist.pf.core.api.model.AnalyticsTiles
import ru.finassist.pf.core.api.model.BankFeesCard
import ru.finassist.pf.core.api.model.BreakdownStatus
import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.CategoryAmount
import ru.finassist.pf.core.api.model.CategoryBreakdown
import ru.finassist.pf.core.api.model.Coverage as ApiCoverage
import ru.finassist.pf.core.api.model.Insights
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.Metric
import ru.finassist.pf.core.api.model.MetricBasis
import ru.finassist.pf.core.api.model.MetricLock
import ru.finassist.pf.core.api.model.MetricStatus
import ru.finassist.pf.core.api.model.MonthSummary
import ru.finassist.pf.core.api.model.MonthlyChart
import ru.finassist.pf.core.api.model.MonthlyPoint
import ru.finassist.pf.core.api.model.NotableSpendingCard
import ru.finassist.pf.core.api.model.NotableSpendingItem
import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.RegularPayment
import ru.finassist.pf.core.api.model.RegularPaymentsCard
import ru.finassist.pf.core.api.model.SmallFrequentCard
import ru.finassist.pf.core.api.model.SmallFrequentGroup
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.PeriodType
import ru.finassist.pf.core.common.time.toApiMonth
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Computes the Analytics screen (api.md 6) from the ledger. Thresholds, locks, bases and staleness are decided
 * here — the client only renders. Rules mirror review-states-decisions.md / review-gpt-0930-decisions.md.
 */
class AnalyticsEngine(
    private val ledger: Ledger,
    private val catalog: Catalog,
    private val scope: Scope,
    private val coverage: () -> Coverage,
    private val dismissedPayments: () -> Set<String>,
    private val unreadLinesIn: (OffsetDateTime, OffsetDateTime) -> Int,
    private val now: () -> OffsetDateTime,
) {
    private val zone get() = scope.zone
    private fun today(): LocalDate = now().atZoneSameInstant(zone).toLocalDate()

    private fun LocalDate.at(): OffsetDateTime = atStartOfDay(zone).toOffsetDateTime()
    private fun range(from: LocalDate, toExclusive: LocalDate) = DateRange(from.at(), toExclusive.at())

    // ---- periods ----

    fun periods(type: PeriodTypeCode): AnalyticsPeriodsList {
        val cov = coverage()
        val keys = cov.monthsWithData.map { PeriodKey.of(type.toCommon(), it.atDay(1)) }.distinct().reversed()
        return AnalyticsPeriodsList(
            keys.map { key ->
                AnalyticsPeriodItem(key, range(key.start(), key.endExclusive()), apiCoverage(cov.coverageOf(key.start(), key.endExclusive())))
            },
        )
    }

    private fun periodsWithData(type: PeriodType): List<PeriodKey> =
        coverage().monthsWithData.map { PeriodKey.of(type, it.atDay(1)) }.distinct()

    // ---- main ----

    fun analytics(period: PeriodTypeCode?, date: PeriodKey?, mode: TransferMode): Analytics {
        val cov = coverage()
        if (cov.isEmpty || ledger.all.isEmpty()) return Analytics(hasData = false)
        val type = (period ?: PeriodTypeCode.MONTH).toCommon()
        val available = periodsWithData(type)
        val key = when {
            date != null -> PeriodKey.of(type, date.start())
            else -> available.last()
        }
        val from = key.start()
        val toExcl = key.endExclusive()
        val periodRange = range(from, toExcl)
        val isCurrent = !from.isAfter(today()) && toExcl.isAfter(today())
        val visible = scope.visible(mode)
        val ops = scope.inRange(visible, periodRange.from, periodRange.to)
        val bounds = cov.dataBounds(from, toExcl)
        val stale = cov.lastDay?.let { ChronoUnit.DAYS.between(it, today()) > STALE_DAYS } ?: true
        val fullMonthsAll = cov.fullMonths

        val expense = scope.expenseTotal(ops)
        val income = scope.incomeTotal(ops)

        val navIndex = available.indexOf(key)
        val navigation = AnalyticsNavigation(
            previous = available.getOrNull(navIndex - 1).takeIf { navIndex > 0 },
            next = available.getOrNull(navIndex + 1),
        )

        return Analytics(
            hasData = true,
            params = AnalyticsParams(period ?: PeriodTypeCode.MONTH, key, mode),
            period = AnalyticsPeriodInfo(
                range = periodRange,
                isCurrent = isCurrent,
                coverage = apiCoverage(cov.coverageOf(from, toExcl)),
                dataFrom = bounds?.first?.at(),
                dataTo = bounds?.second?.let { dataEnd(ops, it) },
                gaps = cov.gapsWithin(from, toExcl),
            ),
            navigation = navigation,
            tiles = tiles(type, key, mode, visible, ops, expense, income, isCurrent, stale, bounds, cov),
            expenseCategories = expenseBreakdown(ops, expense, periodRange, mode),
            incomeCategories = incomeBreakdown(ops, income, periodRange, mode),
            monthlyChart = monthlyChart(type, key, visible, cov),
            insights = if (type == PeriodType.MONTH) insights(key, mode, visible, ops, periodRange, cov) else null,
            state = AnalyticsState(
                stale = stale,
                lastOperationAt = ledger.all.maxOfOrNull { it.occurredAt },
                dataFrom = cov.firstDay!!.at(),
                dataTo = cov.lastDay!!.plusDays(1).at(),
                fullMonths = fullMonthsAll.size,
                unreadLinesCount = unreadLinesIn(periodRange.from, periodRange.to),
                calculatedAt = now(),
                recalculating = false,
            ),
        )
    }

    /** Last moment with data inside the period: the last operation, or the end of the last covered day. */
    private fun dataEnd(ops: List<Operation>, lastDay: LocalDate): OffsetDateTime =
        ops.maxOfOrNull { it.occurredAt }?.takeIf { it.atZoneSameInstant(zone).toLocalDate() == lastDay }
            ?: lastDay.plusDays(1).at().minusSeconds(1)

    // ---- tiles ----

    private fun tiles(
        type: PeriodType, key: PeriodKey, mode: TransferMode, visible: List<Operation>, ops: List<Operation>,
        expense: Long, income: Long, isCurrent: Boolean, stale: Boolean, bounds: Pair<LocalDate, LocalDate>?, cov: Coverage,
    ): AnalyticsTiles {
        val from = key.start()
        val toExcl = key.endExclusive()
        val periodRange = range(from, toExcl)
        val expenseFilter = OperationsFilter(from = periodRange.from, to = periodRange.to, kind = OperationKindFilter.EXPENSE, transferMode = mode)
        val incomeFilter = expenseFilter.copy(kind = OperationKindFilter.INCOME)

        // Previous full periods of the same type, newest first.
        val previousKeys = generateSequence(key.previous()) { it.previous() }
            .takeWhile { !it.start().isBefore(cov.firstDay ?: from) }
            .toList()
        val previousFull = previousKeys.filter { isFullPeriod(it, cov) }
        val lastDataDay = bounds?.second ?: from
        val dayOffset = ChronoUnit.DAYS.between(from, lastDataDay) // «к тому же дню»

        val comparison = previousKeys.firstOrNull()?.takeIf { isFullPeriod(it, cov) }?.let { prev ->
            val prevEnd = minOf(prev.start().plusDays(dayOffset + 1), prev.endExclusive())
            val prevOps = scope.inDays(visible, prev.start(), prevEnd)
            Metric(status = MetricStatus.READY, value = Money(scope.expenseTotal(prevOps)), range = range(prev.start(), prevEnd))
        } ?: locked(LockReason.NEED_FULL_MONTHS, required = 1, available = 0)

        val typical = if (previousFull.size >= TYPICAL_MIN_PERIODS) {
            val base = previousFull.take(6)
            val avg = base.map { p -> scope.expenseTotal(scope.inDays(visible, p.start(), p.endExclusive())) }.average().roundToLong()
            Metric(
                status = MetricStatus.READY, value = Money(avg),
                basis = MetricBasis(range(base.last().start(), base.first().endExclusive()), base.sumOf { monthsIn(it) }),
            )
        } else {
            locked(LockReason.NEED_FULL_MONTHS, required = TYPICAL_MIN_PERIODS * monthsIn(key), available = previousFull.sumOf { monthsIn(it) })
        }

        val daysWithData = bounds?.let { (a, b) -> ChronoUnit.DAYS.between(a, minOf(b, today())) + 1 }?.coerceAtLeast(1) ?: 1
        val daily = Metric(status = MetricStatus.READY, value = Money(expense / daysWithData))
        val dailyComparison = previousKeys.firstOrNull()?.takeIf { isFullPeriod(it, cov) }?.let { prev ->
            val prevOps = scope.inDays(visible, prev.start(), prev.endExclusive())
            val days = ChronoUnit.DAYS.between(prev.start(), prev.endExclusive())
            Metric(status = MetricStatus.READY, value = Money(scope.expenseTotal(prevOps) / days), range = range(prev.start(), prev.endExclusive()))
        } ?: locked(LockReason.NEED_FULL_MONTHS, required = 1, available = 0)

        val forecast = if (!isCurrent) null else when (type) {
            PeriodType.MONTH -> when {
                stale -> locked(LockReason.STALE_DATA)
                today().dayOfMonth < FORECAST_FROM_DAY -> locked(LockReason.TOO_EARLY_IN_MONTH, availableFrom = from.withDayOfMonth(FORECAST_FROM_DAY).at())
                else -> {
                    val elapsed = ChronoUnit.DAYS.between(from, minOf(lastDataDay, today())) + 1
                    val length = ChronoUnit.DAYS.between(from, toExcl)
                    Metric(status = MetricStatus.READY, value = Money(expense * length / elapsed.coerceAtLeast(1)))
                }
            }
            else -> {
                val monthsInPeriod = generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { it.atDay(1).isBefore(toExcl) }.toList()
                val full = monthsInPeriod.filter { cov.isFullMonth(it) }
                if (full.isEmpty()) {
                    locked(LockReason.NEED_FULL_MONTHS, required = monthsInPeriod.size, available = 0)
                } else {
                    val perMonth = full.map { m -> scope.expenseTotal(scope.inDays(visible, m.atDay(1), m.plusMonths(1).atDay(1))) }.average()
                    Metric(status = MetricStatus.READY, value = Money((perMonth * monthsInPeriod.size).roundToLong()))
                }
            }
        }

        return AnalyticsTiles(
            expense = Metric(status = MetricStatus.READY, value = Money(expense), filters = expenseFilter, comparison = comparison, typical = typical),
            income = Metric(status = MetricStatus.READY, value = Money(income), filters = incomeFilter),
            balance = Metric(
                status = MetricStatus.READY, value = Money(income - expense),
                shareOfIncome = if (income > 0) (income - expense).toDouble() / income else null,
            ),
            dailyExpense = daily.copy(comparison = dailyComparison),
            forecast = forecast,
        )
    }

    private fun isFullPeriod(key: PeriodKey, cov: Coverage): Boolean =
        generateSequence(YearMonth.from(key.start())) { it.plusMonths(1) }
            .takeWhile { it.atDay(1).isBefore(key.endExclusive()) }
            .all { cov.isFullMonth(it) }

    private fun monthsIn(key: PeriodKey) = when (key.type) { PeriodType.MONTH -> 1; PeriodType.QUARTER -> 3; PeriodType.YEAR -> 12 }

    private fun locked(reason: LockReason, required: Int? = null, available: Int? = null, availableFrom: OffsetDateTime? = null) =
        Metric(status = MetricStatus.LOCKED, lock = MetricLock(reason, required, available, availableFrom))

    // ---- categories ----

    private fun expenseBreakdown(ops: List<Operation>, total: Long, periodRange: DateRange, mode: TransferMode): CategoryBreakdown {
        val entries = scope.expenses(ops)
        if (entries.isEmpty()) return CategoryBreakdown(BreakdownStatus.NONE, emptyList())
        val grouped = entries.groupBy { it.category }
        val items = grouped.map { (category, list) ->
            val amount = list.sumOf { it.signedMinor }
            CategoryAmount(
                categoryId = category?.id,
                categoryName = category?.name ?: "Без категории",
                categoryIcon = category?.icon ?: "tag",
                note = note(category, list.map { it.op }),
                amount = Money(amount),
                share = if (total > 0 && amount > 0) amount.toDouble() / total else null,
                operationCount = list.size,
                filters = OperationsFilter(
                    from = periodRange.from, to = periodRange.to, kind = OperationKindFilter.EXPENSE, transferMode = mode,
                    categoryId = category?.id, selection = if (category == null) SELECTION_UNCATEGORIZED else null,
                    selectionName = if (category == null) "Без категории" else null,
                ),
            )
        }.sortedByDescending { it.amount.minor }
        return CategoryBreakdown(BreakdownStatus.READY, items)
    }

    private fun incomeBreakdown(ops: List<Operation>, total: Long, periodRange: DateRange, mode: TransferMode): CategoryBreakdown {
        val incomes = scope.incomes(ops)
        if (incomes.isEmpty()) return CategoryBreakdown(BreakdownStatus.NONE, emptyList())
        val items = incomes.groupBy { it.category }.map { (category, list) ->
            val amount = list.sumOf { it.amountMinor }
            CategoryAmount(
                categoryId = category.id, categoryName = category.name, categoryIcon = category.icon,
                note = note(category, list),
                amount = Money(amount), share = if (total > 0) amount.toDouble() / total else null,
                operationCount = list.size,
                filters = OperationsFilter(from = periodRange.from, to = periodRange.to, kind = OperationKindFilter.INCOME, transferMode = mode, categoryId = category.id),
            )
        }.sortedByDescending { it.amount.minor }
        return CategoryBreakdown(BreakdownStatus.READY, items)
    }

    /** «5 переводов», «зарплата и проценты» — a short hint under a category row where the mockups show one. */
    private fun note(category: Category?, ops: List<Operation>): String? = when (category?.id) {
        catalog.transfers.id -> ru.finassist.pf.core.common.time.countWithNoun(ops.size, "перевод", "перевода", "переводов")
        else -> null
    }

    // ---- monthly chart ----

    private fun monthlyChart(type: PeriodType, key: PeriodKey, visible: List<Operation>, cov: Coverage): MonthlyChart {
        val months: List<YearMonth> = when (type) {
            PeriodType.MONTH -> (5 downTo 0).map { YearMonth.from(key.start()).minusMonths(it.toLong()) }
            PeriodType.QUARTER -> (0..2).map { YearMonth.from(key.start()).plusMonths(it.toLong()) }
            PeriodType.YEAR -> (0..11).map { YearMonth.from(key.start()).plusMonths(it.toLong()) }
        }
        val withData = cov.monthsWithData.size
        if (withData < CHART_MIN_MONTHS) {
            return MonthlyChart(status = MetricStatus.LOCKED, lock = MetricLock(LockReason.NEED_MONTHS_WITH_DATA, CHART_MIN_MONTHS, withData))
        }
        return MonthlyChart(status = MetricStatus.READY, points = months.map { m -> monthlyPoint(m, visible, cov) })
    }

    fun monthlyPoint(m: YearMonth, visible: List<Operation>, cov: Coverage): MonthlyPoint {
        val from = m.atDay(1)
        val toExcl = m.plusMonths(1).atDay(1)
        val has = cov.hasData(m)
        val ops = scope.inDays(visible, from, toExcl)
        val bounds = cov.dataBounds(from, toExcl)
        return MonthlyPoint(
            month = m.toApiMonth(),
            range = range(from, toExcl),
            expense = if (has) Money(scope.expenseTotal(ops)) else null,
            income = if (has) Money(scope.incomeTotal(ops)) else null,
            coverage = apiCoverage(cov.coverageOf(from, toExcl)),
            dataFrom = bounds?.first?.at(),
            dataTo = bounds?.second?.let { dataEnd(ops, it) },
            gaps = cov.gapsWithin(from, toExcl),
        )
    }

    /** Month summary for the feed (api.md 4.1): «С переводами» rules, `data_to` when the month is incomplete. */
    fun monthSummary(m: YearMonth): MonthSummary {
        val cov = coverage()
        val point = monthlyPoint(m, scope.visible(TransferMode.WITH), cov)
        return MonthSummary(
            month = point.month, range = point.range,
            expense = point.expense ?: Money.ZERO, income = point.income ?: Money.ZERO,
            dataTo = if (point.coverage == ApiCoverage.PARTIAL) point.dataTo else null,
        )
    }

    // ---- insights (month only) ----

    private fun insights(key: PeriodKey, mode: TransferMode, visible: List<Operation>, ops: List<Operation>, periodRange: DateRange, cov: Coverage): Insights {
        val month = YearMonth.from(key.start())
        // Base: full months up to and including the selected one, newest first, at most 6.
        val base = cov.fullMonths.filter { !it.isAfter(month) }.sortedDescending().take(6)
        val basis = if (base.isEmpty()) null else MetricBasis(range(base.last().atDay(1), base.first().plusMonths(1).atDay(1)), base.size)

        return Insights(
            regularPayments = regularPayments(mode, visible, base, basis, periodRange),
            notableSpending = notableSpending(mode, ops, visible, base, basis, periodRange),
            bankFees = bankFees(mode, ops, periodRange),
            smallFrequent = smallFrequent(mode, ops, base, basis, periodRange, month),
        )
    }

    private fun regularPayments(mode: TransferMode, visible: List<Operation>, base: List<YearMonth>, basis: MetricBasis?, periodRange: DateRange): RegularPaymentsCard {
        if (base.size < 2) {
            return RegularPaymentsCard(status = MetricStatus.LOCKED, lock = MetricLock(LockReason.NEED_FULL_MONTHS, 2, base.size))
        }
        val items = regularItems(mode, visible, base, periodRange)
        val status = if (base.size >= 3) MetricStatus.READY else MetricStatus.TENTATIVE
        if (items.isEmpty()) return RegularPaymentsCard(status = MetricStatus.NONE, basis = basis)
        return RegularPaymentsCard(
            status = status, basis = basis, value = Money(items.sumOf { it.monthlyAmount.minor }), count = items.size, items = items,
            filters = OperationsFilter(from = periodRange.from, to = periodRange.to, transferMode = mode, selection = regularAllSelection(base.first(), mode), selectionName = "Регулярные платежи"),
        )
    }

    /** Regular payments over the last (up to 3) full months of [base]; [base] is newest first, at least 2 months. */
    private fun regularItems(mode: TransferMode, visible: List<Operation>, base: List<YearMonth>, periodRange: DateRange): List<RegularPayment> {
        val window = base.take(3)
        val debits = window.flatMap { m -> scope.inDays(visible, m.atDay(1), m.plusMonths(1).atDay(1)) }
            .filter { it.isDebit && !it.isOwnTransferCategory && it.category.id != catalog.transfers.id }
        val dismissed = dismissedPayments()
        val items = debits.groupBy { it.name }.mapNotNull { (name, list) ->
            val perMonth = list.groupBy { YearMonth.from(scope.dayOf(it)) }
            if (perMonth.size < window.size || perMonth.values.any { it.size > 2 }) return@mapNotNull null
            val amounts = list.map { it.amountMinor }.sorted()
            val median = amounts[amounts.size / 2]
            if (amounts.any { abs(it - median) > median * REGULAR_TOLERANCE }) return@mapNotNull null
            val id = "rp_" + Catalog.slugOf(name).take(40)
            if (id in dismissed) return@mapNotNull null
            val day = list.map { scope.dayOf(it).dayOfMonth }.sorted().let { it[it.size / 2] }
            RegularPayment(
                id = id, title = name, amount = Money(median), monthlyAmount = Money(median),
                scheduleName = "$day-го числа", categoryId = list.first().category.id,
                filters = OperationsFilter(from = periodRange.from, to = periodRange.to, transferMode = mode, selection = "s_regular_$id", selectionName = name),
            )
        }.sortedByDescending { it.monthlyAmount.minor }
    }

    /**
     * `s_regular_all` carries the card it came from (`s_regular_all_<base month>_<mode>`): a selection is valid
     * indefinitely (api.md 4.1), so the merchant set is recomputed from it rather than taken from whichever card
     * was computed last. Null for a malformed selection.
     */
    fun regularNames(selection: String): Set<String>? {
        val (month, modeCode) = selection.removePrefix(SELECTION_REGULAR_ALL + "_").split("_", limit = 2)
            .takeIf { it.size == 2 } ?: return null
        val m = runCatching { YearMonth.parse(month) }.getOrNull() ?: return null
        val mode = TransferMode.entries.firstOrNull { it.code == modeCode && it != TransferMode.UNKNOWN } ?: return null
        val base = coverage().fullMonths.filter { !it.isAfter(m) }.sortedDescending().take(6)
        if (base.firstOrNull() != m || base.size < 2) return emptySet()
        val range = range(m.atDay(1), m.plusMonths(1).atDay(1))
        return regularItems(mode, scope.visible(mode), base, range).map { it.title }.toSet()
    }

    private fun regularAllSelection(baseMonth: YearMonth, mode: TransferMode) = "${SELECTION_REGULAR_ALL}_${baseMonth}_${mode.code}"

    /** Which operations a `s_regular_*` selection means: debits with that merchant name. */
    fun regularPaymentName(selection: String): String? {
        val id = selection.removePrefix("s_regular_")
        return ledger.all.map { it.name }.distinct().firstOrNull { "rp_" + Catalog.slugOf(it).take(40) == id }
    }

    private fun notableSpending(mode: TransferMode, ops: List<Operation>, visible: List<Operation>, base: List<YearMonth>, basis: MetricBasis?, periodRange: DateRange): NotableSpendingCard {
        if (base.size < 3) {
            return NotableSpendingCard(status = MetricStatus.LOCKED, lock = MetricLock(LockReason.NEED_FULL_MONTHS, 3, base.size))
        }
        val baseOps = base.flatMap { m -> scope.inDays(visible, m.atDay(1), m.plusMonths(1).atDay(1)) }
        val typical = scope.expenses(baseOps).filter { it.category != null }.groupBy { it.category!! }
            .mapValues { (_, l) -> l.sumOf { it.signedMinor } / base.size }
        val current = scope.expenses(ops).filter { it.category != null }.groupBy { it.category!! }
            .mapValues { (_, l) -> l.sumOf { it.signedMinor } }
        val items = current.mapNotNull { (category, amount) ->
            val typ = typical[category] ?: return@mapNotNull null
            if (typ <= 0 || amount < NOTABLE_MIN_MINOR || amount < typ * NOTABLE_FACTOR) return@mapNotNull null
            NotableSpendingItem(
                categoryId = category.id, categoryName = category.name, categoryIcon = category.icon,
                amount = Money(amount), typicalAmount = Money(typ),
                filters = OperationsFilter(from = periodRange.from, to = periodRange.to, kind = OperationKindFilter.EXPENSE, transferMode = mode, categoryId = category.id),
            )
        }.sortedByDescending { it.amount.minor - it.typicalAmount.minor }
        return if (items.isEmpty()) NotableSpendingCard(status = MetricStatus.NONE, basis = basis)
        else NotableSpendingCard(status = MetricStatus.READY, basis = basis, items = items)
    }

    private fun bankFees(mode: TransferMode, ops: List<Operation>, periodRange: DateRange): BankFeesCard {
        val fees = scope.expenses(ops).filter { it.category?.id == catalog.bankFees.id }
        val total = fees.sumOf { it.signedMinor }
        if (fees.isEmpty() || total <= 0) return BankFeesCard(status = MetricStatus.NONE)
        val kinds = fees.map { it.op.name.lowercase() }.distinct().take(3).joinToString(", ")
        return BankFeesCard(
            status = MetricStatus.READY, value = Money(total), kindsName = kinds,
            filters = OperationsFilter(from = periodRange.from, to = periodRange.to, transferMode = mode, selection = SELECTION_BANK_FEES, selectionName = "Комиссии и проценты банку"),
        )
    }

    private fun smallFrequent(mode: TransferMode, ops: List<Operation>, base: List<YearMonth>, basis: MetricBasis?, periodRange: DateRange, month: YearMonth): SmallFrequentCard {
        if (base.isEmpty()) return SmallFrequentCard(status = MetricStatus.LOCKED, lock = MetricLock(LockReason.NEED_FULL_MONTHS, 1, 0))
        val groups = ops.filter { it.isDebit && !it.isOwnTransferCategory && it.amountMinor <= SMALL_MAX_MINOR && it.category.id != catalog.transfers.id }
            .groupBy { it.name }
            .filter { (_, l) -> l.size >= SMALL_MIN_COUNT }
            .map { (name, l) ->
                val monthly = l.sumOf { it.amountMinor }
                SmallFrequentGroup(
                    title = name, count = l.size, averageAmount = Money(monthly / l.size),
                    monthlyAmount = Money(monthly), yearlyAmount = Money(monthly * 12),
                    filters = OperationsFilter(from = periodRange.from, to = periodRange.to, transferMode = mode, q = name, kind = OperationKindFilter.EXPENSE, amountTo = Money(SMALL_MAX_MINOR)),
                )
            }.sortedByDescending { it.monthlyAmount.minor }
        if (groups.isEmpty()) return SmallFrequentCard(status = MetricStatus.NONE, basis = basis)
        return SmallFrequentCard(status = MetricStatus.READY, basis = basis, value = Money(groups.sumOf { it.monthlyAmount.minor }), items = groups)
    }

    private fun apiCoverage(code: String) = when (code) {
        "complete" -> ApiCoverage.COMPLETE
        "no_data" -> ApiCoverage.NO_DATA
        else -> ApiCoverage.PARTIAL
    }

    private fun PeriodTypeCode.toCommon() = when (this) {
        PeriodTypeCode.QUARTER -> PeriodType.QUARTER
        PeriodTypeCode.YEAR -> PeriodType.YEAR
        else -> PeriodType.MONTH
    }

    companion object {
        const val STALE_DAYS = 14
        const val FORECAST_FROM_DAY = 10
        const val TYPICAL_MIN_PERIODS = 2
        const val CHART_MIN_MONTHS = 2
        const val REGULAR_TOLERANCE = 0.15
        const val NOTABLE_FACTOR = 1.5
        const val NOTABLE_MIN_MINOR = 100_000L
        const val SMALL_MAX_MINOR = 50_000L
        const val SMALL_MIN_COUNT = 4
        const val SELECTION_UNCATEGORIZED = "s_uncategorized"
        const val SELECTION_BANK_FEES = "s_bank_fees"
        const val SELECTION_REGULAR_ALL = "s_regular_all"
    }
}
