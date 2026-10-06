package ru.finassist.pf.mock.api

import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.CategoriesApi
import ru.finassist.pf.core.api.OperationsApi
import ru.finassist.pf.core.api.model.CategoriesList
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.ChangeCategoryResponse
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.FeedState
import ru.finassist.pf.core.api.model.OperationDetails
import ru.finassist.pf.core.api.model.OperationsList
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.domain.OperationViews
import ru.finassist.pf.mock.domain.OwnTransferPair
import ru.finassist.pf.mock.domain.Record
import ru.finassist.pf.mock.domain.Search
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Feed, search, details and category change (api.md 4–5). */
class MockOperationsApi(private val backend: MockBackend) : OperationsApi, CategoriesApi {

    private val zone get() = backend.operationScope.zone
    private val views = OperationViews(backend.ledger, zone)
    private val search = Search(backend.ledger, backend.catalog, backend.analytics)

    override suspend fun listOperations(query: OperationsQuery): OperationsList {
        backend.simulateNetwork()
        backend.requireSession()
        val isSearch = !query.filter.isEmpty || query.allTime
        if (isSearch && query.before != null) throw AppError.Api(ErrorCodes.VALIDATION_ERROR, 400, message = "before with filters")
        if (query.allTime && (query.filter.from != null || query.filter.to != null)) throw AppError.Api(ErrorCodes.VALIDATION_ERROR, 400, message = "all_time with period")
        return backend.mutex.withLock { if (isSearch) searchPage(query) else feedPage(query) }
    }

    /** Three calendar months, newest first, ending with the last month that has data (or before `before`). */
    private fun feedPage(query: OperationsQuery): OperationsList {
        val records = views.records()
        val stale = backend.coverage().lastDay?.let { ChronoUnit.DAYS.between(it, backend.now().atZoneSameInstant(zone).toLocalDate()) > 14 } ?: true
        val state = FeedState(
            stale = stale,
            lastOperationAt = backend.ledger.all.maxOfOrNull { it.occurredAt },
            lastUploadedAt = backend.uploads.filter { it.isDone }.maxOfOrNull { it.uploadedAt },
        )
        if (records.isEmpty()) return OperationsList(items = emptyList(), summary = emptyList(), state = state, range = null, nextBefore = null)

        val endMonth = query.before?.let { YearMonth.from(it.atZoneSameInstant(zone).toLocalDate().minusDays(1)) }
            ?: YearMonth.from(records.first().occurredAt.atZoneSameInstant(zone).toLocalDate())
        val startMonth = endMonth.minusMonths(2)
        val from = startMonth.atDay(1).atStartOfDay(zone).toOffsetDateTime()
        val to = endMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toOffsetDateTime()
        val page = records.filter { !it.occurredAt.isBefore(from) && it.occurredAt.isBefore(to) }
        val hasOlder = records.any { it.occurredAt.isBefore(from) }
        val coverage = backend.coverage()
        val summary = (0..2).map { endMonth.minusMonths(it.toLong()) }
            .filter { coverage.hasData(it) }
            .map { backend.analytics.monthSummary(it) }
        return OperationsList(
            items = page.map(views::item),
            summary = summary,
            state = state,
            range = DateRange(from, to),
            nextBefore = if (hasOlder) from else null,
        )
    }

    private fun searchPage(query: OperationsQuery): OperationsList {
        val f = query.filter
        f.selection?.let { sel ->
            runCatching { search.validateSelection(sel) }.onFailure {
                if (it is Search.UnknownSelection) throw AppError.Api(ErrorCodes.SELECTION_NOT_FOUND, 422)
            }
        }
        val today = backend.now().atZoneSameInstant(zone).toLocalDate()
        val defaultPeriod = f.from == null && f.to == null && !query.allTime
        val from = f.from ?: if (defaultPeriod) today.minusMonths(12).atStartOfDay(zone).toOffsetDateTime() else null
        val to = f.to ?: if (defaultPeriod) today.plusDays(1).atStartOfDay(zone).toOffsetDateTime() else null

        var records = views.records()
        if (from != null) records = records.filter { !it.occurredAt.isBefore(from) }
        if (to != null) records = records.filter { it.occurredAt.isBefore(to) }
        val matched = search.apply(records, f)
        if (matched.size > MAX_SEARCH_RESULTS) throw AppError.Api(ErrorCodes.TOO_MANY_RESULTS, 422)
        val hasOlderData = if (defaultPeriod) views.records().any { it.occurredAt.isBefore(from!!) } else null
        return OperationsList(
            items = matched.map(views::item),
            totalCount = matched.size,
            range = if (from != null && to != null) DateRange(from, to) else null,
            hasOlderData = hasOlderData,
        )
    }

    override suspend fun getOperation(id: String): OperationDetails {
        backend.simulateNetwork()
        backend.requireSession()
        val record = views.record(id) ?: throw AppError.Api(ErrorCodes.NOT_FOUND, 404)
        return views.details(record)
    }

    override suspend fun changeCategory(id: String, request: ChangeCategoryRequest): ChangeCategoryResponse {
        backend.simulateNetwork()
        backend.requireSession()
        val category = backend.catalog.byId(request.categoryId) ?: throw AppError.Api(ErrorCodes.CATEGORY_NOT_ASSIGNABLE, 422)
        return backend.mutex.withLock {
            views.record(id) ?: throw AppError.Api(ErrorCodes.NOT_FOUND, 404)
            val (items, replaced) = try {
                backend.ledger.changeCategory(id, category)
            } catch (e: IllegalArgumentException) {
                throw AppError.Api(ErrorCodes.CATEGORY_NOT_ASSIGNABLE, 422, message = e.message)
            }
            ChangeCategoryResponse(
                items = items.map { item ->
                    when (item) {
                        is OwnTransferPair -> views.details(Record.Pair(item))
                        is ru.finassist.pf.mock.domain.Operation -> views.details(Record.Single(item))
                        else -> error("unexpected record $item")
                    }
                },
                replacedId = replaced,
            )
        }
    }

    override suspend fun listCategories(): CategoriesList {
        backend.simulateNetwork()
        backend.requireSession()
        return CategoriesList(backend.catalog.categories)
    }

    private companion object {
        const val MAX_SEARCH_RESULTS = 10_000
    }
}
