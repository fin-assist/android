package ru.finassist.pf.core.mockbackend.engine

import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * The user's data as the backend would store it, plus the accounting rules from `decision-ofx-only.md`
 * and `mvp-scope.md`: dedup by (account, FITID), own-transfer pairs, refunds, manual categories.
 * Single-user, in-memory, not thread-safe by itself — the HTTP layer serialises access.
 */
class Ledger(val zone: ZoneId) {
    val accounts = linkedMapOf<String, Account>()
    val operations = linkedMapOf<String, Operation>()
    val pairs = linkedMapOf<String, OwnTransferPair>()
    val uploads = mutableListOf<Upload>()
    val dismissedRegularPayments = mutableSetOf<String>()
    private val byKey = hashMapOf<Pair<String, String>, String>()

    var lastCategoryChangeAt: OffsetDateTime? = null
    var lastUploadChangeAt: OffsetDateTime? = null

    /** Operation ids whose pair was dissolved by a manual category — not re-paired automatically. */
    private val unpairedByUser = mutableSetOf<String>()

    data class ImportOutcome(val upload: Upload, val newOperations: List<Operation>, val duplicates: Int)

    fun import(parsed: ParsedStatement, fileName: String, now: OffsetDateTime, uploadId: String): ImportOutcome {
        parsed.accounts.forEach { accounts.putIfAbsent(it.id, it) }
        val upload = Upload(uploadId, fileName, now, "processing", parsed.transactions.size, 0, 0, parsed.unreadLines, null, null, parsed.coveredFrom, parsed.coveredTo)
        val fresh = mutableListOf<Operation>()
        var duplicates = 0
        for (tx in parsed.transactions) {
            val key = tx.accountId to tx.fitId
            val existing = byKey[key]
            if (existing != null) {
                duplicates++
                operations.getValue(existing).uploadIds += uploadId
                continue
            }
            val category = Categories.forBankCategory(tx.bankCategory, tx.direction)
            val op = Operation(Ids.next(tx.postedAt.toInstant().toEpochMilli()), mutableSetOf(uploadId), tx, category.id, false, Accounting.Regular)
            operations[op.id] = op
            byKey[key] = op.id
            fresh += op
        }
        upload.newCount = fresh.size
        upload.duplicateCount = duplicates
        upload.firstOperationAt = parsed.transactions.minOfOrNull { it.postedAt }
        upload.lastOperationAt = parsed.transactions.maxOfOrNull { it.postedAt }
        upload.status = "done"
        uploads += upload
        lastUploadChangeAt = now
        applyRules()
        return ImportOutcome(upload, fresh, duplicates)
    }

    fun deleteUpload(uploadId: String, now: OffsetDateTime): Boolean {
        val upload = uploads.firstOrNull { it.id == uploadId } ?: return false
        uploads.remove(upload)
        val gone = operations.values.filter { it.uploadIds.remove(uploadId); it.uploadIds.isEmpty() }
        gone.forEach { op ->
            operations.remove(op.id)
            byKey.remove(op.tx.accountId to op.tx.fitId)
        }
        lastUploadChangeAt = now
        applyRules()
        return true
    }

    fun sortedOperations(): List<Operation> = operations.values.sortedByDescending { it.tx.postedAt }

    // ---- rules -------------------------------------------------------------------------------------------------

    /** Recomputes pairs and refunds over the whole ledger. Manual categories are kept. */
    fun applyRules() {
        pairs.clear()
        operations.values.forEach { if (!it.isCategoryManual) it.categoryId = Categories.forBankCategory(it.tx.bankCategory, it.tx.direction).id }
        operations.values.forEach { it.accounting = Accounting.Regular }
        matchOwnTransfers()
        detectRefunds()
    }

    private fun isOwnTransferCandidate(op: Operation): Boolean {
        if (op.id in unpairedByUser) return false
        if (op.isCategoryManual) return op.categoryId == Categories.OWN_TRANSFER
        val acc = accounts[op.tx.accountId]
        val n = op.tx.name.lowercase()
        return op.tx.bankCategory == "Переводы" && (
            n in OWN_NAMES || acc?.type == AccountType.OtherBank
        )
    }

    private fun matchOwnTransfers() {
        val candidates = operations.values.filter(::isOwnTransferCandidate)
        val credits = candidates.filter { it.direction == Direction.Credit }.toMutableList()
        for (debit in candidates.filter { it.direction == Direction.Debit }.sortedBy { it.tx.postedAt }) {
            val match = credits
                .filter { it.tx.accountId != debit.tx.accountId && it.tx.amount == debit.tx.amount }
                .map { it to abs(it.tx.postedAt.toEpochSecond() - debit.tx.postedAt.toEpochSecond()) }
                .filter { it.second <= PAIR_WINDOW_SECONDS }
                .minByOrNull { it.second }?.first ?: continue
            credits.remove(match)
            val pairId = Ids.next(debit.tx.postedAt.toInstant().toEpochMilli())
            pairs[pairId] = OwnTransferPair(pairId, debit, match)
            for (op in listOf(debit, match)) {
                op.accounting = Accounting.OwnTransfer(pairId)
                if (!op.isCategoryManual) op.categoryId = Categories.OWN_TRANSFER
            }
        }
        // Unpaired candidates with an own-transfer name still get the system category (excluded from analytics).
        candidates.filter { it.accounting is Accounting.Regular && !it.isCategoryManual && it.tx.name.lowercase() in OWN_NAMES }
            .forEach { it.categoryId = Categories.OWN_TRANSFER }
    }

    private fun detectRefunds() {
        val sorted = operations.values.sortedBy { it.tx.postedAt }
        for ((index, op) in sorted.withIndex()) {
            if (op.direction != Direction.Credit || op.accounting !is Accounting.Regular || op.isCategoryManual) continue
            val name = op.tx.name.lowercase()
            val earlierDebits = sorted.subList(0, index).filter { it.direction == Direction.Debit && it.tx.name.equals(op.tx.name, true) && it.tx.bankCategory != "Переводы" }
            val isRefund = Categories.isExpenseBankCategory(op.tx.bankCategory) || earlierDebits.isNotEmpty() || name.contains("возврат")
            if (!isRefund) continue
            val reduces: String? = earlierDebits.lastOrNull()?.categoryId
                ?: sorted.subList(0, index).lastOrNull {
                    it.direction == Direction.Debit && it.tx.accountId == op.tx.accountId && it.tx.amount == op.tx.amount &&
                        op.tx.postedAt.toEpochSecond() - it.tx.postedAt.toEpochSecond() <= 60L * 86400
                }?.categoryId
                ?: Categories.forBankCategory(op.tx.bankCategory, Direction.Debit).id.takeIf { Categories.isExpenseBankCategory(op.tx.bankCategory) }
            op.categoryId = Categories.REFUND
            op.accounting = Accounting.Refund(reduces)
        }
    }

    // ---- manual category ---------------------------------------------------------------------------------------

    sealed interface ChangeResult {
        data class Changed(val items: List<Operation>, val pair: OwnTransferPair?, val replacedId: String?) : ChangeResult
        data object NotFound : ChangeResult
        data object NotAssignable : ChangeResult
    }

    /** api.md 4.3: ordinary operation, splitting a pair, or joining into a pair. */
    fun changeCategory(id: String, categoryId: String, now: OffsetDateTime): ChangeResult {
        val category = Categories.byId(categoryId) ?: return ChangeResult.NotAssignable
        if (!category.assignable) return ChangeResult.NotAssignable
        pairs[id]?.let { pair ->
            if (categoryId == Categories.OWN_TRANSFER) return ChangeResult.Changed(listOf(pair.debit, pair.credit), pair, null)
            val toDebit = Direction.Debit in category.kinds
            val target = if (toDebit) pair.debit else pair.credit
            val other = if (toDebit) pair.credit else pair.debit
            target.categoryId = categoryId; target.isCategoryManual = true
            other.categoryId = Categories.forBankCategory(other.tx.bankCategory, other.direction).id; other.isCategoryManual = false
            unpairedByUser += pair.debit.id; unpairedByUser += pair.credit.id
            lastCategoryChangeAt = now
            applyRules()
            return ChangeResult.Changed(listOf(pair.debit, pair.credit), null, pair.id)
        }
        val op = operations[id] ?: return ChangeResult.NotFound
        if (op.direction !in category.kinds) return ChangeResult.NotAssignable
        op.categoryId = categoryId
        op.isCategoryManual = true
        unpairedByUser -= op.id
        lastCategoryChangeAt = now
        applyRules()
        val pair = (op.accounting as? Accounting.OwnTransfer)?.let { pairs[it.pairId] }
        return if (pair != null) ChangeResult.Changed(listOf(pair.debit, pair.credit), pair, op.id)
        else ChangeResult.Changed(listOf(op), null, null)
    }

    fun pairOf(op: Operation): OwnTransferPair? = (op.accounting as? Accounting.OwnTransfer)?.let { pairs[it.pairId] }

    companion object {
        private const val PAIR_WINDOW_SECONDS = 24L * 3600
        private val OWN_NAMES = setOf("между своими счетами", "себе в другой банк", "себе из другого банка", "перевод между счетами")
    }
}
