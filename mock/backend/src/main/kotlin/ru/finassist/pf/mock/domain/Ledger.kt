package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.SystemCategories
import ru.finassist.pf.mock.ofx.OfxAccount
import ru.finassist.pf.mock.ofx.OfxTransaction
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** One statement line after import. Mutable only through [Ledger]. */
class Operation(
    val id: String,
    val account: OfxAccount,
    val fitId: String,
    val isDebit: Boolean,
    val occurredAt: OffsetDateTime,
    val amountMinor: Long,
    val name: String,
    /** Bank category of the line: from `MEMO`, «Без категории» when it is empty (docs/calc.md, 3.1). */
    val bank: Category,
    /** `MEMO` as in the statement, trimmed and cut to 64 characters. */
    val memo: String,
    val currency: String,
    /** Final category: manual override, refund, own transfer or the bank category. */
    var category: Category,
    var isManual: Boolean = false,
    /** For a refund: category whose expenses it reduces («Без категории» when none is found). */
    var refundTarget: Category? = null,
    /** Id of the own-transfer pair this operation belongs to, if any. */
    var pairId: String? = null,
    /** The user split a pair with this operation: it is not a transfer to self unless they say so again. */
    var unpaired: Boolean = false,
    /** Uploads that contained this (account, FITID); the operation lives while at least one remains. */
    val uploadIds: MutableSet<String> = mutableSetOf(),
) {
    val bankCategory: String get() = bank.name
    val isRefund: Boolean get() = category.id == SystemCategories.REFUND
    val isOwnTransferCategory: Boolean get() = category.id == SystemCategories.OWN_TRANSFER
    val key: String get() = "${account.id}|$fitId"
}

/** Two operations the user moved between their own accounts; shown as one record and excluded from analytics. */
class OwnTransferPair(val id: String, val debit: Operation, val credit: Operation)

/**
 * All operations of the mock user and the accounting rules of `docs/calc.md` in fin-assist/contracts (section 3):
 * own-transfer pairs, refunds and their targets, manual categories that survive re-import. The rules run over the
 * whole ledger after every change, like the reference calculation of the contract.
 */
class Ledger(private val catalog: Catalog) {
    private val operations = LinkedHashMap<String, Operation>()   // key → op
    private val byId = HashMap<String, Operation>()
    private val pairs = HashMap<String, OwnTransferPair>()
    /** Manual categories remembered by (account, FITID) so a re-import does not overwrite them. */
    private val manual = HashMap<String, String>()
    /** Keys of operations from pairs the user split, remembered like [manual]. */
    private val unpaired = HashSet<String>()

    val all: Collection<Operation> get() = operations.values
    val pairCount: Int get() = pairs.size

    fun byId(id: String): Operation? = byId[id]
    fun byKey(accountId: String, fitId: String): Operation? = operations["$accountId|$fitId"]
    fun pair(id: String): OwnTransferPair? = pairs[id]
    fun pairOf(op: Operation): OwnTransferPair? = op.pairId?.let { pairs[it] }

    /** Adds the transactions of one upload; returns (new, duplicate) counts. Rules run over the whole ledger. */
    fun importUpload(uploadId: String, accounts: List<OfxAccount>, transactions: List<OfxTransaction>): Pair<List<Operation>, Int> {
        val accountById = accounts.associateBy { it.id }
        val added = mutableListOf<Operation>()
        var duplicates = 0
        for (t in transactions) {
            val key = "${t.accountId}|${t.fitId}"
            val existing = operations[key]
            if (existing != null) {
                existing.uploadIds += uploadId
                duplicates++
                continue
            }
            val bank = catalog.bankCategory(t.memo)
            val op = Operation(
                id = UUID.randomUUID().toString(),
                account = accountById.getValue(t.accountId),
                fitId = t.fitId,
                isDebit = t.isDebit,
                occurredAt = t.postedAt,
                amountMinor = t.amountMinor,
                name = t.name,
                bank = bank,
                memo = t.memo.trim().take(Catalog.NAME_LIMIT),
                currency = t.currency,
                category = bank,
            )
            op.uploadIds += uploadId
            op.unpaired = key in unpaired
            operations[key] = op
            byId[op.id] = op
            added += op
        }
        applyRules()
        return added to duplicates
    }

    /** Removes operations that belonged only to [uploadId]; the others stay (api.md 3.5). */
    fun removeUpload(uploadId: String) {
        val gone = operations.values.filter { it.uploadIds.remove(uploadId) && it.uploadIds.isEmpty() }
        gone.forEach { op ->
            operations.remove(op.key)
            byId.remove(op.id)
        }
        applyRules()
    }

    fun clear() {
        operations.clear(); byId.clear(); pairs.clear(); manual.clear(); unpaired.clear()
    }

    /**
     * Manual category change (api.md 4.3, docs/calc.md 3.4). Returns the records to show and the id that
     * disappeared, if any. Throws [IllegalArgumentException] when the category is not assignable to this record.
     */
    fun changeCategory(recordId: String, category: Category): Pair<List<Any>, String?> {
        require(category.assignable) { "not assignable" }
        val asPair = pairs[recordId] ?: byId[recordId]?.pairId?.let { pairs[it] }
        asPair?.let { pair ->
            if (category.id == SystemCategories.OWN_TRANSFER) return listOf(pair) to null
            // Split: the chosen category goes to the side it fits; the other side gets its bank category back.
            val expenseFits = category.kinds.any { it.code == "expense" }
            val target = if (expenseFits) pair.debit else pair.credit
            val other = if (expenseFits) pair.credit else pair.debit
            pairs.remove(pair.id)
            for (op in listOf(pair.debit, pair.credit)) {
                op.pairId = null
                op.unpaired = true
                unpaired += op.key
            }
            setManual(target, category)
            manual.remove(other.key)
            applyRules()
            return listOf(pair.debit, pair.credit) to pair.id
        }
        val op = byId[recordId] ?: throw NoSuchElementException(recordId)
        val kind = if (op.isDebit) "expense" else "income"
        require(category.kinds.any { it.code == kind }) { "kind mismatch" }
        setManual(op, category)
        applyRules()
        val pair = pairOf(op)
        return if (pair != null) listOf(pair) to op.id else listOf(op) to null
    }

    private fun setManual(op: Operation, category: Category) {
        manual[op.key] = category.id
        if (category.id == SystemCategories.OWN_TRANSFER) {
            op.unpaired = false
            unpaired -= op.key
        }
    }

    // ---- rules (docs/calc.md, 3.2–3.5) ----

    private fun manualOf(op: Operation): Category? = manual[op.key]?.let { catalog.byId(it) }

    /** A transfer to self: a manual «Между своими счетами», or by description or bank category unless split (3.2). */
    private fun selfTransfer(op: Operation): Boolean {
        manualOf(op)?.let { return it.id == SystemCategories.OWN_TRANSFER }
        if (op.unpaired) return false
        return op.name in SELF_TRANSFER_NAMES || op.bank.id == SystemCategories.OWN_TRANSFER
    }

    private fun applyRules() {
        pairOwnTransfers()
        val ordered = operations.values.sortedWith(ORDER)
        for (op in ordered) {
            val manualCategory = manualOf(op)
            op.isManual = manualCategory != null
            op.refundTarget = null
            op.category = when {
                manualCategory != null -> manualCategory
                op.pairId != null || selfTransfer(op) -> catalog.ownTransfer
                else -> op.bank
            }
        }
        val debits = ordered.filter { it.isDebit }
        for (op in ordered) {
            if (op.isDebit || op.pairId != null) continue
            if (op.isManual) {
                if (op.isRefund) op.refundTarget = target(op, debits)
                continue
            }
            if (selfTransfer(op)) continue
            if (isRefund(op, debits)) {
                op.category = catalog.refund
                op.refundTarget = target(op, debits)
            }
        }
    }

    /**
     * Pairs of transfers to self (3.2): existing pairs stay while both sides are there; then debits that are
     * transfers to self, oldest first, take the closest credit of the same amount and currency on another account
     * within 72 hours that is a transfer to self or a «Переводы» credit without a manual category. Ties: the earlier
     * credit, then the smaller (ACCTID, FITID).
     */
    private fun pairOwnTransfers() {
        pairs.values.filter { it.debit.key !in operations || it.credit.key !in operations }.forEach { pairs.remove(it.id) }
        operations.values.forEach { it.pairId = null }
        pairs.values.forEach { p -> p.debit.pairId = p.id; p.credit.pairId = p.id }
        val credits = operations.values.filter { !it.isDebit }
        for (debit in operations.values.filter { it.isDebit }.sortedWith(ORDER)) {
            if (debit.pairId != null || !selfTransfer(debit)) continue
            val candidate = credits
                .filter { it.pairId == null && it.amountMinor == debit.amountMinor && it.currency == debit.currency }
                .filter { it.account.id != debit.account.id }
                .filter { selfTransfer(it) || (manualOf(it) == null && it.bank.name == TRANSFERS) }
                .filter { Duration.between(debit.occurredAt, it.occurredAt).abs() <= PAIR_WINDOW }
                .minWithOrNull(
                    compareBy<Operation> { Duration.between(debit.occurredAt, it.occurredAt).abs() }
                        .then(ORDER),
                ) ?: continue
            val pair = OwnTransferPair(UUID.randomUUID().toString(), debit, candidate)
            pairs[pair.id] = pair
            debit.pairId = pair.id; candidate.pairId = pair.id
        }
    }

    /** Earlier debits with the same non-empty description, except bank «Переводы» (3.3; FIN-67). */
    private fun sameNameDebits(op: Operation, debits: List<Operation>): List<Operation> {
        if (op.name.isEmpty()) return emptyList()
        val name = op.name.lowercase()
        return debits.filter { it.name.isNotEmpty() && it.name.lowercase() == name && it.occurredAt < op.occurredAt && it.bank.name != TRANSFERS }
    }

    /** A credit is a refund (3.3): expense bank category, `MEMO` «Возврат», an earlier debit with its name, the word. */
    private fun isRefund(op: Operation, debits: List<Operation>): Boolean =
        catalog.isExpense(op.bank) || op.memo == Catalog.REFUND_NAME || sameNameDebits(op, debits).isNotEmpty() ||
            "возврат" in op.name.lowercase()

    /** The refund's target (3.3): same description → same amount and account within 60 days → own bank category. */
    private fun target(op: Operation, debits: List<Operation>): Category {
        fun usable(d: Operation) = d.category.id != SystemCategories.OWN_TRANSFER && d.category.id != SystemCategories.REFUND
        sameNameDebits(op, debits).lastOrNull()?.takeIf(::usable)?.let { return it.category }
        debits.lastOrNull {
            it.account.id == op.account.id && it.amountMinor == op.amountMinor && it.occurredAt < op.occurredAt &&
                Duration.between(it.occurredAt, op.occurredAt) <= REFUND_WINDOW && it.bank.name != TRANSFERS
        }?.takeIf(::usable)?.let { return it.category }
        if (catalog.isExpense(op.bank)) return op.bank
        return catalog.uncategorized
    }

    private companion object {
        val SELF_TRANSFER_NAMES = setOf("Между своими счетами", "Себе в другой банк", "Себе из другого банка")
        const val TRANSFERS = "Переводы"
        val PAIR_WINDOW: Duration = Duration.ofHours(72)
        val REFUND_WINDOW: Duration = Duration.ofDays(60)
        /** Time, then (ACCTID, FITID) — the order the rules walk the operations in. */
        val ORDER: Comparator<Operation> = compareBy<Operation> { it.occurredAt.toInstant() }
            .thenBy { it.account.id }.thenBy { it.fitId }
    }
}
