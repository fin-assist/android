package ru.finassist.pf.core.blocks

import ru.finassist.pf.core.api.model.ChartPoint
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.ValueKind
import ru.finassist.pf.core.common.money.Money
import kotlin.math.abs
import kotlin.math.roundToInt

/** Formatting of block values (api.md «Блоки» → «Значения»). Pure functions — unit-tested. */
object BlockFormat {
    /** The shared text of a lock the service left empty (grpc-api.md 5.3, api.md «Блоки»). */
    const val LOCKED_FALLBACK = "Пока недоступно"

    /** Text of [lock]: the server's, or [LOCKED_FALLBACK] when it is empty; null without a lock. */
    fun lockText(lock: ru.finassist.pf.core.api.model.BlockLock?): String? = lock?.text?.ifBlank { LOCKED_FALLBACK }


    /**
     * `value` of [kind]: an amount in [currency] («−84 320 ₽»), a share («46%», «−12%»), a count; null for an unknown
     * kind or no value — the caller shows the captions only. [approx] puts «≈» in front, [showPlus] «+» on a positive.
     */
    fun value(value: Double?, kind: ValueKind?, currency: String = Money.RUB, approx: Boolean = false, showPlus: Boolean = false): String? {
        value ?: return null
        val text = when (kind) {
            ValueKind.AMOUNT -> Money(value.toLong()).format(currency, if (showPlus) Money.Sign.ALWAYS else Money.Sign.AUTO)
            ValueKind.PERCENT -> percent(value)
            ValueKind.COUNT -> value.toLong().toString()
            ValueKind.UNKNOWN, null -> return null
        }
        return if (approx) "≈$text" else text
    }

    /** «около 101 200 ₽» for a screen reader where the screen shows «≈101 200 ₽». */
    fun spoken(value: Double?, kind: ValueKind?, currency: String = Money.RUB, approx: Boolean = false, showPlus: Boolean = false): String? =
        value(value, kind, currency, approx = false, showPlus = showPlus)?.let { if (approx) "около $it" else it }

    /** A share 0–1 as «46%» / «−12%». */
    fun percent(share: Double): String {
        val pct = (share * 100).roundToInt()
        return if (pct < 0) "${Money.MINUS}${abs(pct)}%" else "$pct%"
    }

    /**
     * Filled share of a partial bar: the days from `data_from` to `data_to` (inclusive) over the days of the period's
     * month. September with data to the 25th → 25/30. `null` for a complete point.
     */
    fun partialFill(p: ChartPoint): Float? {
        if (p.coverage.effective != Coverage.PARTIAL) return null
        val monthStart = p.range.from.toLocalDate()
        val monthEnd = p.range.to.toLocalDate().minusDays(1)
        val from = p.dataFrom?.toLocalDate()?.coerceAtLeast(monthStart) ?: monthStart
        val to = p.dataTo?.toLocalDate()?.coerceAtMost(monthEnd) ?: monthEnd
        val days = monthStart.lengthOfMonth()
        val covered = (to.toEpochDay() - from.toEpochDay() + 1).coerceIn(0, days.toLong())
        return covered.toFloat() / days
    }
}
