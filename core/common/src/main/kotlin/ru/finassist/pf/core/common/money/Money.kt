package ru.finassist.pf.core.common.money

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Amount in minor units (kopecks for RUB), signed. API operations are non-negative with direction in `kind`;
 * analytics and assistant amounts are signed (a refund can push a category below zero).
 */
@JvmInline
@Serializable
value class Money(val minor: Long) : Comparable<Money> {
    val isNegative: Boolean get() = minor < 0
    val isZero: Boolean get() = minor == 0L

    operator fun plus(other: Money) = Money(minor + other.minor)
    operator fun minus(other: Money) = Money(minor - other.minor)
    operator fun unaryMinus() = Money(-minor)
    operator fun times(factor: Int) = Money(minor * factor)
    fun abs() = Money(abs(minor))

    override fun compareTo(other: Money): Int = minor.compareTo(other.minor)

    /**
     * Formats as the design system shows money: `−84 320 ₽`, `+1 250 ₽`, `2 340,50 ₽`.
     * Thousands are separated with a narrow no-break space, minus is U+2212, kopecks are shown only when non-zero.
     *
     * @param sign how to render the sign: [Sign.AUTO] shows `−` for negatives only, [Sign.ALWAYS] adds `+`,
     * [Sign.NONE] drops it (own transfers in a list).
     */
    fun format(sign: Sign = Sign.AUTO, withCurrency: Boolean = true): String {
        val absolute = abs(minor)
        val rubles = absolute / 100
        val kopecks = (absolute % 100).toInt()
        val digits = groupThousands(rubles)
        val body = if (kopecks == 0) digits else "$digits,${kopecks.toString().padStart(2, '0')}"
        val prefix = when {
            sign == Sign.NONE -> ""
            minor < 0 -> MINUS
            sign == Sign.ALWAYS && minor > 0 -> "+"
            else -> ""
        }
        return if (withCurrency) "$prefix$body${NBSP}₽" else "$prefix$body"
    }

    enum class Sign { AUTO, ALWAYS, NONE }

    companion object {
        const val MINUS = "−"
        const val NBSP = " "

        val ZERO = Money(0)

        fun ofRubles(rubles: Long, kopecks: Int = 0) = Money(rubles * 100 + kopecks)

        private fun groupThousands(value: Long): String {
            val s = value.toString()
            val sb = StringBuilder()
            s.forEachIndexed { i, c ->
                if (i > 0 && (s.length - i) % 3 == 0) sb.append(NBSP)
                sb.append(c)
            }
            return sb.toString()
        }
    }
}

fun Long.asMoney() = Money(this)
fun Iterable<Money>.sum(): Money = fold(Money.ZERO) { acc, m -> acc + m }
