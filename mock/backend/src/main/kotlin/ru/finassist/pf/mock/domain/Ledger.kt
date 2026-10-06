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
    val bankCategory: String,
    val currency: String,
    /** Final category: manual override, refund, own transfer or the bank category. */
    var category: Category,
    var isManual: Boolean = false,
    /** For a refund: category whose expenses it reduces; null = «Без категории». */
    var refundTarget: Category? = null,
    /** Id of the own-transfer pair this operation belongs to, if any. */
    var pairId: String? = null,
    /** The user split a pair with this operation: it is not a transfer to self unless they say so again. */
    var unpaired: Boolean = false,
    /** Uploads that contained this (account, FITID); the operation lives while at least one remains. */
    val uploadIds: MutableSet<String> = mutableSetOf(),
) {
    val isRefund: Boolean get() = category.id == SystemCategories.REFUND
    val isOwnTransferCategory: Boolean get() = category.id == SystemCategories.OWN_TRANSFER
    val key: String get() = "${account.id}|$fitId"
}

/** Two operations the user moved between their own accounts; shown as one record and excluded from analytics. */
class OwnTransferPair(val id: String, val debit: Operation, val credit: Operation)

/**
 * All operations of the mock user plus the accounting rules from mvp-scope.md / decision-ofx-only.md:
 * own-transfer pairing, refund detection and manual categories that survive re-import.
 */
class Ledger(private val catalog: Catalog) {
    private val operations = LinkedHashMap<String, Operation>()   // key → op
    private val byId = HashMap<String, Operation>()
    private val pairs = HashMap<String, OwnTransferPair>()
    /** Manual categories remembered by (account, FITID) so a re-import does not overwrite them. */
    private val manual = HashMap<String, String>()

    val all: Collection<Operation> get() = operations.values
    val pairCount: Int get() = pairs.size

    fun byId(id: String): Operation? = byId[id]
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
            val bankCategory = catalog.byName(t.memo.ifBlank { "Другое" })
            val op = Operation(
                id = UUID.randomUUID().toString(),
                account = accountById.getValue(t.accountId),
                fitId = t.fitId,
                isDebit = t.isDebit,
                occurredAt = t.postedAt,
                amountMinor = t.amountMinor,
                name = t.name,
                bankCategory = bankCategory.name,
                currency = t.currency,
                category = bankCategory,
            )
            op.uploadIds += uploadId
            manual[key]?.let { id -> catalog.byId(id)?.let { op.category = it; op.isManual = true } }
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
            // The surviving side of a pair (kept by another upload) must not point at the removed pair.
            op.pairId?.let { id -> pairs.remove(id)?.let { p -> p.debit.pairId = null; p.credit.pairId = null } }
        }
        applyRules()
    }

    fun clear() {
        operations.clear(); byId.clear(); pairs.clear(); manual.clear()
    }

    /**
     * Manual category change (api.md 4.3). Returns the records to show and the id that disappeared, if any.
     * Throws [IllegalArgumentException] when the category is not assignable to this record.
     */
    fun changeCategory(recordId: String, category: Category): Pair<List<Any>, String?> {
        require(category.assignable) { "not assignable" }
        val asPair = pairs[recordId] ?: byId[recordId]?.pairId?.let { pairs[it] }
        asPair?.let { pair ->
            if (category.id == SystemCategories.OWN_TRANSFER) return listOf(pair) to null
            // Split: chosen category goes to the side it fits; the other side gets its bank category back.
            val expenseFits = category.kinds.any { it.code == "expense" }
            val target = if (expenseFits) pair.debit else pair.credit
            val other = if (expenseFits) pair.credit else pair.debit
            pairs.remove(pair.id)
            pair.debit.pairId = null; pair.credit.pairId = null
            pair.debit.unpaired = true; pair.credit.unpaired = true
            setManual(target, category)
            other.category = catalog.byName(other.bankCategory); other.isManual = false
            manual.remove(other.key)
            applyRules()
            return listOf(pair.debit, pair.credit) to pair.id
        }
        val op = byId[recordId] ?: throw NoSuchElementException(recordId)
        val kind = if (op.isDebit) "expense" else "income"
        require(category.kinds.any { it.code == kind }) { "kind mismatch" }
        setManual(op, category)
        op.refundTarget = null
        applyRules()
        val pair = pairOf(op)
        return if (pair != null) listOf(pair) to op.id else listOf(op) to null
    }

    private fun setManual(op: Operation, category: Category) {
        op.category = category
        op.isManual = true
        if (category.id == SystemCategories.OWN_TRANSFER) op.unpaired = false
        manual[op.key] = category.id
    }

    // ---- rules ----

    private fun applyRules() {
        pairOwnTransfers()
        detectRefunds()
    }

    /**
     * A debit and a credit of the same amount on different accounts within 3 days, where the debit says it is
     * a transfer to self («Между своими счетами», «Себе в другой банк») or the user set that category manually,
     * and the credit is a self transfer or a plain «Переводы» credit. Each operation joins at most one pair.
     * A self transfer without a pair keeps the «Между своими счетами» category as a plain operation (api.md 4).
     */
    private fun pairOwnTransfers() {
        pairs.values.filter { it.debit.key !in operations || it.credit.key !in operations }.forEach { p ->
            pairs.remove(p.id); p.debit.pairId = null; p.credit.pairId = null
        }
        val paired = pairs.values.flatMap { listOf(it.debit.id, it.credit.id) }.toMutableSet()
        val ops = operations.values

        fun selfTransfer(op: Operation) = when {
            op.isManual -> op.isOwnTransferCategory
            op.unpaired -> false
            else -> op.isOwnTransferCategory || op.name in SELF_TRANSFER_NAMES
        }

        val credits = ops.filter { !it.isDebit && it.id !in paired }.groupBy { it.amountMinor }
        for (debit in ops.filter { it.isDebit && it.id !in paired && selfTransfer(it) }.sortedBy { it.occurredAt }) {
            val candidate = credits[debit.amountMinor]
                ?.filter { it.id !in paired && it.account.id != debit.account.id }
                ?.filter { selfTransfer(it) || (!it.isManual && it.bankCategory == "Переводы") }
                ?.filter { Duration.between(debit.occurredAt, it.occurredAt).abs() <= Duration.ofDays(3) }
                ?.minByOrNull { Duration.between(debit.occurredAt, it.occurredAt).abs() }
                ?: continue
            val pair = OwnTransferPair(UUID.randomUUID().toString(), debit, candidate)
            pairs[pair.id] = pair
            debit.pairId = pair.id; candidate.pairId = pair.id
            paired += debit.id; paired += candidate.id
        }
        for (op in ops) {
            if (op.isManual) continue
            when {
                op.pairId != null -> op.category = catalog.ownTransfer
                op.unpaired -> op.category = catalog.byName(op.bankCategory)
                op.name in SELF_TRANSFER_NAMES -> op.category = catalog.ownTransfer
                op.isOwnTransferCategory -> op.category = catalog.byName(op.bankCategory) // lost its pair
            }
        }
    }

    /**
     * Refund rule (decision-ofx-only.md): a credit is a refund when its bank category is an expense one, or an
     * earlier debit had the same description (except transfers), or the description says «возврат».
     * The category it reduces: past debits with the same name → a debit of the same amount on the same account
     * within 60 days → the refund's own bank category if it is an expense one → none.
     */
    private fun detectRefunds() {
        val ops = operations.values.sortedBy { it.occurredAt }
        val debitsByName = ops.filter { it.isDebit }.groupBy { it.name.lowercase() }
        for (op in ops) {
            if (op.isDebit || op.isManual || op.pairId != null) continue
            val bankCat = op.bankCategory
            val nameKey = op.name.lowercase()
            val earlierSameName = debitsByName[nameKey]?.filter { it.occurredAt < op.occurredAt && it.bankCategory != "Переводы" }.orEmpty()
            val isRefund = (!catalog.isIncomeOnly(bankCat) && bankCat !in Catalog.BOTH_KINDS) ||
                earlierSameName.isNotEmpty() ||
                nameKey.contains("возврат")
            if (!isRefund) {
                if (op.isRefund) { op.category = catalog.byName(bankCat); op.refundTarget = null }
                continue
            }
            val target: Category? = earlierSameName.lastOrNull()?.let { finalExpenseCategory(it) }
                ?: ops.lastOrNull {
                    it.isDebit && it.account.id == op.account.id && it.amountMinor == op.amountMinor &&
                        it.occurredAt < op.occurredAt && Duration.between(it.occurredAt, op.occurredAt) <= Duration.ofDays(60)
                }?.let { finalExpenseCategory(it) }
                ?: catalog.byName(bankCat).takeIf { !catalog.isIncomeOnly(bankCat) && bankCat !in Catalog.BOTH_KINDS }
            op.category = catalog.refund
            op.refundTarget = target
        }
    }

    private fun finalExpenseCategory(debit: Operation): Category? =
        debit.category.takeIf { it.id != SystemCategories.OWN_TRANSFER && it.id != SystemCategories.REFUND }

    private companion object {
        val SELF_TRANSFER_NAMES = setOf("Между своими счетами", "Себе в другой банк", "Себе из другого банка")
    }
}
