package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.AnalyticsNavigation
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.AnalyticsPeriodInfo
import ru.finassist.pf.core.api.model.AnalyticsPeriodItem
import ru.finassist.pf.core.api.model.AnalyticsPeriodsList
import ru.finassist.pf.core.api.model.AnalyticsState
import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.Coverage as ApiCoverage
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.MonthSummary
import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.SystemCategories
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
import java.util.UUID
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
    /** `kind` of the expense tile's filter: `expense`, or a code the client does not know (`MockConfig.unknownCodes`). */
    private val expenseKind: () -> OperationKindFilter = { OperationKindFilter.EXPENSE },
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

    fun analytics(period: PeriodTypeCode?, date: PeriodKey?, mode: TransferMode): AnalyticsSnapshot {
        val cov = coverage()
        if (cov.isEmpty || ledger.all.isEmpty()) return AnalyticsSnapshot(hasData = false)
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

        return AnalyticsSnapshot(
            hasData = true,
            params = AnalyticsParams(period ?: PeriodTypeCode.MONTH, key, mode),
            period = AnalyticsPeriodInfo(
                range = periodRange,
                isCurrent = isCurrent,
                coverage = apiCoverage(cov.coverageOf(from, toExcl)),
                dataFrom = bounds?.first?.at(),
                dataTo = dataEnd(from, toExcl, cov),
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

    /**
     * Last moment with data inside `[from, toExcl)` (docs/calc.md, section 4): the time of the period's last operation —
     * any, whatever the transfer mode and pairs — if it is on the last covered day, else the end of that day.
     */
    fun dataEnd(from: LocalDate, toExcl: LocalDate, cov: Coverage): OffsetDateTime? {
        val lastDay = cov.dataBounds(from, toExcl)?.second ?: return null
        val last = ledger.all.filter { val d = it.occurredAt.atZoneSameInstant(zone).toLocalDate(); d >= from && d < toExcl }
            .maxOfOrNull { it.occurredAt }
        return last?.takeIf { it.atZoneSameInstant(zone).toLocalDate() == lastDay } ?: lastDay.plusDays(1).at().minusSeconds(1)
    }

    // ---- tiles ----

    private fun tiles(
        type: PeriodType, key: PeriodKey, mode: TransferMode, visible: List<Operation>, ops: List<Operation>,
        expense: Long, income: Long, isCurrent: Boolean, stale: Boolean, bounds: Pair<LocalDate, LocalDate>?, cov: Coverage,
    ): AnalyticsTiles {
        val from = key.start()
        val toExcl = key.endExclusive()
        val periodRange = range(from, toExcl)
        val expenseFilter = OperationsFilter(from = periodRange.from, to = periodRange.to, kind = expenseKind(), transferMode = mode)
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

        // Covered days of the period not later than today, gaps excluded (docs/calc.md, section 5).
        val daysWithData = cov.countDays(key.start(), minOf(key.endExclusive(), today().plusDays(1))).coerceAtLeast(1)
        val daily = Metric(status = MetricStatus.READY, value = Money(expense / daysWithData))
        val dailyComparison = previousKeys.firstOrNull()?.takeIf { isFullPeriod(it, cov) }?.let { prev ->
            val prevOps = scope.inDays(visible, prev.start(), prev.endExclusive())
            val days = ChronoUnit.DAYS.between(prev.start(), prev.endExclusive())
            Metric(status = MetricStatus.READY, value = Money(scope.expenseTotal(prevOps) / days), range = range(prev.start(), prev.endExclusive()))
        } ?: locked(LockReason.NEED_FULL_MONTHS, required = 1, available = 0)

        // A stale statement blocks every forecast, not only the month one: extrapolating from a long-gone last
        // operation would look reliable while it is not (the client shows the fact instead).
        val forecast = if (!isCurrent) null else if (stale) locked(LockReason.STALE_DATA) else when (type) {
            PeriodType.MONTH -> when {
                today().dayOfMonth < FORECAST_FROM_DAY -> locked(LockReason.TOO_EARLY_IN_MONTH, availableFrom = from.withDayOfMonth(FORECAST_FROM_DAY).at())
                else -> {
                    val elapsed = ChronoUnit.DAYS.between(from, minOf(lastDataDay, today())) + 1
                    val length = ChronoUnit.DAYS.between(from, toExcl)
                    Metric(status = MetricStatus.READY, value = Money(expense * length / elapsed.coerceAtLeast(1)))
                }
            }
            else -> {
                // Quarter and year: by full months of the whole history — a quarter needs 3, a year 12 (FIN-28,
                // api.md `tiles.forecast`: «Нужно 12 полных месяцев, есть 5»). Fact so far plus the average day
                // of the latest full months for the days left in the period.
                val required = monthsIn(key)
                val full = cov.fullMonths.sortedDescending()
                if (full.size < required) {
                    locked(LockReason.NEED_FULL_MONTHS, required = required, available = full.size)
                } else {
                    val base = full.take(required)
                    val baseExpense = base.sumOf { m -> scope.expenseTotal(scope.inDays(visible, m.atDay(1), m.plusMonths(1).atDay(1))) }
                    val baseDays = base.sumOf { it.lengthOfMonth() }
                    // No data inside the period yet (statement ends before it): every day of it is still ahead.
                    val remainingFrom = bounds?.second?.plusDays(1) ?: from
                    val remainingDays = ChronoUnit.DAYS.between(remainingFrom, toExcl).coerceAtLeast(0)
                    Metric(
                        status = MetricStatus.READY,
                        value = Money(expense + (baseExpense.toDouble() / baseDays * remainingDays).roundToLong()),
                        basis = MetricBasis(range(base.last().atDay(1), base.first().plusMonths(1).atDay(1)), base.size),
                    )
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
                categoryId = category.id,
                categoryName = category.name,
                categoryIcon = category.icon,
                note = note(category, list.map { it.op }),
                amount = Money(amount),
                share = if (total > 0 && amount >= 0) amount.toDouble() / total else null,
                operationCount = list.size,
                filters = OperationsFilter(
                    from = periodRange.from, to = periodRange.to, kind = OperationKindFilter.EXPENSE, transferMode = mode,
                    categoryId = category.id,
                ),
            )
        }.sortedWith(BY_AMOUNT_THEN_NAME)
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
        }.sortedWith(BY_AMOUNT_THEN_NAME)
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
            dataTo = dataEnd(from, toExcl, cov),
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
        // B: full months up to and including the selected one, newest first (docs/calc.md, section 5).
        val base = cov.fullMonths.filter { !it.isAfter(month) }.sortedDescending()
        val basis = if (base.isEmpty()) null else MetricBasis(range(base.last().atDay(1), base.first().plusMonths(1).atDay(1)), base.size)

        return Insights(
            regularPayments = regularPayments(mode, visible, base, basis, periodRange),
            notableSpending = notableSpending(mode, ops, visible, month, cov, periodRange),
            bankFees = bankFees(mode, ops, periodRange),
            smallFrequent = smallFrequent(mode, base, basis),
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

    /**
     * Regular payments (docs/calc.md, section 5): window W — the first up to 3 months of [base] (full months up to the
     * selected one, newest first). Debits with a description, outside the common exclusions, grouped by description;
     * a payment is a group with exactly one debit in every month of W, all within ±15 % of the median (the element
     * n/2 of the sorted amounts). Payments the user dismissed are left out. Largest first, then by description.
     */
    private fun regularItems(mode: TransferMode, visible: List<Operation>, base: List<YearMonth>, periodRange: DateRange): List<RegularPayment> {
        val window = base.take(3)
        val byName = LinkedHashMap<String, MutableMap<YearMonth, MutableList<Operation>>>()
        for (m in window) {
            for (op in scope.inDays(visible, m.atDay(1), m.plusMonths(1).atDay(1))) {
                if (op.isDebit && op.name.isNotEmpty() && observed(op) && !op.isOwnTransferCategory) {
                    byName.getOrPut(op.name) { LinkedHashMap() }.getOrPut(m) { mutableListOf() } += op
                }
            }
        }
        val dismissed = dismissedPayments()
        return byName.mapNotNull { (name, byMonth) ->
            if (byMonth.size != window.size || byMonth.values.any { it.size != 1 }) return@mapNotNull null
            val list = byMonth.values.map { it.single() }
            val amounts = list.map { it.amountMinor }.sorted()
            val median = amounts[amounts.size / 2]
            if (amounts.any { 100 * abs(it - median) > REGULAR_TOLERANCE_PCT * median }) return@mapNotNull null
            val id = paymentId(name)
            if (id in dismissed) return@mapNotNull null
            val day = list.map { scope.dayOf(it).dayOfMonth }.sorted().let { it[it.size / 2] }
            RegularPayment(
                id = id, title = name, amount = Money(median), monthlyAmount = Money(median),
                scheduleName = "$day-го числа", categoryId = list.first().category.id,
                filters = OperationsFilter(from = periodRange.from, to = periodRange.to, transferMode = mode, selection = "s_regular_$id", selectionName = name),
            )
        }.sortedWith(compareByDescending<RegularPayment> { it.monthlyAmount.minor }.thenBy { it.title })
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
        val base = coverage().fullMonths.filter { !it.isAfter(m) }.sortedDescending()
        if (base.firstOrNull() != m || base.size < 2) return emptySet()
        val range = range(m.atDay(1), m.plusMonths(1).atDay(1))
        return regularItems(mode, scope.visible(mode), base, range).map { it.title }.toSet()
    }

    private fun regularAllSelection(baseMonth: YearMonth, mode: TransferMode) = "${SELECTION_REGULAR_ALL}_${baseMonth}_${mode.code}"

    /** Which operations a `s_regular_*` selection means: debits with that description. */
    fun regularPaymentName(selection: String): String? {
        val id = selection.removePrefix("s_regular_")
        return ledger.all.map { it.name }.distinct().firstOrNull { paymentId(it) == id }
    }

    /**
     * Notable spending (docs/calc.md, section 5): base — 3 full months before the selected one; «usual» of a category
     * — its expense over the base / 3, rounded half up. A category (except «Без категории», «Переводы», «Между своими
     * счетами») is notable when usual > 0, the period's expense ≥ 1 000 ₽ and ≥ 1.5 × usual. By the difference.
     */
    private fun notableSpending(mode: TransferMode, ops: List<Operation>, visible: List<Operation>, month: YearMonth, cov: Coverage, periodRange: DateRange): NotableSpendingCard {
        val before = cov.fullMonths.filter { it.isBefore(month) }.sortedDescending()
        if (before.size < 3) {
            return NotableSpendingCard(status = MetricStatus.LOCKED, lock = MetricLock(LockReason.NEED_FULL_MONTHS, 3, before.size))
        }
        val base = before.take(3)
        val basis = MetricBasis(range(base.last().atDay(1), base.first().plusMonths(1).atDay(1)), base.size)

        fun byCategory(list: List<Operation>): Map<Category, Long> = scope.expenses(list)
            .filter { e -> e.category.id != SystemCategories.UNCATEGORIZED && e.category.id != catalog.transfers.id && e.category.id != SystemCategories.OWN_TRANSFER }
            .groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.signedMinor } }

        val typical = byCategory(base.flatMap { m -> scope.inDays(visible, m.atDay(1), m.plusMonths(1).atDay(1)) })
            .mapValues { (_, sum) -> Math.floorDiv(2 * sum + 3, 6) } // sum / 3, half up
        val items = byCategory(ops).mapNotNull { (category, amount) ->
            val typ = typical[category] ?: 0
            if (typ <= 0 || amount < NOTABLE_MIN_MINOR || 2 * amount < 3 * typ) return@mapNotNull null
            NotableSpendingItem(
                categoryId = category.id, categoryName = category.name, categoryIcon = category.icon,
                amount = Money(amount), typicalAmount = Money(typ),
                filters = OperationsFilter(from = periodRange.from, to = periodRange.to, kind = OperationKindFilter.EXPENSE, transferMode = mode, categoryId = category.id),
            )
        }.sortedWith(compareByDescending<NotableSpendingItem> { it.amount.minor - it.typicalAmount.minor }.thenBy { it.categoryName })
        return if (items.isEmpty()) NotableSpendingCard(status = MetricStatus.NONE, basis = basis)
        else NotableSpendingCard(status = MetricStatus.READY, basis = basis, items = items)
    }

    /**
     * Bank fees (docs/calc.md, section 5): expense lines of the period whose final category (the target for a
     * refund) is «Услуги банка» or whose description has the whole word «комиссия» / «процент» in any case form.
     */
    private fun bankFees(mode: TransferMode, ops: List<Operation>, periodRange: DateRange): BankFeesCard {
        val fees = scope.expenses(ops).filter { isFee(it.op, it.category) }
        val total = fees.sumOf { it.signedMinor }
        if (fees.isEmpty() || total <= 0) return BankFeesCard(status = MetricStatus.NONE)
        // Fees without a description name no kind; none named — no kinds line at all (FIN-67).
        val kinds = fees.map { it.op.name }.filter { it.isNotBlank() }.distinct().take(3).joinToString(", ").ifEmpty { null }
        return BankFeesCard(
            status = MetricStatus.READY, value = Money(total), kindsName = kinds,
            filters = OperationsFilter(from = periodRange.from, to = periodRange.to, transferMode = mode, selection = SELECTION_BANK_FEES, selectionName = "Комиссии и проценты банку"),
        )
    }

    /** One group of small frequent spending: by merchant (description) or by category. */
    private data class SmallGroup(val kind: String, val key: String, val title: String, val ops: List<Operation>) {
        val total: Long get() = ops.sumOf { it.amountMinor }
    }

    /**
     * Small frequent spending of month [m] (docs/calc.md, section 5): its debits outside the common exclusions;
     * groups by merchant (non-empty description) of more than 8 operations with an average ≤ 500 ₽, then, from the
     * rest, the same by final category (not «Без категории»). Largest first, then by title.
     */
    private fun smallGroups(m: YearMonth, mode: TransferMode): List<SmallGroup> {
        val debits = scope.inDays(scope.visible(mode), m.atDay(1), m.plusMonths(1).atDay(1))
            .filter { it.isDebit && !it.isOwnTransferCategory && observed(it) }
        val groups = mutableListOf<SmallGroup>()
        val used = HashSet<String>()
        fun take(candidates: List<Operation>, keyOf: (Operation) -> String?, group: (String, List<Operation>) -> SmallGroup) {
            val buckets = LinkedHashMap<String, MutableList<Operation>>()
            candidates.forEach { op -> keyOf(op)?.let { buckets.getOrPut(it) { mutableListOf() } += op } }
            for ((key, list) in buckets) {
                val total = list.sumOf { it.amountMinor }
                if (list.size > SMALL_MIN_COUNT && total / list.size <= SMALL_MAX_AVG_MINOR) {
                    groups += group(key, list)
                    list.forEach { used += it.key }
                }
            }
        }
        take(debits, { op -> op.name.takeIf { it.isNotEmpty() } }) { k, l -> SmallGroup(SMALL_MERCHANT, k, k, l) }
        take(
            debits.filter { it.key !in used },
            { op -> op.category.id.takeIf { it != SystemCategories.UNCATEGORIZED } },
        ) { k, l -> SmallGroup(SMALL_CATEGORY, k, l.first().category.name, l) }
        return groups.sortedWith(compareByDescending<SmallGroup> { it.total }.thenBy { it.title })
    }

    private fun smallFrequent(mode: TransferMode, base: List<YearMonth>, basis: MetricBasis?): SmallFrequentCard {
        if (base.isEmpty()) return SmallFrequentCard(status = MetricStatus.LOCKED, lock = MetricLock(LockReason.NEED_FULL_MONTHS, 1, 0))
        // The month of the calculation: the selected one if it is full, else the last full month before it.
        val m = base.first()
        val range = range(m.atDay(1), m.plusMonths(1).atDay(1))
        val groups = smallGroups(m, mode).map { g ->
            SmallFrequentGroup(
                title = g.title, count = g.ops.size, averageAmount = Money(g.total / g.ops.size),
                monthlyAmount = Money(g.total), yearlyAmount = Money(g.total * 12),
                filters = OperationsFilter(
                    from = range.from, to = range.to, transferMode = mode,
                    selection = "${SELECTION_SMALL}_${g.kind}_${m}_${mode.code}_${keyId(g.key)}", selectionName = g.title,
                ),
            )
        }
        if (groups.isEmpty()) return SmallFrequentCard(status = MetricStatus.NONE, basis = basis)
        return SmallFrequentCard(status = MetricStatus.READY, basis = basis, value = Money(groups.sumOf { it.monthlyAmount.minor }), items = groups)
    }

    /**
     * Which operations a `s_small_<kind>_<month>_<mode>_<key>` selection means. It carries the card it came from, so
     * the groups are recomputed (a selection is valid indefinitely, api.md 4.1). Null for a malformed or unknown one.
     */
    fun smallSelection(selection: String): ((Operation) -> Boolean)? {
        val parts = selection.removePrefix(SELECTION_SMALL + "_").split("_", limit = 4).takeIf { it.size == 4 } ?: return null
        val (kind, month, modeCode, keyId) = parts
        val m = runCatching { YearMonth.parse(month) }.getOrNull() ?: return null
        val mode = TransferMode.entries.firstOrNull { it.code == modeCode && it != TransferMode.UNKNOWN } ?: return null
        val groups = smallGroups(m, mode)
        val group = groups.firstOrNull { it.kind == kind && keyId(it.key) == keyId } ?: return null
        val merchantUsed = groups.filter { it.kind == SMALL_MERCHANT }.flatMap { g -> g.ops.map { it.key } }.toSet()
        return { op ->
            op.isDebit && !op.isOwnTransferCategory && observed(op) &&
                if (group.kind == SMALL_MERCHANT) op.name == group.key else op.category.id == group.key && op.key !in merchantUsed
        }
    }

    /** Common exclusions of observations: «Переводы» and «Между своими счетами», by the target for a refund. */
    private fun observed(op: Operation): Boolean {
        val category = if (op.isRefund) op.refundTarget else op.category
        return category?.id != catalog.transfers.id && category?.id != SystemCategories.OWN_TRANSFER
    }

    private fun keyId(key: String) = UUID.nameUUIDFromBytes(key.toByteArray()).toString()

    /** Id of a regular payment: a UUID stable by its description (docs/calc.md, section 5). */
    private fun paymentId(name: String) = UUID.nameUUIDFromBytes("payment:$name".toByteArray()).toString()

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
        const val REGULAR_TOLERANCE_PCT = 15
        const val NOTABLE_MIN_MINOR = 100_000L
        const val SMALL_MAX_AVG_MINOR = 50_000L
        const val SMALL_MIN_COUNT = 8
        const val SELECTION_SMALL = "s_small"
        private const val SMALL_MERCHANT = "merchant"
        private const val SMALL_CATEGORY = "category"
        private val FEE_WORDS = setOf(
            "комиссия", "комиссии", "комиссию", "комиссией", "комиссий", "комиссиям", "комиссиями", "комиссиях",
            "процент", "процента", "проценту", "процентом", "проценте", "проценты", "процентов", "процентам", "процентами",
            "процентах",
        )
        private val WORD = Regex("[a-zа-яё]+")

        /** A fee or interest to the bank: category «Услуги банка» or a whole fee word in the description (5). */
        fun isFee(op: Operation, category: Category?): Boolean =
            category?.name == "Услуги банка" || WORD.findAll(op.name.lowercase().replace('ё', 'е')).any { it.value in FEE_WORDS }
        const val SELECTION_UNCATEGORIZED = "s_uncategorized"
        /** Category rows: by amount descending, equal amounts by name in code points (docs/calc.md, section 10). */
        private val BY_AMOUNT_THEN_NAME = compareByDescending<CategoryAmount> { it.amount.minor }.thenBy { it.categoryName }
        const val SELECTION_BANK_FEES = "s_bank_fees"
        const val SELECTION_REGULAR_ALL = "s_regular_all"
    }
}
