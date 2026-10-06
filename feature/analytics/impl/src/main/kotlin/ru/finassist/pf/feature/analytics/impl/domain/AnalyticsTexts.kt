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

    /** «на 17% больше, чем август к этому дню»; «столько же, сколько …». */
    fun comparison(current: Money, cmp: Metric?, periodType: PeriodTypeCode): String? {
        val prev = cmp?.takeIf { it.isReady }?.value ?: return null
        val range = cmp.range
        val prevName = range?.let { prevName(YearMonth.from(it.from), periodType) } ?: "прошлый период"
        if (prev.minor == 0L) return "В прошлом периоде к этому дню расходов не было"
        val pct = ((current.minor - prev.minor) * 100.0 / abs(prev.minor)).roundToInt()
        return when {
            pct == 0 -> "Столько же, сколько $prevName к этому дню"
            pct > 0 -> "На $pct% больше, чем $prevName к этому дню"
            else -> "На ${-pct}% меньше, чем $prevName к этому дню"
        }
    }

    private fun prevName(month: YearMonth, periodType: PeriodTypeCode): String = when (periodType) {
        PeriodTypeCode.MONTH, PeriodTypeCode.UNKNOWN -> RussianDates.monthNominative(month.month)
        PeriodTypeCode.QUARTER -> "прошлый квартал"
        PeriodTypeCode.YEAR -> "прошлый год"
    }

    /** «Обычно 88 900 ₽ · апрель — август». */
    fun typical(t: Metric?): String? {
        val v = t?.takeIf { it.isReady }?.value ?: return null
        val basis = t.basis?.range?.let { r -> " · " + RussianDates.monthRange(YearMonth.from(r.from), YearMonth.from(r.to.minusDays(1))) }.orEmpty()
        return "Обычно ${v.format()}$basis"
    }

    /** «в августе — 2 890 ₽». */
    fun dailyComparison(cmp: Metric?): String? {
        val v = cmp?.takeIf { it.isReady }?.value ?: return null
        val month = cmp.range?.let { YearMonth.from(it.from) } ?: return "Раньше — ${v.format()}"
        return "${RussianDates.monthPrepositional(month.month).replaceFirstChar { it.uppercase() }} — ${v.format()}"
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
}
