package ru.finassist.pf.feature.analytics.impl.domain

import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.money.MoneyFormat
import ru.finassist.pf.core.common.money.SignStyle
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.PeriodType
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.RussianDates.plural
import ru.finassist.pf.core.navigation.OperationsFilter
import ru.finassist.pf.core.network.codes.Coverage
import ru.finassist.pf.core.network.codes.LockReason
import ru.finassist.pf.core.network.codes.MetricStatus
import ru.finassist.pf.core.network.dto.AnalyticsDto
import ru.finassist.pf.core.network.dto.AnalyticsPeriodItemDto
import ru.finassist.pf.core.network.dto.AnalyticsStateDto
import ru.finassist.pf.core.network.dto.MetricDto
import ru.finassist.pf.core.network.dto.MetricLockDto
import ru.finassist.pf.core.network.dto.MonthlyPointDto
import ru.finassist.pf.core.network.dto.OperationsFilterDto
import ru.finassist.pf.core.network.dto.RangeDto
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Text rules of the «Аналитика» screen (api.md 6.1, review-states-decisions.md). Everything here is pure: the
 * server decides thresholds and locks, the client only words them.
 */

private const val NBSP = ' '
private const val MINUS = '−'

fun money(v: Long?): String = v?.let { MoneyFormat.rub(Money(it)) } ?: "—"
fun expenseMoney(v: Long?): String = v?.let { MoneyFormat.rub(Money(it), SignStyle.Expense) } ?: "—"
fun incomeMoney(v: Long?): String = v?.let { MoneyFormat.rub(Money(it), SignStyle.Income) } ?: "—"
fun approx(v: Long): String = "около$NBSP" + MoneyFormat.rub(Money(v))

fun RangeDto.fromDate(): LocalDate = OffsetDateTime.parse(from).toLocalDate()
fun RangeDto.toDateExclusive(): LocalDate = OffsetDateTime.parse(to).toLocalDate()
fun RangeDto.lastDay(): LocalDate = toDateExclusive().minusDays(1)
fun String.toLocalDate(): LocalDate = OffsetDateTime.parse(this).toLocalDate()

fun OperationsFilterDto.toNav(): OperationsFilter = OperationsFilter(
    from = from, to = to, q = q, categoryId = categoryId, kind = kind?.name, amountFrom = amountFrom, amountTo = amountTo,
    transferMode = transferMode, selection = selection, selectionName = selectionName,
)

/** Genitive period for «за сентябрь», «за III квартал», «за 2026 год». */
fun periodGenitive(key: PeriodKey): String = when (key) {
    is PeriodKey.Month -> "за ${RussianDates.monthGenitive[key.yearMonth.monthValue - 1]}"
    is PeriodKey.Quarter -> "за ${listOf("I", "II", "III", "IV")[key.quarter - 1]}$NBSP" + "квартал"
    is PeriodKey.Year -> "за ${key.year}$NBSP" + "год"
    is PeriodKey.Unknown -> "за период"
}

/**
 * PeriodNav label: «Сентябрь 2026»; partial month — «1–25 сентября 2026»; month with gaps — «Июль 2026 · без 11–19-го»;
 * «III квартал 2026»; year — «2026» or «Апрель — сентябрь 2026 · год неполный».
 */
fun periodLabel(key: PeriodKey, coverage: Coverage, dataFrom: String?, dataTo: String?, gaps: List<RangeDto>): String {
    val from = dataFrom?.let { runCatching { it.toLocalDate() }.getOrNull() }
    val to = dataTo?.let { runCatching { it.toLocalDate() }.getOrNull() }
    return when (key) {
        is PeriodKey.Month -> {
            val title = RussianDates.monthTitle(key.yearMonth)
            when {
                coverage == Coverage.Complete || from == null || to == null -> title
                gaps.isNotEmpty() && from.dayOfMonth == 1 && to == key.yearMonth.atEndOfMonth() -> "$title · без ${gapsText(gaps)}"
                else -> {
                    val range = RussianDates.dayRange(from, to.plusDays(1))
                    "$range$NBSP${key.yearMonth.year}" + if (gaps.isNotEmpty()) " · без ${gapsText(gaps)}" else ""
                }
            }
        }
        is PeriodKey.Quarter -> RussianDates.quarterTitle(key.year, key.quarter) + if (coverage != Coverage.Complete) " · неполный" else ""
        is PeriodKey.Year -> when {
            coverage == Coverage.Complete || from == null || to == null -> key.year.toString()
            else -> RussianDates.monthRange(YearMonth.from(from), YearMonth.from(to)).replaceFirstChar { it.uppercase() } + " · год неполный"
        }
        is PeriodKey.Unknown -> key.wire
    }
}

/** «11–19-го», «11–19 июля и 2–3 августа». */
fun gapsText(gaps: List<RangeDto>): String = gaps.joinToString(" и ") { g ->
    val f = g.fromDate(); val l = g.lastDay()
    if (f.month == l.month) "${f.dayOfMonth}–${l.dayOfMonth}-го" else "${RussianDates.dayMonth(f)} — ${RussianDates.dayMonth(l)}"
}

/** Short period for «Всего · 1–25 сентября», «Расходов за 1–25 сентября нет». */
fun shortPeriod(dto: AnalyticsDto): String {
    val key = PeriodKey.parse(dto.params?.date ?: "")
    val p = dto.period
    val from = p?.dataFrom?.let { it.toLocalDate() }
    val to = p?.dataTo?.let { it.toLocalDate() }
    return when {
        key is PeriodKey.Month && Coverage.fromWire(p?.coverage) != Coverage.Complete && from != null && to != null -> RussianDates.dayRange(from, to.plusDays(1))
        key is PeriodKey.Month -> RussianDates.monthNominative[key.yearMonth.monthValue - 1]
        key is PeriodKey.Quarter -> RussianDates.monthRange(YearMonth.of(key.year, (key.quarter - 1) * 3 + 1), YearMonth.of(key.year, key.quarter * 3)).substringBeforeLast(NBSP)
        key is PeriodKey.Year && from != null && to != null && Coverage.fromWire(p?.coverage) != Coverage.Complete -> RussianDates.monthRange(YearMonth.from(from), YearMonth.from(to)).substringBeforeLast(NBSP)
        key is PeriodKey.Year -> "${key.year}$NBSP" + "год"
        else -> "период"
    }
}

/** Period item of the sheet: «Сентябрь 2026», «III квартал 2026», «2026» (+ «неполный»). */
fun periodItemLabel(item: AnalyticsPeriodItemDto): String = when (val k = PeriodKey.parse(item.key)) {
    is PeriodKey.Month -> RussianDates.monthTitle(k.yearMonth)
    is PeriodKey.Quarter -> RussianDates.quarterTitle(k.year, k.quarter)
    is PeriodKey.Year -> k.year.toString()
    is PeriodKey.Unknown -> k.wire
}

/** Lock text (review-states: «Нужно 3 полных месяца, есть 1»; at zero — «… — пока есть только 1–25 сентября»). */
fun lockText(lock: MetricLockDto?, state: AnalyticsStateDto?, unit: PeriodType = PeriodType.Month): String {
    if (lock == null) return "Пока не считаем"
    return when (LockReason.fromWire(lock.reason)) {
        LockReason.NeedFullMonths -> {
            val need = lock.required ?: return "Пока мало данных"
            val have = lock.available ?: 0
            val needText = "Нужно ${plural(need.toLong(), "полный месяц", "полных месяца", "полных месяцев")}"
            if (have == 0 && state != null) "$needText — пока есть только ${RussianDates.dayRange(state.dataFrom.toLocalDate(), state.dataTo.toLocalDate().plusDays(1))}"
            else "$needText, есть $have"
        }
        LockReason.NeedMonthsWithData -> {
            val need = lock.required ?: return "Пока мало данных"
            "Нужно ${plural(need.toLong(), "месяц с данными", "месяца с данными", "месяцев с данными")}, есть ${lock.available ?: 0}"
        }
        LockReason.TooEarlyInMonth -> lock.availableFrom?.let { "Появится с ${RussianDates.dayMonth(it.toLocalDate())}" } ?: "Пока рано для прогноза"
        LockReason.StaleData -> "Выписка устарела"
        LockReason.Unknown -> "Пока не считаем"
    }
}

/** «на 17% больше, чем август к этому дню» — percent is computed on the client (api.md tiles.expense.comparison). */
fun comparisonText(current: Long?, previous: MetricDto?, key: PeriodKey, dataTo: String?): String? {
    if (previous == null || MetricStatus.fromWire(previous.status) != MetricStatus.Ready) return null
    val cur = current ?: return null
    val prev = previous.value ?: return null
    val prevName = previous.range?.let { r -> periodName(PeriodKey.parse(keyOf(r, key))) } ?: "прошлый период"
    val day = dataTo?.let { runCatching { it.toLocalDate().dayOfMonth }.getOrNull() }
    val curName = periodName(key).replaceFirstChar { it.uppercase() }
    val head = if (key is PeriodKey.Month && day != null) "$curName к $day-му — ${expenseMoney(cur)}" else "$curName — ${expenseMoney(cur)}"
    val tail = when {
        prev == 0L -> "в $prevName расходов не было"
        else -> {
            val pct = (((abs(cur).toDouble() - abs(prev)) / abs(prev)) * 100).roundToInt()
            val suffix = if (key is PeriodKey.Month) " к этому дню" else ""
            when {
                pct > 0 -> "на $pct% больше, чем $prevName$suffix"
                pct < 0 -> "на ${-pct}% меньше, чем $prevName$suffix"
                else -> "столько же, сколько $prevName$suffix"
            }
        }
    }
    return "$head, $tail"
}

/** «Среднее за апрель — август — 88 900 ₽». */
fun typicalText(typical: MetricDto?): String? {
    if (typical == null || MetricStatus.fromWire(typical.status) != MetricStatus.Ready) return null
    val v = typical.value ?: return null
    val base = typical.basis?.range?.let { r -> RussianDates.monthRange(YearMonth.from(r.fromDate()), YearMonth.from(r.lastDay())).substringBeforeLast(NBSP) }
    return if (base != null) "Среднее за $base — ${expenseMoney(v)}" else "Обычно — ${expenseMoney(v)}"
}

/** «в августе — 2 890 ₽» for the daily tile. */
fun dailyComparisonText(c: MetricDto?): String? {
    if (c == null || MetricStatus.fromWire(c.status) != MetricStatus.Ready) return null
    val v = c.value ?: return null
    val r = c.range ?: return "в прошлом периоде — ${money(v)}"
    val ym = YearMonth.from(r.fromDate())
    val name = if (YearMonth.from(r.lastDay()) == ym) "в ${monthPrepositional(ym)}" else "в прошлом периоде"
    return "$name — ${money(v)}"
}

/** «46% дохода · по выписке», «−12% · потратили больше, чем получили», «Доходов за период нет». */
fun balanceNote(share: Double?): String = when {
    share == null -> "Доходов за период нет"
    share < 0 -> "$MINUS${(-share * 100).roundToInt()}% · потратили больше, чем получили"
    else -> "${(share * 100).roundToInt()}% дохода · по выписке"
}

private fun keyOf(r: RangeDto, like: PeriodKey): String {
    val f = r.fromDate()
    return when (like) {
        is PeriodKey.Quarter -> "${f.year}-Q${(f.monthValue - 1) / 3 + 1}"
        is PeriodKey.Year -> f.year.toString()
        else -> "%04d-%02d".format(f.year, f.monthValue)
    }
}

/** Nominative name for sentences: «сентябрь», «III квартал», «2026 год». */
fun periodName(key: PeriodKey): String = when (key) {
    is PeriodKey.Month -> RussianDates.monthNominative[key.yearMonth.monthValue - 1]
    is PeriodKey.Quarter -> "${listOf("I", "II", "III", "IV")[key.quarter - 1]}$NBSP" + "квартал"
    is PeriodKey.Year -> "${key.year}$NBSP" + "год"
    is PeriodKey.Unknown -> key.wire
}

private val monthPrepositionalForms = listOf("январе", "феврале", "марте", "апреле", "мае", "июне", "июле", "августе", "сентябре", "октябре", "ноябре", "декабре")
fun monthPrepositional(ym: YearMonth): String = monthPrepositionalForms[ym.monthValue - 1]

/** One chart column from a monthly point; partial months get an outline fill and a «по 25-е» note. */
data class ChartPoint(val label: String, val spoken: String, val value: Double?, val display: String?, val partialFill: Float?, val partialNote: String?)

fun chartPoint(p: MonthlyPointDto, value: Long?, format: (Long) -> String): ChartPoint {
    val ym = runCatching { YearMonth.parse(p.month) }.getOrElse { YearMonth.from(p.range.fromDate()) }
    val partial = Coverage.fromWire(p.coverage) == Coverage.Partial && value != null
    val from = p.dataFrom?.let { it.toLocalDate() }
    val to = p.dataTo?.let { it.toLocalDate() }
    val note = when {
        !partial -> null
        p.gaps.isNotEmpty() && from?.dayOfMonth == 1 && to == ym.atEndOfMonth() -> "без ${gapsText(p.gaps)}"
        from != null && from.dayOfMonth > 1 && to == ym.atEndOfMonth() -> "с ${from.dayOfMonth}-го"
        to != null -> "по ${to.dayOfMonth}-е"
        else -> "неполный"
    }
    val fill = if (partial && to != null) (to.dayOfMonth.toFloat() / ym.lengthOfMonth()) else null
    return ChartPoint(
        label = RussianDates.monthShort[ym.monthValue - 1],
        spoken = RussianDates.monthNominative[ym.monthValue - 1] + if (note != null) ", $note" else "",
        value = value?.toDouble(),
        display = if (value == null) "нет данных" else format(value),
        partialFill = fill,
        partialNote = note,
    )
}

/** «Больше всего — в июле: 96 300 ₽» for quarter/year charts. */
fun peakText(points: List<MonthlyPointDto>): String? {
    val best = points.filter { it.expense != null }.maxByOrNull { abs(it.expense!!) } ?: return null
    val ym = runCatching { YearMonth.parse(best.month) }.getOrNull() ?: return null
    return "Больше всего — в ${monthPrepositional(ym)}: ${expenseMoney(best.expense)}"
}

/** Share of income kept, 0–1, for the «Доходы минус расходы по месяцам» chart; null without income. */
fun savingsShare(p: MonthlyPointDto): Double? {
    val inc = p.income ?: return null
    val exp = p.expense ?: return null
    if (inc <= 0) return null
    return (inc - abs(exp)).toDouble() / inc
}

/** «В 2,3 раза больше обычного — обычно около 3 100 ₽ (апрель — август)». */
fun notableText(amount: Long?, typical: Long?, basis: RangeDto?): String? {
    val a = amount ?: return null
    val t = typical ?: return null
    if (t == 0L) return "Обычно таких трат не было"
    val ratio = abs(a).toDouble() / abs(t)
    val ratioText = String.format(java.util.Locale("ru"), "%.1f", ratio).removeSuffix(",0")
    val base = basis?.let { " (${RussianDates.monthRange(YearMonth.from(it.fromDate()), YearMonth.from(it.lastDay())).substringBeforeLast(NBSP)})" } ?: ""
    return "В $ratioText раза больше обычного — обычно ${approx(abs(t))}$base"
}

/** «23 раза по 280 ₽ — около 77 000 ₽ в год». */
fun smallFrequentItemText(count: Int?, average: Long?, yearly: Long?): String =
    listOfNotNull(
        count?.let { "${plural(it.toLong(), "раз", "раза", "раз")}" + (average?.let { a -> " по ${money(a)}" } ?: "") },
        yearly?.let { "${approx(it)} в год" },
    ).joinToString(" — ")

/** «с апреля» — basis of regular payments. */
fun sinceText(basis: RangeDto?): String? = basis?.let { "с ${RussianDates.monthGenitive[it.fromDate().monthValue - 1]}" }

fun percentText(share: Double?): String? = share?.let { "${(it * 100).roundToInt()}%" }
