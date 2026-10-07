package ru.finassist.pf.feature.analytics.impl.domain

import ru.finassist.pf.core.api.model.AnalyticsPeriodInfo
import ru.finassist.pf.core.api.model.AnalyticsState
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.Metric
import ru.finassist.pf.core.api.model.MetricLock
import ru.finassist.pf.core.api.model.MonthlyPoint
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.countWithNoun
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Texts of the Analytics screen. The server decides thresholds, locks and coverage; the client only phrases
 * them (api.md 6.1). Pure functions — unit-tested.
 */
object AnalyticsTexts {

    private val QUARTERS = listOf("I", "II", "III", "IV")

    /** «Сентябрь 2026», «III квартал 2026», «2026». */
    fun periodTitle(key: PeriodKey): String {
        val v = key.value
        return when {
            Regex("""^\d{4}-Q[1-4]$""").matches(v) -> "${QUARTERS[v.last().digitToInt() - 1]} квартал ${v.take(4)}"
            Regex("""^\d{4}-\d{2}$""").matches(v) -> RussianDates.monthTitle(YearMonth.parse(v))
            else -> v
        }
    }

    fun unitName(period: PeriodTypeCode): String = when (period) {
        PeriodTypeCode.QUARTER -> "квартал"
        PeriodTypeCode.YEAR -> "год"
        else -> "месяц"
    }

    /** Caption under the period: «1–25 сентября», «июль без 11–19-го»; null for a complete period. */
    fun coverage(p: AnalyticsPeriodInfo): String? = when (p.coverage.effective) {
        Coverage.COMPLETE -> null
        Coverage.NO_DATA -> "За этот период данных нет"
        else -> {
            val from = p.dataFrom?.toLocalDate()
            val to = p.dataTo?.toLocalDate()
            val base = if (from != null && to != null) "Данные за ${RussianDates.dayRange(from, to)}" else "Данные за часть периода"
            val gap = p.gaps.firstOrNull()?.let { g -> ", без ${RussianDates.dayRange(g.from.toLocalDate(), RussianDates.lastDayOf(g))}" }.orEmpty()
            val suffix = if (p.isCurrent) "" else " · период неполный"
            base + gap + suffix
        }
    }

    /**
     * Lock text: «Нужно 3 полных месяца, есть 1»; with no full months at all — «…пока есть только 1–25 сентября»
     * from `state.data_from` / `data_to`.
     */
    fun lock(lock: MetricLock?, state: AnalyticsState?): String {
        if (lock == null) return "Пока недоступно"
        return when (lock.reason) {
            LockReason.NEED_FULL_MONTHS -> {
                val req = lock.required ?: return "Нужно больше полных месяцев"
                val need = "Нужно ${countWithNoun(req, "полный месяц", "полных месяца", "полных месяцев")}"
                val avail = lock.available ?: 0
                if (avail == 0 && state != null) {
                    "$need — пока есть только ${RussianDates.dayRange(state.dataFrom.toLocalDate(), state.dataTo.toLocalDate())}"
                } else {
                    "$need, есть $avail"
                }
            }
            LockReason.NEED_MONTHS_WITH_DATA -> {
                val req = lock.required ?: return "Нужно больше месяцев с данными"
                "Нужно ${countWithNoun(req, "месяц", "месяца", "месяцев")} с данными, есть ${lock.available ?: 0}"
            }
            LockReason.TOO_EARLY_IN_MONTH ->
                lock.availableFrom?.let { "Посчитаем с ${RussianDates.day(it.toLocalDate())}" } ?: "Рано считать — мало дней"
            LockReason.STALE_DATA -> "Выписка устарела — загрузите новую"
            LockReason.UNKNOWN -> "Пока недоступно"
        }
    }

    /**
     * Line under the expense chart. Month: «Сентябрь к 25-му — 84 320 ₽, на 17% больше, чем август к этому дню»;
     * quarter and year keep the short form «На 17% больше, чем прошлый год к этому дню».
     */
    fun comparison(current: Money, cmp: Metric?, periodType: PeriodTypeCode, key: PeriodKey? = null): String? {
        val prev = cmp?.takeIf { it.isReady }?.value ?: return null
        val range = cmp.range
        val prevName = range?.let { prevName(YearMonth.from(it.from), periodType) } ?: "прошлый период"
        if (prev.minor == 0L) return "В прошлом периоде к этому дню расходов не было"
        val pct = ((current.minor - prev.minor) * 100.0 / abs(prev.minor)).roundToInt()
        val diff = when {
            pct == 0 -> "столько же, сколько $prevName к этому дню"
            pct > 0 -> "на $pct% больше, чем $prevName к этому дню"
            else -> "на ${-pct}% меньше, чем $prevName к этому дню"
        }
        val month = key?.value?.takeIf { Regex("""^\d{4}-\d{2}$""").matches(it) }?.let(YearMonth::parse)
        val day = range?.let { RussianDates.lastDayOf(it).dayOfMonth }
        if (periodType != PeriodTypeCode.MONTH || month == null || day == null) return diff.replaceFirstChar { it.uppercase() }
        val name = RussianDates.monthNominative(month.month).replaceFirstChar { it.uppercase() }
        return "$name к $day-му ${RussianDates.EM_DASH} ${current.format()}, $diff"
    }

    private fun prevName(month: YearMonth, periodType: PeriodTypeCode): String = when (periodType) {
        PeriodTypeCode.MONTH, PeriodTypeCode.UNKNOWN -> RussianDates.monthNominative(month.month)
        PeriodTypeCode.QUARTER -> "прошлый квартал"
        PeriodTypeCode.YEAR -> "прошлый год"
    }

    /** «Среднее за апрель — август — 88 900 ₽». */
    fun typical(t: Metric?): String? {
        val v = t?.takeIf { it.isReady }?.value ?: return null
        val r = t.basis?.range ?: return "Обычно ${v.format()}"
        val months = RussianDates.monthRange(YearMonth.from(r.from), YearMonth.from(r.to.minusDays(1)))
        return "Среднее за $months ${RussianDates.EM_DASH} ${v.format()}"
    }

    /** «в августе — 2 890 ₽» under «Расходы в день». */
    fun dailyComparison(cmp: Metric?): String? {
        val v = cmp?.takeIf { it.isReady }?.value ?: return null
        val month = cmp.range?.let { YearMonth.from(it.from) } ?: return "раньше ${RussianDates.EM_DASH} ${v.format()}"
        return "в ${RussianDates.monthPrepositional(month.month)} ${RussianDates.EM_DASH} ${v.format()}"
    }

    /** «в среднем за апрель — сентябрь» under «Расходы в день» for a quarter or a year. */
    fun dailyAverageOver(p: AnalyticsPeriodInfo): String? {
        val from = p.dataFrom ?: return null
        val to = p.dataTo ?: return null
        return "в среднем за ${RussianDates.monthRange(YearMonth.from(from), YearMonth.from(to.minusSeconds(1)))}"
    }

    /** Note of the forecast tile, label is always «Прогноз расходов»: «на сентябрь», «на III квартал», «на 2026 год». */
    fun forecastNote(key: PeriodKey): String {
        val v = key.value
        return when {
            Regex("""^\d{4}-Q[1-4]$""").matches(v) -> "на ${QUARTERS[v.last().digitToInt() - 1]} квартал"
            Regex("""^\d{4}-\d{2}$""").matches(v) -> "на ${RussianDates.monthNominative(YearMonth.parse(v).month)}"
            else -> "на $v год"
        }
    }

    /** Caption above the tiles of an incomplete quarter or year: «Апрель — сентябрь 2026 · год неполный». */
    fun partialPeriod(p: AnalyticsPeriodInfo, period: PeriodTypeCode): String? {
        if (period == PeriodTypeCode.MONTH || p.coverage.effective == Coverage.COMPLETE) return null
        if (p.coverage.effective == Coverage.NO_DATA) return "За этот период данных нет"
        val from = p.dataFrom?.let(YearMonth::from) ?: return null
        val to = p.dataTo?.let { YearMonth.from(it.minusSeconds(1)) } ?: return null
        val months = RussianDates.monthRange(from, to).replaceFirstChar { it.uppercase() }
        val year = if (from.year == to.year) " ${to.year}" else ""
        val unit = if (period == PeriodTypeCode.QUARTER) "квартал неполный" else "год неполный"
        return "$months$year · $unit"
    }

    /** Text under the «Доходы минус расходы» amount at the end of the screen. */
    fun balanceExplained(balance: Metric, withChart: Boolean): String {
        val share = balance.shareOfIncome ?: return "Доходов за период нет"
        val pct = (share * 100).roundToInt()
        val head = if (pct < 0) "${-pct}% дохода: на столько расходы по выписке больше доходов"
        else "$pct% дохода: на столько доходы по выписке больше расходов"
        val tail = if (withChart) "На графике ${RussianDates.EM_DASH} та же доля по месяцам"
        else "График по месяцам появится, когда в выписке будет хотя бы один полный месяц"
        return "$head. $tail"
    }

    /** «В 2,3 раза больше обычного — обычно около 3 100 ₽ (апрель — август)». */
    fun notable(amount: Money, typical: Money, basis: DateRange?): String {
        val months = basis?.let { " (${RussianDates.monthRange(YearMonth.from(it.from), YearMonth.from(it.to.minusDays(1)))})" }.orEmpty()
        val usual = "обычно около ${typical.format()}$months"
        if (typical.minor <= 0) return usual.replaceFirstChar { it.uppercase() }
        val ratio = "%.1f".format(amount.minor.toDouble() / typical.minor).replace('.', ',')
        return "В $ratio раза больше обычного ${RussianDates.EM_DASH} $usual"
    }

    /** «в месяц · кофе и перекусы: 23 раза по 280 ₽ — около 77 000 ₽ в год». */
    fun smallFrequent(title: String, count: Int, average: Money, yearly: Money): String =
        "в месяц · ${title.replaceFirstChar { it.lowercase() }}: ${countWithNoun(count, "раз", "раза", "раз")} по ${average.format()} " +
            "${RussianDates.EM_DASH} около ${yearly.format()} в год"

    /** «Больше всего — в июле: 96 300 ₽» under the chart of a quarter or a year. */
    fun peakMonth(points: List<MonthlyPoint>): String? {
        val top = points.filter { it.expense != null }.maxByOrNull { it.expense!!.minor } ?: return null
        val month = YearMonth.parse(top.month).month
        return "Больше всего ${RussianDates.EM_DASH} в ${RussianDates.monthPrepositional(month)}: ${top.expense!!.format()}"
    }

    /** «46% дохода» / «−12% · потратили больше, чем получили» / «Доходов за период нет». */
    fun share(balance: Metric): String {
        val share = balance.shareOfIncome ?: return "Доходов за период нет"
        val pct = (share * 100).roundToInt()
        return if (pct < 0) "${Money.MINUS}${-pct}% · потратили больше, чем получили" else "$pct% дохода"
    }

    /** Bar label and partial note: «сен», «по 25-е», «с 29-го», «без 11–19-го». */
    fun barNote(p: MonthlyPoint): String? {
        if (p.coverage.effective == Coverage.COMPLETE || p.expense == null) return null
        val monthStart = p.range.from.toLocalDate()
        val monthEnd = RussianDates.lastDayOf(p.range)
        val from = p.dataFrom?.toLocalDate()
        val to = p.dataTo?.toLocalDate()
        p.gaps.firstOrNull()?.let { g ->
            return "без ${g.from.dayOfMonth}${RussianDates.EN_DASH}${RussianDates.lastDayOf(g).dayOfMonth}-го"
        }
        return when {
            to != null && to.isBefore(monthEnd) -> "по ${to.dayOfMonth}-е"
            from != null && from.isAfter(monthStart) -> "с ${from.dayOfMonth}-го"
            else -> null
        }
    }

    /** Share of income left after expenses for a month; null without data or without income. */
    fun balanceShare(p: MonthlyPoint): Double? {
        val income = p.income ?: return null
        val expense = p.expense ?: return null
        if (income.minor <= 0) return null
        return (income.minor - expense.minor).toDouble() / income.minor
    }
}
