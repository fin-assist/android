package ru.finassist.pf.feature.operations.impl.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import ru.finassist.pf.core.api.CategoriesApi
import ru.finassist.pf.core.api.OperationsApi
import ru.finassist.pf.core.api.model.CategoriesList
import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.ChangeCategoryResponse
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.OperationDetails
import ru.finassist.pf.core.api.model.OperationItem
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationStatus
import ru.finassist.pf.core.api.model.OperationsList
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.operations.api.CategoriesRepository
import ru.finassist.pf.feature.statements.api.StatementsEvent
import ru.finassist.pf.feature.statements.api.StatementsRepository
import java.time.OffsetDateTime

/** Fakes shared by the view model tests of the operations feature. */
internal fun at(day: Int, hour: Int = 12): OffsetDateTime = OffsetDateTime.parse("2026-09-%02dT%02d:00:00+03:00".format(day, hour))

internal fun item(id: String, kind: OperationKind = OperationKind.EXPENSE, categoryId: String = "cat_supermarkets") = OperationItem(
    id = id, kind = kind, occurredAt = at(25), amount = Money(100_00), currency = "RUB", title = "Пятёрочка",
    categoryId = categoryId, categoryName = "Супермаркеты", categoryIcon = "cart", isCategoryManual = false,
    status = OperationStatus.POSTED,
)

internal fun details(id: String, kind: OperationKind = OperationKind.EXPENSE, categoryId: String = "cat_supermarkets") = OperationDetails(
    id = id, kind = kind, occurredAt = at(25), amount = Money(100_00), currency = "RUB", title = "Пятёрочка",
    categoryId = categoryId, categoryName = "Супермаркеты", categoryIcon = "cart", isCategoryManual = false,
    status = OperationStatus.POSTED, sourceName = "Выписка Т-Банка",
)

internal class FakeOperationsApi : OperationsApi {
    val queries = mutableListOf<OperationsQuery>()
    /** Answers to `listOperations` in order; the last one repeats. An [AppError] in the list is thrown. */
    val lists = ArrayDeque<Any>()
    var gate: CompletableDeferred<Unit>? = null

    val opened = mutableListOf<String>()
    val operations = mutableMapOf<String, OperationDetails>()
    var getFailure: AppError? = null

    val changes = mutableListOf<Pair<String, String>>()
    var changeResponse: ChangeCategoryResponse? = null
    var changeFailure: AppError? = null

    override suspend fun listOperations(query: OperationsQuery): OperationsList {
        queries += query
        gate?.await()
        val next = if (lists.size > 1) lists.removeFirst() else lists.firstOrNull() ?: OperationsList(emptyList())
        if (next is AppError) throw next
        return next as OperationsList
    }

    override suspend fun getOperation(id: String): OperationDetails {
        opened += id
        getFailure?.let { throw it }
        return operations.getValue(id)
    }

    override suspend fun changeCategory(id: String, request: ChangeCategoryRequest): ChangeCategoryResponse {
        changes += id to request.categoryId
        changeFailure?.let { throw it }
        return changeResponse ?: error("no response set")
    }
}

internal class FakeCategories(private val list: List<Category> = emptyList()) : CategoriesRepository {
    var failure: AppError? = null
    override suspend fun categories(forceRefresh: Boolean): List<Category> = failure?.let { throw it } ?: list
}

internal class FakeCategoriesApi(var list: List<Category>) : CategoriesApi {
    var calls = 0
    var failure: AppError? = null
    override suspend fun listCategories(): CategoriesList {
        calls++
        failure?.let { throw it }
        return CategoriesList(list)
    }
}

internal class FakeStatements : StatementsRepository {
    override val events = MutableSharedFlow<StatementsEvent>(extraBufferCapacity = 4)
    override fun lastResult(uploadId: String): ImportResult? = null
    override suspend fun consumeFirstImportHints(): Boolean = false
}

/** Flags that can change while the screen is open (remote config landing late). */
internal class FakeFlags(private val values: MutableMap<Flag, Boolean> = mutableMapOf()) : FeatureFlags {
    override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override fun isEnabled(flag: Flag): Boolean = values[flag] ?: flag.defaultValue
    override val version: String = "test"
    override suspend fun setUser(userId: String?) = Unit
    override suspend fun refresh() = Unit

    fun set(flag: Flag, on: Boolean) {
        values[flag] = on
        changes.tryEmit(Unit)
    }
}

internal class FakeTracker : Tracker {
    val events = mutableListOf<Pair<Event, Map<String, String>>>()
    override fun track(event: Event, params: Map<String, String>) {
        events += event to params
    }
    override fun setUserId(userId: String?) = Unit

    fun names() = events.map { it.first.name }
}

internal fun category(id: String) = Category(id, id, "circle", isSystem = false, kinds = emptyList(), assignable = true)
