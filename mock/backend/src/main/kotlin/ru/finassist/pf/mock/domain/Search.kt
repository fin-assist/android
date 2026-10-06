package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.SystemCategories
import ru.finassist.pf.core.api.model.TransferMode

/**
 * Search semantics of `listOperations` (api.md 4.1) over [Record]s. With `transfer_mode` the analytics rules
 * apply (pairs out, refunds are expenses of their target category); without it every record is searchable and
 * a refund is a plain credit.
 */
class Search(private val ledger: Ledger, private val catalog: Catalog, private val analytics: AnalyticsEngine) {

    class UnknownSelection(val selection: String) : RuntimeException(selection)

    fun apply(records: List<Record>, f: OperationsFilter): List<Record> {
        val mode = f.transferMode?.effective
        var result = records
        if (mode != null) {
            result = result.filter { r ->
                r is Record.Single && r.op.pairId == null && (mode != TransferMode.WITHOUT || r.op.category.id != catalog.transfers.id)
            }
        }
        f.kind?.let { kind ->
            result = result.filter { r ->
                when (r) {
                    is Record.Pair -> false
                    is Record.Single -> when (kind) {
                        OperationKindFilter.EXPENSE -> r.op.isDebit || (mode != null && r.op.isRefund)
                        OperationKindFilter.INCOME -> !r.op.isDebit && !(mode != null && r.op.isRefund)
                    } && !r.op.isOwnTransferCategory
                }
            }
        }
        f.categoryId?.let { id ->
            result = result.filter { r ->
                when (r) {
                    is Record.Pair -> id == SystemCategories.OWN_TRANSFER
                    is Record.Single ->
                        if (mode != null && r.op.isRefund) r.op.refundTarget?.id == id else r.op.category.id == id
                }
            }
        }
        f.amountFrom?.let { min -> result = result.filter { it.amountMinor >= min.minor } }
        f.amountTo?.let { max -> result = result.filter { it.amountMinor <= max.minor } }
        f.q?.trim()?.takeIf { it.isNotEmpty() }?.let { q -> result = result.filter { matchesQuery(it, q) } }
        f.selection?.let { sel -> val matches = selectionPredicate(sel); result = result.filter(matches) }
        return result
    }

    private fun matchesQuery(r: Record, q: String): Boolean {
        val norm = q.lowercase().replace('ё', 'е')
        val digits = q.replace(Regex("[\\s]"), "").replace(',', '.')
        val amount = digits.toBigDecimalOrNull()?.movePointRight(2)?.toLong()
        val last4 = q.takeIf { it.length == 4 && it.all(Char::isDigit) }
        fun text(s: String) = s.lowercase().replace('ё', 'е').contains(norm)
        return when (r) {
            is Record.Pair -> text("Перевод между счетами") || text(r.pair.debit.category.name) ||
                (amount != null && r.amountMinor == amount) ||
                (last4 != null && (r.pair.debit.account.id.endsWith(last4) || r.pair.credit.account.id.endsWith(last4)))
            is Record.Single -> text(r.op.name) || text(r.op.category.name) || text(r.op.bankCategory) ||
                (amount != null && r.op.amountMinor == amount) ||
                (last4 != null && r.op.account.id.endsWith(last4))
        }
    }

    /**
     * Which operations a selection means, resolved once per request (the regular-payments set is recomputed
     * from the selection itself). Throws [UnknownSelection] for a selection the mock does not know.
     */
    private fun selectionPredicate(selection: String): (Record) -> Boolean {
        val test: (Operation) -> Boolean = when {
            selection == AnalyticsEngine.SELECTION_UNCATEGORIZED -> { op -> op.isRefund && op.refundTarget == null }
            selection == AnalyticsEngine.SELECTION_BANK_FEES -> { op -> op.isDebit && op.category.id == catalog.bankFees.id }
            selection.startsWith(AnalyticsEngine.SELECTION_REGULAR_ALL) -> {
                val names = analytics.regularNames(selection) ?: throw UnknownSelection(selection)
                ({ op -> op.isDebit && op.name in names })
            }
            selection.startsWith("s_regular_") -> {
                val name = analytics.regularPaymentName(selection) ?: throw UnknownSelection(selection)
                ({ op -> op.isDebit && op.name == name })
            }
            selection.startsWith("s_merchant_") -> {
                val slug = selection.removePrefix("s_merchant_")
                val name = ledger.all.map { it.name }.distinct().firstOrNull { Catalog.slugOf(it).take(40) == slug } ?: throw UnknownSelection(selection)
                ({ op -> op.name == name })
            }
            else -> throw UnknownSelection(selection)
        }
        return { r -> (r as? Record.Single)?.op?.let(test) ?: false }
    }

    /** Validates a selection up front so an unknown one fails the whole request, not per record. */
    fun validateSelection(selection: String) {
        if (ledger.all.isEmpty()) return
        selectionPredicate(selection)
    }

}
