package ru.finassist.pf.core.common.time

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId

/** Half-open interval `[from, to)`, as every range in the API. */
@Serializable
data class DateRange(
    @Serializable(with = OffsetDateTimeSerializer::class) val from: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class) val to: OffsetDateTime,
) {
    operator fun contains(moment: OffsetDateTime): Boolean = !moment.isBefore(from) && moment.isBefore(to)
}

/** Period type of the Analytics screen. Open set in the API; the client only ever requests known values. */
enum class PeriodType(val code: String) {
    MONTH("month"), QUARTER("quarter"), YEAR("year");

    companion object {
        fun fromCode(code: String?): PeriodType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Calendar period key as the API uses it: `2026-09` (month), `2026-Q3` (quarter), `2026` (year).
 * Parsed on the client only to build labels and to compute the neighbouring key for the mock backend;
 * it is always sent back to the server verbatim.
 */
@JvmInline
@Serializable
value class PeriodKey(val value: String) {
    val type: PeriodType
        get() = when {
            QUARTER.matches(value) -> PeriodType.QUARTER
            MONTH.matches(value) -> PeriodType.MONTH
            else -> PeriodType.YEAR
        }

    val year: Int get() = value.substring(0, 4).toInt()

    /** 1..12 for a month key, 1..4 for a quarter key, null for a year key. */
    val index: Int? get() = when (type) {
        PeriodType.MONTH -> value.substring(5, 7).toInt()
        PeriodType.QUARTER -> value.substring(6, 7).toInt()
        PeriodType.YEAR -> null
    }

    /** First day of the period. */
    fun start(): LocalDate = when (type) {
        PeriodType.MONTH -> LocalDate.of(year, index!!, 1)
        PeriodType.QUARTER -> LocalDate.of(year, (index!! - 1) * 3 + 1, 1)
        PeriodType.YEAR -> LocalDate.of(year, 1, 1)
    }

    /** First day after the period (exclusive end). */
    fun endExclusive(): LocalDate = when (type) {
        PeriodType.MONTH -> start().plusMonths(1)
        PeriodType.QUARTER -> start().plusMonths(3)
        PeriodType.YEAR -> start().plusYears(1)
    }

    fun toRange(zone: ZoneId): DateRange = DateRange(
        from = start().atStartOfDay(zone).toOffsetDateTime(),
        to = endExclusive().atStartOfDay(zone).toOffsetDateTime(),
    )

    fun next(): PeriodKey = of(type, endExclusive())
    fun previous(): PeriodKey = of(type, start().minusDays(1))

    override fun toString(): String = value

    companion object {
        private val MONTH = Regex("""^\d{4}-(0[1-9]|1[0-2])$""")
        private val QUARTER = Regex("""^\d{4}-Q[1-4]$""")
        private val ANY = Regex("""^\d{4}(-(0[1-9]|1[0-2])|-Q[1-4])?$""")

        fun isValid(value: String) = ANY.matches(value)

        fun of(type: PeriodType, date: LocalDate): PeriodKey = when (type) {
            PeriodType.MONTH -> PeriodKey("%04d-%02d".format(date.year, date.monthValue))
            PeriodType.QUARTER -> PeriodKey("%04d-Q%d".format(date.year, (date.monthValue - 1) / 3 + 1))
            PeriodType.YEAR -> PeriodKey("%04d".format(date.year))
        }

        fun ofMonth(month: YearMonth) = PeriodKey("%04d-%02d".format(month.year, month.monthValue))
    }
}

/** `YYYY-MM` as the API `Month` scalar. */
fun YearMonth.toApiMonth(): String = "%04d-%02d".format(year, monthValue)

fun String.toYearMonth(): YearMonth = YearMonth.parse(this)
