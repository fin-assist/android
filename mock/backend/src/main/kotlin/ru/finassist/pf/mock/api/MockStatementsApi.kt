package ru.finassist.pf.mock.api

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.UploadFile
import ru.finassist.pf.core.api.model.AnalyticsFeature
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.FeatureAvailability
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.ImportAccount
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.api.model.ImportCoverage
import ru.finassist.pf.core.api.model.ImportNotice
import ru.finassist.pf.core.api.model.ImportProgress
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.ImportStage
import ru.finassist.pf.core.api.model.ImportTotals
import ru.finassist.pf.core.api.model.ImportTotalsScope
import ru.finassist.pf.core.api.model.IncompleteMonth
import ru.finassist.pf.core.api.model.LoadedSummary
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.StatementsList
import ru.finassist.pf.core.api.model.StatementsSummary
import ru.finassist.pf.core.api.model.UnreadLine
import ru.finassist.pf.core.api.model.UnreadLinesList
import ru.finassist.pf.core.api.model.UnreadReason
import ru.finassist.pf.core.api.model.Upload
import ru.finassist.pf.core.api.model.UploadAccepted
import ru.finassist.pf.core.api.model.UploadStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.toApiMonth
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.domain.AnalyticsEngine
import ru.finassist.pf.mock.domain.Coverage
import ru.finassist.pf.mock.domain.Operation
import ru.finassist.pf.mock.ofx.OfxCheck
import ru.finassist.pf.mock.ofx.OfxDocument
import ru.finassist.pf.mock.ofx.OfxParser
import java.time.OffsetDateTime
import java.util.UUID

/** Uploads, progress stream, history, unread lines (api.md 3). Import runs in the background like the real one. */
class MockStatementsApi(private val backend: MockBackend) : StatementsApi {

    private val jobs = HashMap<String, Job>()
    private val cancelled = HashSet<String>()

    override suspend fun getImportConfig(): ImportConfig {
        backend.simulateNetwork()
        backend.requireSession()
        val now = backend.now()
        val zone = now.offset
        val tomorrow = now.toLocalDate().plusDays(1).atStartOfDay().atOffset(zone)
        val lastOp = backend.ledger.all.maxOfOrNull { it.occurredAt }
        val from = lastOp?.toLocalDate()?.atStartOfDay()?.atOffset(zone) ?: now.minusMonths(12).toLocalDate().atStartOfDay().atOffset(zone)
        val count = backend.ledger.all.size
        return ImportConfig(
            downloadUrl = "https://www.tbank-online.com/mybank/api/operations/timeline/public/legacy/v1/export_operations" +
                "?appName=supreme&appVersion=0.0.1&origin=web%2Cib5%2Cplatform&start=${from.toInstant().toEpochMilli()}&end=${tomorrow.toInstant().toEpochMilli()}&format=ofx",
            loginUrl = "https://www.tbank.ru/login/",
            maxFileSizeBytes = backend.config.maxFileSizeBytes,
            acceptedFormats = listOf("OFX"),
            suggestedPeriod = DateRange(from, tomorrow),
            loaded = if (count == 0) null else LoadedSummary(
                firstOperationAt = backend.ledger.all.minOf { it.occurredAt },
                lastOperationAt = lastOp!!,
                operationCount = count,
            ),
        )
    }

    override suspend fun uploadStatement(key: IdempotencyKey, file: UploadFile): UploadAccepted {
        backend.simulateNetwork()
        backend.requireSession()
        return backend.idempotent("statements.upload:${key.value}") {
            if (file.size > backend.config.maxFileSizeBytes) throw AppError.Api(ErrorCodes.FILE_TOO_LARGE, 413)
            val text = file.open().use { it.readBytes() }.toString(Charsets.UTF_8)
            when (val check = OfxParser.check(text)) {
                OfxCheck.Csv -> throw AppError.Api(ErrorCodes.CSV_NOT_ACCEPTED, 422)
                OfxCheck.NotOfx -> throw AppError.Api(ErrorCodes.WRONG_FORMAT, 422)
                is OfxCheck.WrongBank -> throw AppError.Api(ErrorCodes.WRONG_BANK, 422, message = "bank ${check.bankId}")
                OfxCheck.Ok -> Unit
            }
            val doc = OfxParser.parse(text)
            val upload = startUpload(file.name, doc, backend.now())
            UploadAccepted(uploadId = upload.id, fileName = upload.fileName)
        }
    }

    /** Also used at start-up to preload the bundled statement (synchronously, without delays). */
    suspend fun startUpload(fileName: String, doc: OfxDocument, uploadedAt: OffsetDateTime, instant: Boolean = false): MockBackend.Upload {
        val now = backend.now()
        val upload = MockBackend.Upload(
            id = UUID.randomUUID().toString(),
            fileName = fileName,
            uploadedAt = uploadedAt,
            coverageFrom = doc.coverageFrom ?: doc.transactions.minOfOrNull { it.postedAt } ?: now,
            coverageTo = doc.coverageTo ?: doc.transactions.maxOfOrNull { it.postedAt } ?: now,
            unreadLines = doc.unreadLines,
            operationCount = doc.transactions.size,
            firstOperationAt = doc.transactions.minOfOrNull { it.postedAt },
            lastOperationAt = doc.transactions.maxOfOrNull { it.postedAt },
        )
        backend.mutex.withLock { backend.uploads.add(0, upload) }
        if (instant) {
            process(upload, doc, stepDelay = 0)
        } else {
            jobs[upload.id] = backend.scope.launch { process(upload, doc, backend.config.importStepDelayMs) }
        }
        return upload
    }

    private suspend fun process(upload: MockBackend.Upload, doc: OfxDocument, stepDelay: Long) {
        suspend fun stage(stage: ImportStage, percent: Int) {
            if (upload.id in cancelled) throw kotlinx.coroutines.CancellationException("cancelled")
            upload.events.value = ImportEvent.Progress(ImportProgress(stage, percent))
            if (stepDelay > 0) delay(stepDelay)
        }
        try {
            stage(ImportStage.PARSING, 20)
            stage(ImportStage.DEDUP, 55)
            val result = backend.mutex.withLock {
                val before = backend.coverage()
                val isFirst = backend.uploads.none { it.isDone }
                val (added, duplicates) = backend.ledger.importUpload(upload.id, doc.accounts, doc.transactions)
                stage(ImportStage.RULES, 85)
                upload.result = buildResult(upload, doc, added, duplicates, isFirst, before)
                upload.result!!
            }
            upload.events.value = ImportEvent.Complete(result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            discard(upload)
            upload.events.value = ImportEvent.Error(ErrorCodes.CANCELLED, "cancelled by user")
        } catch (e: Exception) {
            discard(upload)
            upload.events.value = ImportEvent.Error(ErrorCodes.PROCESSING_FAILED, e.message ?: "failed")
        } finally {
            jobs.remove(upload.id)
            cancelled.remove(upload.id)
        }
    }

    /** Import is atomic: a failed or cancelled upload leaves nothing behind. Runs even when the job is cancelled. */
    private suspend fun discard(upload: MockBackend.Upload) = withContext(NonCancellable) {
        backend.mutex.withLock {
            backend.ledger.removeUpload(upload.id)
            backend.uploads.remove(upload)
        }
    }

    private fun buildResult(
        upload: MockBackend.Upload, doc: OfxDocument, added: List<Operation>, duplicates: Int, isFirst: Boolean, before: Coverage,
    ): ImportResult {
        val after = backend.coverage()
        val scope = backend.operationScope
        val newVisible = added.filter { it.pairId == null }
        val uncategorized = added.filter { it.isRefund && it.refundTarget == null }
        val ownTransfers = added.count { it.pairId != null || it.isOwnTransferCategory }
        val fullAfter = after.fullMonths
        val fullBefore = before.fullMonths
        val zone = scope.zone
        val today = backend.now().atZoneSameInstant(zone).toLocalDate()

        fun feature(f: AnalyticsFeature, openNow: Boolean, openBefore: Boolean, requiredFull: Int? = null, requiredWithData: Int? = null) =
            FeatureAvailability(f, open = openNow, openedNow = openNow && !openBefore,
                requiredFullMonths = requiredFull.takeIf { !openNow }, requiredMonthsWithData = requiredWithData.takeIf { !openNow })

        val features = listOf(
            feature(AnalyticsFeature.COMPARISON, fullAfter.isNotEmpty(), fullBefore.isNotEmpty(), requiredFull = 1),
            feature(AnalyticsFeature.MONTHLY_CHART, after.monthsWithData.size >= 2, before.monthsWithData.size >= 2, requiredWithData = 2),
            feature(AnalyticsFeature.TYPICAL, fullAfter.size >= 2, fullBefore.size >= 2, requiredFull = 2),
            feature(AnalyticsFeature.REGULAR_PAYMENTS, fullAfter.size >= 2, fullBefore.size >= 2, requiredFull = 2),
            feature(AnalyticsFeature.NOTABLE_SPENDING, fullAfter.size >= 3, fullBefore.size >= 3, requiredFull = 3),
            feature(AnalyticsFeature.SMALL_FREQUENT, fullAfter.isNotEmpty(), fullBefore.isNotEmpty(), requiredFull = 1),
            feature(AnalyticsFeature.YEAR_FORECAST, fullAfter.size >= 12, fullBefore.size >= 12, requiredFull = 12),
        )
        val incomplete = after.monthsWithData.filter { !after.isFullMonth(it) }.map { m ->
            val from = m.atDay(1)
            val toExcl = m.plusMonths(1).atDay(1)
            val bounds = after.dataBounds(from, toExcl)!!
            IncompleteMonth(
                month = m.toApiMonth(),
                range = DateRange(from.atStartOfDay(zone).toOffsetDateTime(), toExcl.atStartOfDay(zone).toOffsetDateTime()),
                dataFrom = bounds.first.atStartOfDay(zone).toOffsetDateTime(),
                dataTo = bounds.second.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                gaps = after.gapsWithin(from, toExcl),
                inProgress = !m.atEndOfMonth().isBefore(today),
            )
        }
        val notices = buildList {
            if (doc.transactions.isEmpty()) add(ImportNotice.EMPTY_STATEMENT)
            else if (doc.transactions.none { it.isDebit }) add(ImportNotice.NO_EXPENSES)
        }
        val totalsOps = if (isFirst) added else newVisible
        return ImportResult(
            operationCount = doc.transactions.size,
            newCount = added.size,
            duplicateCount = duplicates,
            unreadCount = doc.unreadLines.size,
            firstOperationAt = upload.firstOperationAt,
            lastOperationAt = upload.lastOperationAt,
            isFirstImport = isFirst,
            totals = if (added.isEmpty()) null else ImportTotals(
                scope = if (isFirst) ImportTotalsScope.ALL else ImportTotalsScope.NEW,
                expense = Money(scope.expenseTotal(totalsOps.filter { it.pairId == null })),
                income = Money(scope.incomeTotal(totalsOps.filter { it.pairId == null })),
            ),
            accounts = doc.accounts.map { ImportAccount(typeName = it.typeName, mask = it.mask, isOtherBank = it.isOtherBank) },
            categorizedCount = added.size - uncategorized.size,
            ownTransferCount = ownTransfers,
            uncategorizedCount = uncategorized.size,
            uncategorizedFilters = if (uncategorized.isEmpty()) null else OperationsFilter(selection = AnalyticsEngine.SELECTION_UNCATEGORIZED, selectionName = "Без категории"),
            coverage = ImportCoverage(
                fullMonths = fullAfter.size,
                fullMonthsBefore = fullBefore.size,
                newlyFullMonths = (fullAfter - fullBefore.toSet()).map { it.toApiMonth() },
                incompleteMonths = incomplete,
                features = features,
            ),
            notices = notices,
        )
    }

    override fun streamProgress(uploadId: String): Flow<ImportEvent> {
        val upload = backend.uploads.firstOrNull { it.id == uploadId }
            ?: return kotlinx.coroutines.flow.flow { throw AppError.Api(ErrorCodes.NOT_FOUND, 404) }
        return upload.events.transformWhile { event ->
            emit(event)
            event is ImportEvent.Progress
        }
    }

    override suspend fun listStatements(): StatementsList {
        backend.simulateNetwork()
        backend.requireSession()
        val done = backend.uploads.filter { it.isDone }
        val summary = if (done.isEmpty()) null else StatementsSummary(
            uploadCount = done.size,
            operationCount = backend.ledger.all.size,
            firstOperationAt = backend.ledger.all.minOf { it.occurredAt },
            lastOperationAt = backend.ledger.all.maxOf { it.occurredAt },
            gaps = backend.coverage().gaps,
        )
        return StatementsList(
            summary = summary,
            uploads = backend.uploads.filter { !it.isFailed }.map { u ->
                Upload(
                    uploadId = u.id, fileName = u.fileName,
                    status = if (u.isDone) UploadStatus.DONE else UploadStatus.PROCESSING,
                    uploadedAt = u.uploadedAt, operationCount = u.operationCount, unreadCount = u.unreadLines.size,
                    firstOperationAt = u.firstOperationAt, lastOperationAt = u.lastOperationAt,
                    sourceName = MockBackend.SOURCE_NAME,
                )
            },
        )
    }

    override suspend fun deleteStatement(uploadId: String) {
        backend.simulateNetwork()
        backend.requireSession()
        val upload = backend.uploads.firstOrNull { it.id == uploadId } ?: return
        if (upload.isProcessing) {
            cancelled += uploadId
            jobs[uploadId]?.cancel()
            return
        }
        backend.mutex.withLock {
            backend.ledger.removeUpload(uploadId)
            backend.uploads.remove(upload)
        }
    }

    override suspend fun listUnreadLines(uploadId: String): UnreadLinesList {
        backend.simulateNetwork()
        backend.requireSession()
        val upload = backend.uploads.firstOrNull { it.id == uploadId } ?: throw AppError.Api(ErrorCodes.NOT_FOUND, 404)
        return UnreadLinesList(upload.unreadLines.map { toDto(upload, it) })
    }

    override suspend fun listUnreadLines(from: OffsetDateTime, to: OffsetDateTime): UnreadLinesList {
        backend.simulateNetwork()
        backend.requireSession()
        return UnreadLinesList(
            backend.uploads.filter { it.isDone }.flatMap { u ->
                u.unreadLines.filter { it.date != null && !it.date.isBefore(from) && it.date.isBefore(to) }.map { toDto(u, it) }
            },
        )
    }

    private fun toDto(upload: MockBackend.Upload, line: ru.finassist.pf.mock.ofx.UnreadLine) = UnreadLine(
        uploadId = upload.id, fileName = upload.fileName, lineNumber = line.lineNumber, date = line.date,
        reason = UnreadReason.entries.firstOrNull { it.code == line.reason } ?: UnreadReason.UNKNOWN,
        reasonName = when (line.reason) {
            "bad_amount" -> "не распознали сумму"
            "bad_date" -> "не распознали дату"
            "no_date" -> "нет даты"
            "no_description" -> "нет описания"
            else -> "строка оборвана"
        },
    )
}
