package ru.finassist.pf.core.mockbackend.engine

import ru.finassist.pf.core.network.dto.*
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** `GET /v1/operations` — feed (3-month pages) and search (whole selection), api.md 4.1. */
class OperationsQuery(private val ledger: Ledger, private val zone: ZoneId, private val wire: Wire) {

    data class Params(
        val from: OffsetDateTime?, val to: OffsetDateTime?, val allTime: Boolean, val q: String?, val categoryId: String?,
        val kind: String?, val amountFrom: Long?, val amountTo: Long?, val transferMode: String?, val selection: String?, val before: OffsetDateTime?,
    ) {
        val isSearch: Boolean get() = listOf(from, to, q, categoryId, kind, amountFrom, amountTo, transferMode, selection).any { it != null } || allTime
    }

    sealed interface Result {
        data class Ok(val body: OperationsListDto) : Result
        data class Error(val status: Int, val code: String, val message: String) : Result
    }

    /** A row is either a single operation or a pair. */
    private sealed interface Row {
        val at: OffsetDateTime
        data class Single(val op: Operation) : Row { override val at get() = op.tx.postedAt }
        data class Pair(val pair: OwnTransferPair) : Row { override val at get() = pair.debit.tx.postedAt }
    }

    private fun allRows(): List<Row> {
        val rows = mutableListOf<Row>()
        ledger.pairs.values.forEach { rows += Row.Pair(it) }
        ledger.operations.values.filter { it.accounting !is Accounting.OwnTransfer }.forEach { rows += Row.Single(it) }
        return rows.sortedByDescending { it.at }
    }

    fun query(p: Params, now: OffsetDateTime): Result {
        if (p.isSearch && p.before != null) return Result.Error(400, "VALIDATION_ERROR", "before is not allowed with filters")
        if (p.allTime && (p.from != null || p.to != null)) return Result.Error(400, "VALIDATION_ERROR", "all_time is not allowed with from/to")
        if (p.transferMode != null && p.transferMode != "with" && p.transferMode != "without") return Result.Error(400, "VALIDATION_ERROR", "unknown transfer_mode")
        return if (p.isSearch) search(p, now) else feed(p, now)
    }

    private fun feed(p: Params, now: OffsetDateTime): Result {
        val rows = allRows()
        val lastOp = ledger.operations.values.maxOfOrNull { it.tx.postedAt }
        if (rows.isEmpty() || lastOp == null) {
            return Result.Ok(OperationsListDto(items = emptyList(), summary = emptyList(), state = FeedStateDto(stale = true), range = null, nextBefore = null))
        }
        // Pages are calendar quarters-of-three-months ending at the last month with data (or at `before`).
        val endMonth = if (p.before != null) YearMonth.from(wire.localDate(p.before).minusDays(1)) else YearMonth.from(wire.localDate(lastOp))
        val startMonth = endMonth.minusMonths(2)
        val fromT = wire.dayStart(startMonth.atDay(1))
        val toT = wire.dayStart(endMonth.plusMonths(1).atDay(1))
        val page = rows.filter { !it.at.isBefore(fromT) && it.at.isBefore(toT) }
        val older = rows.any { it.at.isBefore(fromT) }
        val view = AccountingView(ledger, zone, true)
        val summary = (0..2).map { endMonth.minusMonths(it.toLong()) }.filter { ym -> Coverage(ledger.uploads, zone, wire.localDate(now)).hasData(ym) }.map { ym ->
            val lines = view.inMonth(ym)
            val full = Coverage(ledger.uploads, zone, wire.localDate(now)).isFullMonth(ym)
            MonthSummaryDto(wire.month(ym), wire.monthRange(ym), view.expense(lines), view.income(lines), dataTo = if (full) null else lines.maxOfOrNull { it.op.tx.postedAt }?.let(wire::time))
        }
        val stale = ChronoUnit.DAYS.between(wire.localDate(lastOp), wire.localDate(now)) > 14
        return Result.Ok(OperationsListDto(
            items = page.map(::row),
            summary = summary,
            state = FeedStateDto(stale, wire.time(lastOp), ledger.uploads.maxOfOrNull { it.uploadedAt }?.let(wire::time)),
            range = RangeDto(wire.time(fromT), wire.time(toT)),
            nextBefore = if (older) wire.time(fromT) else null,
        ))
    }

    private fun search(p: Params, now: OffsetDateTime): Result {
        val today = wire.localDate(now)
        val defaultFrom = wire.dayStart(today.minusMonths(12))
        val defaultTo = wire.dayStart(today.plusDays(1))
        val from = if (p.allTime) null else p.from ?: if (p.to == null) defaultFrom else null
        val to = if (p.allTime) null else p.to ?: if (p.from == null) defaultTo else null
        val usedDefault = !p.allTime && p.from == null && p.to == null
        val hasOlder = usedDefault && ledger.operations.values.any { it.tx.postedAt.isBefore(defaultFrom) }

        val withRules = p.transferMode != null
        val view = if (withRules) AccountingView(ledger, zone, p.transferMode != "without") else null
        val selectionMatcher = p.selection?.let { selectionMatcher(it) ?: return Result.Error(422, "SELECTION_NOT_FOUND", "unknown selection") }

        val rows = allRows().filter { row ->
            if (from != null && row.at.isBefore(from)) return@filter false
            if (to != null && !row.at.isBefore(to)) return@filter false
            when (row) {
                is Row.Pair -> {
                    if (withRules || p.kind != null || selectionMatcher != null) return@filter false
                    if (p.categoryId != null && p.categoryId != Categories.OWN_TRANSFER) return@filter false
                    if (p.amountFrom != null && row.pair.debit.tx.amount < p.amountFrom) return@filter false
                    if (p.amountTo != null && row.pair.debit.tx.amount > p.amountTo) return@filter false
                    if (p.q != null && !matchesQuery(row.pair.debit, "Перевод между счетами", p.q)) return@filter false
                    true
                }
                is Row.Single -> {
                    val op = row.op
                    val line = view?.lines?.firstOrNull { it.op === op }
                    if (view != null && line == null) return@filter false
                    if (p.categoryId != null) {
                        val cat = if (line != null) line.categoryId else op.categoryId
                        if (cat != p.categoryId) return@filter false
                    }
                    if (p.kind != null) {
                        val isExpense = if (line != null) line.isExpense else op.direction == Direction.Debit
                        if ((p.kind == "expense") != isExpense) return@filter false
                        if (op.categoryId == Categories.OWN_TRANSFER) return@filter false
                    }
                    if (p.amountFrom != null && op.tx.amount < p.amountFrom) return@filter false
                    if (p.amountTo != null && op.tx.amount > p.amountTo) return@filter false
                    if (selectionMatcher != null && !selectionMatcher(op)) return@filter false
                    if (p.q != null && !matchesQuery(op, op.tx.name, p.q)) return@filter false
                    true
                }
            }
        }
        if (rows.size > 10_000) return Result.Error(422, "TOO_MANY_RESULTS", "narrow the period")
        return Result.Ok(OperationsListDto(
            items = rows.map(::row),
            totalCount = rows.size,
            range = if (from != null && to != null) RangeDto(wire.time(from), wire.time(to)) else null,
            hasOlderData = if (usedDefault) hasOlder else null,
        ))
    }

    private fun selectionMatcher(selection: String): ((Operation) -> Boolean)? = when {
        selection == Selections.BANK_FEES -> { op -> op.direction == Direction.Debit && (op.categoryId == Categories.BANK_FEES || op.tx.name.contains("комисс", true)) }
        selection == Selections.UNCATEGORIZED -> { op -> (op.accounting as? Accounting.Refund)?.reducesCategoryId == null && op.accounting is Accounting.Refund }
        selection.startsWith("s_regular_rp_") -> { val key = selection.removePrefix("s_regular_rp_"); { op -> Selections.merchantKey(op.tx.name) == key && op.direction == Direction.Debit } }
        selection.startsWith("s_small_") -> { val cat = selection.removePrefix("s_small_"); { op -> op.categoryId == cat && op.direction == Direction.Debit && op.tx.amount <= 50_000 } }
        selection.startsWith("s_merchant_") -> { val key = selection.removePrefix("s_merchant_"); { op -> Selections.merchantKey(op.tx.name) == key } }
        else -> null
    }

    /** `q`: substring of title, category name (final or bank), exact amount, last 4 digits of the account. ё = е. */
    private fun matchesQuery(op: Operation, title: String, q: String): Boolean {
        val needle = norm(q)
        if (needle.isEmpty()) return true
        if (norm(title).contains(needle)) return true
        if (norm(Categories.byId(op.categoryId)?.name.orEmpty()).contains(needle)) return true
        if (norm(op.tx.bankCategory).contains(needle)) return true
        val digits = q.replace(" ", "").replace(' ', ' ').replace(" ", "").replace(',', '.')
        digits.toDoubleOrNull()?.let { if ((it * 100).toLong() == op.tx.amount) return true }
        if (digits.length == 4 && digits.all { it.isDigit() } && op.tx.accountId.endsWith(digits)) return true
        return false
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е')

    private fun row(r: Row): OperationDto = when (r) {
        is Row.Single -> wire.operation(ledger, r.op, details = false)
        is Row.Pair -> wire.pair(ledger, r.pair, details = false)
    }

    fun details(id: String): OperationDto? {
        ledger.pairs[id]?.let { return wire.pair(ledger, it, details = true) }
        val op = ledger.operations[id] ?: return null
        if (op.accounting is Accounting.OwnTransfer) return null   // halves of a pair are not addressable
        return wire.operation(ledger, op, details = true)
    }

    @Suppress("unused")
    private fun LocalDate.ym() = YearMonth.from(this)
}
