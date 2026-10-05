package ru.finassist.pf.core.common.money

/** Amount in kopecks. Operation amounts are non-negative (direction is `kind`); analytics amounts are signed. */
@JvmInline
value class Money(val kopecks: Long) : Comparable<Money> {
    val rubles: Long get() = kopecks / 100
    val isNegative: Boolean get() = kopecks < 0
    operator fun plus(other: Money) = Money(kopecks + other.kopecks)
    operator fun minus(other: Money) = Money(kopecks - other.kopecks)
    operator fun unaryMinus() = Money(-kopecks)
    override fun compareTo(other: Money): Int = kopecks.compareTo(other.kopecks)

    companion object {
        val ZERO = Money(0)
    }
}

/** How the sign is rendered. `Expense` shows "−", `Income` shows "+", `Plain` shows a minus only for negatives. */
enum class SignStyle { Plain, Expense, Income }

object MoneyFormat {
    private const val NBSP = ' '
    private const val MINUS = '−'
    private const val RUB = "₽"

    /**
     * Formats to the design-system shape: "−84 320 ₽", "+1 250 ₽", "20 000 ₽".
     * Kopecks are shown only when non-zero ("1 520,50 ₽") — statements carry them rarely and the mockups hide them.
     */
    fun rub(money: Money, style: SignStyle = SignStyle.Plain): String {
        val abs = kotlin.math.abs(money.kopecks)
        val whole = abs / 100
        val frac = abs % 100
        val digits = groupThousands(whole)
        val body = if (frac == 0L) digits else "$digits,${frac.toString().padStart(2, '0')}"
        val sign = when {
            style == SignStyle.Expense && money.kopecks != 0L -> MINUS.toString()
            style == SignStyle.Income && money.kopecks != 0L -> "+"
            money.isNegative -> MINUS.toString()
            else -> ""
        }
        return "$sign$body$NBSP$RUB"
    }

    /** Signed analytics value: negative numbers get "−", positives nothing. */
    fun signed(money: Money): String = rub(money, SignStyle.Plain)

    fun groupThousands(value: Long): String {
        val s = value.toString()
        val sb = StringBuilder()
        s.forEachIndexed { i, c ->
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(NBSP)
            sb.append(c)
        }
        return sb.toString()
    }
}
