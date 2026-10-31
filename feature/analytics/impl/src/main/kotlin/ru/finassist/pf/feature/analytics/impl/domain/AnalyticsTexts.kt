package ru.finassist.pf.feature.analytics.impl.domain

import ru.finassist.pf.core.api.model.AnalyticsPeriodInfo
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.countWithNoun
import java.time.YearMonth
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Texts of the Analytics screen around the blocks: the period, its coverage, unread lines. The texts of the blocks
 * come from the server since FIN-4. Pure functions — unit-tested.
 */
object AnalyticsTexts {

    private val QUARTERS = listOf("I", "II", "III", "IV")

    /** «Сентябрь 2026», «III квартал 2026», «2026». */
    fun periodTitle(key: PeriodKey): String {
        val v = key.value
        return when {
            key == PeriodKey.ROLLING_YEAR -> "Последние 12 месяцев"
            key == PeriodKey.ROLLING_QUARTER -> "Последние 3 месяца"
            key == PeriodKey.ALL_TIME -> "За всё время"
            Regex("""^\d{4}-Q[1-4]$""").matches(v) -> "${QUARTERS[v.last().digitToInt() - 1]} квартал ${v.take(4)}"
            Regex("""^\d{4}-\d{2}$""").matches(v) -> RussianDates.monthTitle(YearMonth.parse(v))
            else -> v
        }
    }

    fun unitName(period: PeriodTypeCode): String = when (period) {
        PeriodTypeCode.QUARTER -> "квартал"
        PeriodTypeCode.YEAR -> "год"
        PeriodTypeCode.ROLLING_YEAR, PeriodTypeCode.ROLLING_QUARTER -> "период"
        PeriodTypeCode.ALL_TIME -> "период"
        else -> "месяц"
    }

    /** Caption under the period: «1–25 сентября», «июль без 11–19-го»; null for a complete period. */
    fun coverage(p: AnalyticsPeriodInfo, zone: ZoneId? = null): String? = when (p.coverage.effective) {
        Coverage.COMPLETE -> null
        Coverage.NO_DATA -> "За этот период данных нет"
        else -> {
            val from = p.dataFrom?.let { day(it, zone) }
            val to = p.dataTo?.let { day(it, zone) }
            val base = if (from != null && to != null) "Данные за ${RussianDates.dayRange(from, to)}" else "Данные за часть периода"
            val gap = p.gaps.firstOrNull()?.let { g ->
                val last = if (zone == null) RussianDates.lastDayOf(g) else day(g.to.minusNanos(1), zone)
                ", без ${RussianDates.dayRange(day(g.from, zone), last)}"
            }.orEmpty()
            val suffix = if (p.isCurrent) "" else " · период неполный"
            base + gap + suffix
        }
    }

    /** The factual covered-day envelope, with every year and internal gaps explained. */
    fun allTimeRange(p: AnalyticsPeriodInfo): String {
        val from = p.range.from.toLocalDate()
        val to = RussianDates.lastDayOf(p.range)
        val gap = if (p.gaps.isEmpty()) "" else " · есть пропуски в данных"
        return RussianDates.dayRange(from, to, withYear = true) + gap
    }

    /** Caption above the tiles of an incomplete quarter or year: «Апрель — сентябрь 2026 · год неполный». */
    fun partialPeriod(p: AnalyticsPeriodInfo, period: PeriodTypeCode, zone: ZoneId): String? {
        if (period.isRolling) return rollingRange(p, zone) +
            (coverage(p, zone)?.let { " · $it" } ?: "")
        if (period == PeriodTypeCode.ALL_TIME) return allTimeRange(p)
        if (period == PeriodTypeCode.MONTH || p.coverage.effective == Coverage.COMPLETE) return null
        if (p.coverage.effective == Coverage.NO_DATA) return "За этот период данных нет"
        val from = p.dataFrom?.let(YearMonth::from) ?: return null
        val to = p.dataTo?.let { YearMonth.from(it.minusSeconds(1)) } ?: return null
        val months = RussianDates.monthRange(from, to).replaceFirstChar { it.uppercase() }
        val year = if (from.year == to.year) " ${to.year}" else ""
        val unit = if (period == PeriodTypeCode.QUARTER) "квартал неполный" else "год неполный"
        return "$months$year · $unit"
    }

    /**
     * Format the full rolling range in the profile zone independently of coverage and wire offsets. Taking
     * the last instant also skips nonexistent civil dates at historical IANA transitions.
     */
    private fun rollingRange(p: AnalyticsPeriodInfo, zone: ZoneId): String = RussianDates.dayRange(
        day(p.range.from, zone), day(p.range.to.minusNanos(1), zone), withYear = true,
    )

    private fun day(moment: OffsetDateTime, zone: ZoneId?) =
        if (zone == null) moment.toLocalDate() else moment.atZoneSameInstant(zone).toLocalDate()

    /**
     * Period label: «Сентябрь 2026», but «1–25 сентября 2026» while the statement covers only part of the first
     * month (mockup `AnalyticsEarly`).
     */
    fun periodLabel(key: PeriodKey, period: AnalyticsPeriodInfo, type: PeriodTypeCode, early: Boolean): String {
        val from = period.dataFrom?.toLocalDate()
        val to = period.dataTo?.toLocalDate()
        return if (early && type == PeriodTypeCode.MONTH && from != null && to != null) {
            RussianDates.dayRange(from, to, withYear = true)
        } else {
            periodTitle(key)
        }
    }

    /**
     * The statement holds only part of one month: a month period with partial coverage and no earlier period to
     * go to. The screen then has no monthly chart and names the days instead of the month.
     */
    fun isEarly(type: PeriodTypeCode, period: AnalyticsPeriodInfo, hasPrevious: Boolean): Boolean =
        type == PeriodTypeCode.MONTH && !hasPrevious && period.coverage.effective == Coverage.PARTIAL

    /** «5 строк за сентябрь не прочитали — суммы могут быть неполными» (a quarter or a year: «за период»). */
    fun unreadLines(count: Int, type: PeriodTypeCode, period: AnalyticsPeriodInfo): String {
        val span = if (type == PeriodTypeCode.MONTH) "за ${RussianDates.monthNominative(period.range.from.month)}" else "за период"
        return "${countWithNoun(count, "строку", "строки", "строк")} $span не прочитали ${RussianDates.EM_DASH} суммы могут быть неполными"
    }
}
