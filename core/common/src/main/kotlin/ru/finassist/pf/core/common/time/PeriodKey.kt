package ru.finassist.pf.core.common.time

import java.time.YearMonth

/** Period type for analytics. Open set on the wire; the client only ever requests the values it knows. */
enum class PeriodType(val wire: String) {
    Month("month"), Quarter("quarter"), Year("year");

    companion object {
        fun fromWire(value: String?): PeriodType? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Calendar period key: `YYYY-MM`, `YYYY-Qn`, `YYYY`. Kept as the wire string plus a parsed view,
 * so unknown future formats (e.g. weeks) still round-trip untouched.
 */
sealed interface PeriodKey {
    val wire: String

    data class Month(val yearMonth: YearMonth) : PeriodKey {
        override val wire: String get() = "%04d-%02d".format(yearMonth.year, yearMonth.monthValue)
    }

    data class Quarter(val year: Int, val quarter: Int) : PeriodKey {
        override val wire: String get() = "$year-Q$quarter"
    }

    data class Year(val year: Int) : PeriodKey {
        override val wire: String get() = year.toString()
    }

    data class Unknown(override val wire: String) : PeriodKey

    companion object {
        private val MONTH = Regex("""^(\d{4})-(0[1-9]|1[0-2])$""")
        private val QUARTER = Regex("""^(\d{4})-Q([1-4])$""")
        private val YEAR = Regex("""^(\d{4})$""")

        fun parse(wire: String): PeriodKey {
            MONTH.matchEntire(wire)?.let { return Month(YearMonth.of(it.groupValues[1].toInt(), it.groupValues[2].toInt())) }
            QUARTER.matchEntire(wire)?.let { return Quarter(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            YEAR.matchEntire(wire)?.let { return Year(it.groupValues[1].toInt()) }
            return Unknown(wire)
        }
    }
}
