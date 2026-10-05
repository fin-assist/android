package ru.finassist.pf.core.mockbackend.engine

import ru.finassist.pf.core.network.dto.*
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId

/** Statement-related responses: config, list, unread lines, import result (api.md §3). */
class StatementsService(private val ledger: Ledger, private val zone: ZoneId, private val wire: Wire) {

    fun config(now: OffsetDateTime): ImportConfigDto {
        val today = wire.localDate(now)
        val lastOp = ledger.operations.values.maxOfOrNull { it.tx.postedAt }
        val firstOp = ledger.operations.values.minOfOrNull { it.tx.postedAt }
        val from = lastOp?.let { wire.localDate(it) } ?: today.minusMonths(12)
        val to = today.plusDays(1)
        val startMs = wire.dayStart(from).toInstant().toEpochMilli()
        val endMs = wire.dayStart(to).toInstant().toEpochMilli()
        return ImportConfigDto(
            downloadUrl = "https://www.tbank-online.com/mybank/api/operations/timeline/public/legacy/v1/export_operations?appName=supreme&appVersion=0.0.1&origin=web%2Cib5%2Cplatform&start=$startMs&end=$endMs&format=ofx",
            loginUrl = "https://www.tbank.ru/login/",
            maxFileSizeBytes = 10L * 1024 * 1024,
            acceptedFormats = listOf("OFX"),
            suggestedPeriod = RangeDto(wire.dayStartStr(from), wire.dayStartStr(to)),
            loaded = if (lastOp != null && firstOp != null) LoadedSummaryDto(wire.time(firstOp), wire.time(lastOp), ledger.operations.size) else null,
        )
    }

    fun list(now: OffsetDateTime): StatementsListDto {
        val ops = ledger.operations.values
        val coverage = Coverage(ledger.uploads, zone, wire.localDate(now))
        val summary = if (ledger.uploads.isEmpty() || ops.isEmpty()) null else StatementsSummaryDto(
            uploadCount = ledger.uploads.size,
            operationCount = ops.size,
            firstOperationAt = wire.time(ops.minOf { it.tx.postedAt }),
            lastOperationAt = wire.time(ops.maxOf { it.tx.postedAt }),
            gaps = coverage.gaps(coverage.dataFrom!!, coverage.dataTo!!).map { wire.dayRange(it.start, it.endInclusive) },
        )
        return StatementsListDto(summary, ledger.uploads.sortedByDescending { it.uploadedAt }.map { u ->
            UploadDto(u.id, u.fileName, u.status, wire.time(u.uploadedAt), u.operationCount, u.unreadLines.size,
                u.firstOperationAt?.let(wire::time), u.lastOperationAt?.let(wire::time), "Выписка Т-Банка")
        })
    }

    fun unreadLines(uploadId: String?, from: OffsetDateTime?, to: OffsetDateTime?): UnreadLinesListDto? {
        val uploads = if (uploadId != null) listOf(ledger.uploads.firstOrNull { it.id == uploadId } ?: return null) else ledger.uploads
        val lines = uploads.flatMap { u ->
            u.unreadLines.filter { l ->
                if (uploadId != null) true
                else l.date != null && (from == null || !l.date.isBefore(from)) && (to == null || l.date.isBefore(to))
            }.map { l -> UnreadLineDto(u.id, u.fileName, l.lineNumber, l.date?.let(wire::time), l.reason, l.reasonName) }
        }
        return UnreadLinesListDto(lines)
    }

    /** `complete` event of the progress stream. [before] is the coverage before this upload. */
    fun importResult(outcome: Ledger.ImportOutcome, before: Coverage, now: OffsetDateTime): ImportResultDto {
        val upload = outcome.upload
        val after = Coverage(ledger.uploads, zone, wire.localDate(now))
        val fresh = outcome.newOperations
        val isFirst = ledger.uploads.size == 1
        val scopeOps = if (isFirst) ledger.operations.values.filter { upload.id in it.uploadIds } else fresh
        val view = AccountingView(ledger, zone, true)
        val scopeIds = scopeOps.map { it.id }.toSet()
        val scopeLines = view.lines.filter { it.op.id in scopeIds }
        val totals = if (fresh.isEmpty()) null else ImportTotalsDto(if (isFirst) "all" else "new", view.expense(scopeLines), view.income(scopeLines))
        val ownTransfers = scopeOps.count { it.accounting is Accounting.OwnTransfer }
        val uncategorized = scopeOps.count { (it.accounting as? Accounting.Refund)?.reducesCategoryId == null && it.accounting is Accounting.Refund }
        val fullBefore = before.fullMonths()
        val fullAfter = after.fullMonths()
        val newlyFull = fullAfter.filter { it !in fullBefore }
        val today = wire.localDate(now)
        val incomplete = after.monthsWithData().filter { it !in fullAfter }.map { ym ->
            val last = minOf(ym.atEndOfMonth(), today)
            val inMonth = view.inMonth(ym)
            IncompleteMonthDto(
                month = wire.month(ym), range = wire.monthRange(ym),
                dataFrom = inMonth.minOfOrNull { it.op.tx.postedAt }?.let(wire::time) ?: wire.dayStartStr(ym.atDay(1)),
                dataTo = inMonth.maxOfOrNull { it.op.tx.postedAt }?.let(wire::time) ?: wire.dayStartStr(last),
                gaps = after.gaps(maxOf(ym.atDay(1), after.dataFrom!!), minOf(last, after.dataTo!!)).map { wire.dayRange(it.start, it.endInclusive) },
                inProgress = YearMonth.from(today) == ym,
            )
        }
        val monthsWithData = after.monthsWithData().size
        fun feature(name: String, requiredFull: Int?, requiredWithData: Int?): FeatureAvailabilityDto {
            val openNow = (requiredFull == null || fullAfter.size >= requiredFull) && (requiredWithData == null || monthsWithData >= requiredWithData)
            val openBefore = (requiredFull == null || fullBefore.size >= requiredFull) && (requiredWithData == null || before.monthsWithData().size >= requiredWithData)
            return FeatureAvailabilityDto(name, openNow, openNow && !openBefore, if (openNow) null else requiredFull, if (openNow) null else requiredWithData)
        }
        val notices = buildList {
            if (upload.operationCount == 0) add("empty_statement")
            else if (fresh.isNotEmpty() && scopeOps.none { it.direction == Direction.Debit }) add("no_expenses")
        }
        return ImportResultDto(
            operationCount = upload.operationCount,
            newCount = fresh.size,
            duplicateCount = outcome.duplicates,
            unreadCount = upload.unreadLines.size,
            firstOperationAt = upload.firstOperationAt?.let(wire::time),
            lastOperationAt = upload.lastOperationAt?.let(wire::time),
            isFirstImport = isFirst,
            totals = totals,
            accounts = ledger.accounts.values.filter { a -> scopeOps.any { it.tx.accountId == a.id } }.map { ImportAccountDto(it.typeName, it.mask, it.type == AccountType.OtherBank) },
            categorizedCount = scopeOps.size - uncategorized,
            ownTransferCount = ownTransfers,
            uncategorizedCount = uncategorized,
            uncategorizedFilters = if (uncategorized > 0) OperationsFilterDto(selection = Selections.UNCATEGORIZED, selectionName = "Без категории") else null,
            coverage = ImportCoverageDto(
                fullMonths = fullAfter.size, fullMonthsBefore = fullBefore.size, newlyFullMonths = newlyFull.map(wire::month),
                incompleteMonths = incomplete,
                features = listOf(
                    feature("comparison", 1, null), feature("monthly_chart", null, 2), feature("typical", 2, null),
                    feature("regular_payments", 2, null), feature("notable_spending", 3, null), feature("small_frequent", 1, null),
                    feature("year_forecast", 12, null),
                ),
            ),
            notices = notices,
        )
    }

    fun categories(): CategoriesListDto = CategoriesListDto(
        Categories.all.sortedWith(compareBy({ !it.isSystem }, { Direction.Debit !in it.kinds })).map {
            CategoryDto(it.id, it.name, it.icon, it.isSystem, it.kinds.map { k -> if (k == Direction.Debit) "expense" else "income" }, it.assignable, it.note)
        },
    )
}
