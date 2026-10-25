package ru.finassist.pf.mock.api

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import ru.finassist.pf.core.api.model.ImportNoticeCode
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
import ru.finassist.pf.core.api.model.SystemCategories
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
import ru.finassist.pf.mock.MockErrorCatalog
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.apiError
import ru.finassist.pf.mock.MockCodes
import ru.finassist.pf.mock.domain.AnalyticsEngine
import ru.finassist.pf.mock.domain.Catalog
import ru.finassist.pf.mock.domain.Coverage
import ru.finassist.pf.mock.domain.Operation
import ru.finassist.pf.mock.domain.ServerTexts
import ru.finassist.pf.mock.ofx.OfxCheck
import ru.finassist.pf.mock.ofx.OfxDocument
import ru.finassist.pf.mock.ofx.OfxParser
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Uploads, progress stream, history, unread lines (api.md 3). Import runs in the background like the real one. */
class MockStatementsApi(private val backend: MockBackend) : StatementsApi {

    // Touched by request coroutines and by the import jobs, on different threads.
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /**
     * The `error` a failed or cancelled upload ended with: the upload itself is discarded, but re-opening its progress
     * stream gives that event at once (api.md 3.3), not 404.
     */
    private val failures = ConcurrentHashMap<String, ImportEvent.Error>()

    override suspend fun getImportConfig(): ImportConfig {
        backend.simulateNetwork()
        backend.requireSession()
        // Calendar days in the profile zone (FIN-92), each bound with its own offset (DST).
        val zone = backend.profileZone()
        val today = backend.now().atZoneSameInstant(zone).toLocalDate()
        val tomorrow = today.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
        val lastOp = backend.ledger.all.maxOfOrNull { it.occurredAt }
        val from = (lastOp?.atZoneSameInstant(zone)?.toLocalDate() ?: today.minusMonths(12)).atStartOfDay(zone).toOffsetDateTime()
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
            if (file.size > backend.config.maxFileSizeBytes) throw backend.apiError(ErrorCodes.FILE_TOO_LARGE, 413)
            val text = file.open().use { it.readBytes() }.toString(Charsets.UTF_8)
            when (val check = OfxParser.check(text)) {
                OfxCheck.Csv -> throw backend.apiError(ErrorCodes.CSV_NOT_ACCEPTED, 422)
                OfxCheck.NotOfx -> throw backend.apiError(ErrorCodes.WRONG_FORMAT, 422)
                is OfxCheck.WrongBank -> throw backend.apiError(ErrorCodes.WRONG_BANK, 422, message = "bank ${check.bankId}")
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
            accountPeriods = doc.accountPeriods,
            operationCount = doc.transactions.size,
            firstOperationAt = doc.transactions.minOfOrNull { it.postedAt },
            lastOperationAt = doc.transactions.maxOfOrNull { it.postedAt },
        )
        backend.mutex.withLock { backend.uploads.add(0, upload) }
        if (instant) {
            process(upload, doc, stepDelay = 0)
        } else {
            // ATOMIC: the job runs [process] even when cancelled before it started, so a cancelled upload is
            // always discarded and its progress stream ends with CANCELLED (FIN-76). A job cancelled under the
            // default start would never run, leaving the upload PROCESSING for good.
            val job = backend.scope.launch(start = CoroutineStart.ATOMIC) { process(upload, doc, backend.config.importStepDelayMs) }
            jobs[upload.id] = job
            // Removed only once registered: a job that finishes before this line still leaves no entry behind.
            job.invokeOnCompletion { jobs.remove(upload.id, job) }
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
            // api.md 3.3: a parse that has not finished in 3 minutes from its start fails like any other.
            val result = withTimeout(backend.config.importTimeoutMs) {
                stage(ImportStage.PARSING, 20)
                stage(ImportStage.DEDUP, 55)
                backend.mutex.withLock {
                    val before = backend.coverage()
                    val isFirst = backend.uploads.none { it.isDone }
                    val (added, duplicates) = backend.ledger.importUpload(upload.id, doc.accounts, doc.transactions)
                    stage(ImportStage.RULES, 85)
                    // In the same transaction, after the last point a cancellation can stop it: unread lines are closed
                    // both ways — earlier uploads' against this one, this one's against later finished ones (FIN-79).
                    backend.unreadResolver.recompute(backend.uploads, committing = upload)
                    upload.result = buildResult(upload, doc, added, duplicates, isFirst, before)
                    backend.dataChanges.uploadsChanged()
                    upload.result!!
                }
            }
            upload.events.value = ImportEvent.Complete(result)
        } catch (e: TimeoutCancellationException) {
            // Caught before CancellationException, its parent: a timeout is a failed parse, not a cancel by the user.
            failWith(upload, ImportEvent.Error(ErrorCodes.PROCESSING_FAILED, "parsing did not finish in time", MockErrorCatalog.presentation(ErrorCodes.PROCESSING_FAILED)))
        } catch (e: kotlinx.coroutines.CancellationException) {
            failWith(upload, ImportEvent.Error(ErrorCodes.CANCELLED, "cancelled by user", MockErrorCatalog.presentation(ErrorCodes.CANCELLED)))
        } catch (e: Exception) {
            failWith(upload, ImportEvent.Error(ErrorCodes.PROCESSING_FAILED, e.message ?: "failed", MockErrorCatalog.presentation(ErrorCodes.PROCESSING_FAILED)))
        } finally {
            cancelled.remove(upload.id)
        }
    }

    private suspend fun failWith(upload: MockBackend.Upload, error: ImportEvent.Error) {
        // Kept before the upload goes: a stream re-opened in between finds the outcome, not 404.
        failures[upload.id] = error
        discard(upload)
        upload.events.value = error
    }

    /** Import is atomic: a failed or cancelled upload leaves nothing behind. Runs even when the job is cancelled. */
    private suspend fun discard(upload: MockBackend.Upload) = withContext(NonCancellable) {
        backend.mutex.withLock {
            backend.ledger.removeUpload(upload.id)
            backend.uploads.remove(upload)
            // Whatever it may have closed is checked again (FIN-79).
            backend.unreadResolver.recompute(backend.uploads)
        }
    }

    private fun buildResult(
        upload: MockBackend.Upload, doc: OfxDocument, added: List<Operation>, duplicates: Int, isFirst: Boolean, before: Coverage,
    ): ImportResult {
        // The upload is not marked done yet: include it explicitly, or «after» would equal «before».
        val after = backend.coverage(including = upload)
        val scope = backend.operationScope
        val newVisible = added.filter { it.pairId == null }
        // «Без категории» (docs/calc.md, section 6): new operations in it and refunds without an expense category.
        val uncategorized = added.filter {
            it.category.id == SystemCategories.UNCATEGORIZED || (it.isRefund && it.refundTarget?.id == SystemCategories.UNCATEGORIZED)
        }
        val ownTransfers = added.count { it.pairId != null || it.isOwnTransferCategory }
        val fullAfter = after.fullMonths
        val fullBefore = before.fullMonths
        val zone = scope.zone
        val today = backend.now().atZoneSameInstant(zone).toLocalDate()

        fun feature(f: AnalyticsFeature, openNow: Boolean, openBefore: Boolean, requiredFull: Int? = null, requiredWithData: Int? = null) =
            FeatureAvailability(f, open = openNow, openedNow = openNow && !openBefore,
                requiredFullMonths = requiredFull.takeIf { !openNow }, requiredMonthsWithData = requiredWithData.takeIf { !openNow },
                featureName = ServerTexts.featureName(f),
                text = ServerTexts.lockedFeature(f, requiredFull, requiredWithData, fullAfter.size, after.monthsWithData.size).takeIf { !openNow })

        val features = listOf(
            feature(AnalyticsFeature.COMPARISON, fullAfter.isNotEmpty(), fullBefore.isNotEmpty(), requiredFull = 1),
            feature(AnalyticsFeature.MONTHLY_CHART, after.monthsWithData.size >= 2, before.monthsWithData.size >= 2, requiredWithData = 2),
            feature(AnalyticsFeature.TYPICAL, fullAfter.size >= 2, fullBefore.size >= 2, requiredFull = 2),
            feature(AnalyticsFeature.REGULAR_PAYMENTS, fullAfter.size >= 2, fullBefore.size >= 2, requiredFull = 2),
            feature(AnalyticsFeature.NOTABLE_SPENDING, fullAfter.size >= 3, fullBefore.size >= 3, requiredFull = 3),
            feature(AnalyticsFeature.SMALL_FREQUENT, fullAfter.isNotEmpty(), fullBefore.isNotEmpty(), requiredFull = 1),
            feature(AnalyticsFeature.YEAR_FORECAST, fullAfter.size >= 12, fullBefore.size >= 12, requiredFull = 12),
        ) + listOfNotNull(
            // A feature the client does not know: it shows the server's name and text (FIN-83).
            FeatureAvailability(
                AnalyticsFeature.UNKNOWN, open = false, openedNow = false, requiredFullMonths = 6,
                featureName = MockCodes.FEATURE_UNKNOWN_NAME, text = MockCodes.FEATURE_UNKNOWN_TEXT,
            ).takeIf { backend.config.unknownCodes },
        )
        val incomplete = after.monthsWithData.filter { !after.isFullMonth(it) }.map { m ->
            val from = m.atDay(1)
            val toExcl = m.plusMonths(1).atDay(1)
            val bounds = after.dataBounds(from, toExcl)!!
            IncompleteMonth(
                month = m.toApiMonth(),
                range = DateRange(from.atStartOfDay(zone).toOffsetDateTime(), toExcl.atStartOfDay(zone).toOffsetDateTime()),
                dataFrom = bounds.first.atStartOfDay(zone).toOffsetDateTime(),
                dataTo = backend.analytics.dataEnd(from, toExcl, after)!!,
                gaps = after.gapsWithin(from, toExcl),
                inProgress = !m.atEndOfMonth().isBefore(today),
            )
        }
        val notices = buildList {
            if (doc.transactions.isEmpty()) add(ImportNotice(ImportNoticeCode.EMPTY_STATEMENT, ServerTexts.EMPTY_STATEMENT))
            else if (doc.transactions.none { it.isDebit }) add(ImportNotice(ImportNoticeCode.NO_EXPENSES, ServerTexts.NO_EXPENSES))
            // The real server sends the raw code; the fake one parses its own events, so the e2e code arrives as UNKNOWN.
            if (backend.config.unknownCodes) add(ImportNotice(ImportNoticeCode.UNKNOWN, MockCodes.NOTICE_UNKNOWN_TEXT))
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
            uncategorizedFilters = if (uncategorized.isEmpty()) null else added.map { it.occurredAt.atZoneSameInstant(zone).toLocalDate() }.let { days ->
                OperationsFilter(
                    from = days.min().atStartOfDay(zone).toOffsetDateTime(),
                    to = days.max().plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                    selection = AnalyticsEngine.SELECTION_UNCATEGORIZED,
                    selectionName = Catalog.UNCATEGORIZED_NAME,
                )
            },
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
        // Every opening goes through droppable: maintenance and the client version come before 404 (api.md «Ошибки»).
        val upload = backend.uploads.firstOrNull { it.id == uploadId }
            ?: return backend.droppable(
                failures[uploadId]?.let { flowOf(it) } ?: kotlinx.coroutines.flow.flow { throw backend.apiError(ErrorCodes.NOT_FOUND, 404) },
            ) { true }
        return backend.droppable(
            upload.events.transformWhile { event ->
                emit(event)
                event is ImportEvent.Progress
            },
        ) { it !is ImportEvent.Progress }
    }

    override suspend fun listStatements(): StatementsList {
        backend.simulateNetwork()
        backend.requireSession()
        // Built under the mutex: an import or a deletion recomputes the closures meanwhile (FIN-79).
        return backend.mutex.withLock { statementsSnapshot() }
    }

    private fun statementsSnapshot(): StatementsList {
        val done = backend.uploads.filter { it.isDone }
        // A completed empty statement leaves the ledger empty: no summary then (api.md 3.4 `summary?`).
        val summary = if (done.isEmpty() || backend.ledger.all.isEmpty()) null else StatementsSummary(
            uploadCount = done.size,
            operationCount = backend.ledger.all.size,
            firstOperationAt = backend.ledger.all.minOf { it.occurredAt },
            lastOperationAt = backend.ledger.all.maxOf { it.occurredAt },
            gaps = backend.coverage().gaps,
        )
        return StatementsList(
            summary = summary,
            uploads = backend.uploads.filter { !it.isFailed }.map { u ->
                val period = historyPeriod(u)
                Upload(
                    uploadId = u.id, fileName = u.fileName,
                    status = if (u.isDone) UploadStatus.DONE else UploadStatus.PROCESSING,
                    uploadedAt = u.uploadedAt, operationCount = u.operationCount, unreadCount = u.openUnreadLines.size,
                    resolvedUnreadCount = u.readClosures.size,
                    resolvedByUploadedAt = u.readClosures.map { it.uploadId }.toSet()
                        .mapNotNull { id -> backend.uploads.firstOrNull { it.id == id }?.uploadedAt }.maxOrNull(),
                    periodFrom = period?.first,
                    periodTo = period?.second,
                    sourceName = MockBackend.SOURCE_NAME,
                )
            },
        )
    }

    /** Calendar days, not instants: only complete account periods participate (calc.md 4). */
    private fun historyPeriod(upload: MockBackend.Upload): Pair<String, String>? {
        if (!upload.isDone) return null
        val zone = backend.profileZone()
        val periods = upload.accountPeriods.values
        val from = periods.minOfOrNull { it.from.atZoneSameInstant(zone).toLocalDate() }
            ?: upload.firstOperationAt?.atZoneSameInstant(zone)?.toLocalDate()
        val to = periods.maxOfOrNull { it.to.atZoneSameInstant(zone).toLocalDate() }
            ?: upload.lastOperationAt?.atZoneSameInstant(zone)?.toLocalDate()
        return if (from != null && to != null) from.toString() to to.toString() else null
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
            backend.dataChanges.uploadsChanged()
            // Lines this upload closed are checked again against the uploads that remain (FIN-79).
            backend.unreadResolver.recompute(backend.uploads)
        }
    }

    override suspend fun listUnreadLines(uploadId: String): UnreadLinesList {
        backend.simulateNetwork()
        backend.requireSession()
        return backend.mutex.withLock {
            val upload = backend.uploads.firstOrNull { it.id == uploadId } ?: throw backend.apiError(ErrorCodes.NOT_FOUND, 404)
            UnreadLinesList(upload.openUnreadLines.map { toDto(upload, it) })
        }
    }

    override suspend fun listUnreadLines(from: OffsetDateTime, to: OffsetDateTime): UnreadLinesList {
        backend.simulateNetwork()
        backend.requireSession()
        return backend.mutex.withLock {
            UnreadLinesList(
                backend.uploads.filter { it.isDone }.flatMap { u ->
                    u.openUnreadLines.filter { it.date != null && !it.date.isBefore(from) && it.date.isBefore(to) }.map { toDto(u, it) }
                },
            )
        }
    }

    private fun toDto(upload: MockBackend.Upload, line: ru.finassist.pf.mock.ofx.UnreadLine) = UnreadLine(
        uploadId = upload.id, fileName = upload.fileName, lineNumber = line.lineNumber, date = line.date,
        reason = UnreadReason.entries.firstOrNull { it.code == line.reason } ?: UnreadReason.UNKNOWN,
        reasonName = when (line.reason) {
            "bad_amount" -> "не распознали сумму"
            "bad_date" -> "не распознали дату"
            "no_date" -> "нет даты"
            else -> "строка оборвана"
        },
    )
}
