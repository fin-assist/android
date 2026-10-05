package ru.finassist.pf.core.mockbackend.engine

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * How operations enter analytics (api.md §6, mvp-scope «Аналитика»).
 *  - Own-transfer pairs and the «Между своими счетами» category are excluded in both modes.
 *  - `without` additionally drops the «Переводы» category in both directions.
 *  - A refund is an expense line of the category it reduces (negative), in its own month; it is never income.
 */
class AccountingView(private val ledger: Ledger, private val zone: ZoneId, val withTransfers: Boolean) {

    /** One analytics line: signed expense or income attributed to a category on a day. */
    data class Line(val op: Operation, val day: LocalDate, val categoryId: String?, val isExpense: Boolean, val signedAmount: Long)

    val lines: List<Line> = ledger.operations.values.mapNotNull { op ->
        val day = op.tx.postedAt.atZoneSameInstant(zone).toLocalDate()
        when (val a = op.accounting) {
            is Accounting.OwnTransfer -> null
            is Accounting.Refund -> Line(op, day, a.reducesCategoryId, true, -op.tx.amount)
            Accounting.Regular -> when {
                op.categoryId == Categories.OWN_TRANSFER -> null
                !withTransfers && op.categoryId == Categories.TRANSFERS -> null
                op.direction == Direction.Debit -> Line(op, day, op.categoryId, true, op.tx.amount)
                else -> Line(op, day, op.categoryId, false, op.tx.amount)
            }
        }
    }

    fun inRange(from: LocalDate, toInclusive: LocalDate): List<Line> = lines.filter { it.day >= from && it.day <= toInclusive }
    fun inMonth(ym: YearMonth): List<Line> = inRange(ym.atDay(1), ym.atEndOfMonth())

    fun expense(lines: List<Line>): Long = lines.filter { it.isExpense }.sumOf { it.signedAmount }
    fun income(lines: List<Line>): Long = lines.filter { !it.isExpense }.sumOf { it.signedAmount }
}
