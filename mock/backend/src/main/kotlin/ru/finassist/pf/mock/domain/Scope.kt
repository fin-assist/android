package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.TransferMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Which operations analytics counts, and with which sign. Own-transfer pairs are out in both modes; `without`
 * also drops the «Переводы» category (both directions). A refund is a negative expense in the category it
 * reduces (or in «Без категории»), never an income.
 */
class Scope(private val ledger: Ledger, private val catalog: Catalog, val zone: ZoneId) {

    /** Expense line: category (null = «Без категории») and signed kopecks (refunds negative). */
    data class ExpenseEntry(val op: Operation, val category: Category?, val signedMinor: Long)

    fun visible(mode: TransferMode): List<Operation> = ledger.all.filter { op ->
        op.pairId == null && (mode != TransferMode.WITHOUT || op.category.id != catalog.transfers.id)
    }

    fun inRange(ops: List<Operation>, from: OffsetDateTime, to: OffsetDateTime) =
        ops.filter { !it.occurredAt.isBefore(from) && it.occurredAt.isBefore(to) }

    fun inDays(ops: List<Operation>, from: LocalDate, toExclusive: LocalDate) =
        ops.filter { val d = it.day(); !d.isBefore(from) && d.isBefore(toExclusive) }

    fun Operation.day(): LocalDate = occurredAt.atZoneSameInstant(zone).toLocalDate()
    fun dayOf(op: Operation): LocalDate = op.day()

    fun expenses(ops: List<Operation>): List<ExpenseEntry> = ops.mapNotNull { op ->
        when {
            op.isRefund -> ExpenseEntry(op, op.refundTarget, -op.amountMinor)
            op.isDebit && !op.isOwnTransferCategory -> ExpenseEntry(op, op.category, op.amountMinor)
            else -> null
        }
    }

    fun incomes(ops: List<Operation>): List<Operation> = ops.filter { !it.isDebit && !it.isRefund && !it.isOwnTransferCategory }

    fun expenseTotal(ops: List<Operation>): Long = expenses(ops).sumOf { it.signedMinor }
    fun incomeTotal(ops: List<Operation>): Long = incomes(ops).sumOf { it.amountMinor }
}
