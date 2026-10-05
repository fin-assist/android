package ru.finassist.pf.feature.operations.impl.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import ru.finassist.pf.core.navigation.OperationsFilter
import ru.finassist.pf.core.network.api.PfApi
import ru.finassist.pf.core.network.client.apiCall
import ru.finassist.pf.core.network.dto.ChangeCategoryRequestDto
import ru.finassist.pf.feature.operations.api.CategoriesRepository
import ru.finassist.pf.feature.operations.api.CategoryInfo
import ru.finassist.pf.feature.operations.impl.domain.FeedPage
import ru.finassist.pf.feature.operations.impl.domain.MonthSummary
import ru.finassist.pf.feature.operations.impl.domain.Operation
import ru.finassist.pf.feature.operations.impl.domain.SearchResult
import ru.finassist.pf.core.common.money.Money
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** `/v1/operations` and `/v1/categories`; no local cache beyond the category dictionary (android-plan.md §1). */
@Singleton
class OperationsRepository @Inject constructor(private val api: PfApi) : CategoriesRepository {

    data class CategoryChange(val items: List<Operation>, val replacedId: String?)

    private val _categories = MutableStateFlow<List<CategoryInfo>?>(null)
    override val categories: Flow<List<CategoryInfo>> = _categories.filterNotNull()

    /** Emits after a manual category change so lists refresh. */
    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changed: SharedFlow<Unit> = _changed

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = apiCall { api.listCategories() }.categories.map { CategoryInfo(it.id, it.name, it.icon, it.isSystem, it.kinds.toSet(), it.assignable, it.note) }
        _categories.value = list
    }

    suspend fun categoriesOrLoad(): List<CategoryInfo> = _categories.value ?: run { refresh(); _categories.value.orEmpty() }

    suspend fun feed(before: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val r = apiCall { api.listOperations(before = before) }
        FeedPage(
            items = r.items.map(Operation::from),
            summary = r.summary.orEmpty().map { MonthSummary(it.month, Money(it.expense), Money(it.income), it.dataTo?.let(OffsetDateTime::parse)) },
            stale = r.state?.stale ?: false,
            lastOperationAt = r.state?.lastOperationAt?.let(OffsetDateTime::parse),
            lastUploadedAt = r.state?.lastUploadedAt?.let(OffsetDateTime::parse),
            nextBefore = r.nextBefore,
        )
    }

    suspend fun search(f: OperationsFilter, allTime: Boolean): SearchResult = withContext(Dispatchers.IO) {
        val r = apiCall {
            api.listOperations(
                from = if (allTime) null else f.from, to = if (allTime) null else f.to, allTime = if (allTime) true else null,
                q = f.q?.takeIf { it.isNotBlank() }, categoryId = f.categoryId, kind = f.kind, amountFrom = f.amountFrom, amountTo = f.amountTo,
                transferMode = f.transferMode, selection = f.selection,
            )
        }
        SearchResult(
            items = r.items.map(Operation::from),
            totalCount = r.totalCount ?: r.items.size,
            rangeFrom = r.range?.from?.let(OffsetDateTime::parse),
            rangeTo = r.range?.to?.let(OffsetDateTime::parse),
            hasOlderData = r.hasOlderData ?: false,
        )
    }

    suspend fun details(id: String): Operation = withContext(Dispatchers.IO) { Operation.from(apiCall { api.getOperation(id) }) }

    suspend fun changeCategory(id: String, categoryId: String): CategoryChange = withContext(Dispatchers.IO) {
        val r = apiCall { api.changeCategory(id, ChangeCategoryRequestDto(categoryId)) }
        _changed.tryEmit(Unit)
        CategoryChange(r.items.map(Operation::from), r.replacedId)
    }
}
