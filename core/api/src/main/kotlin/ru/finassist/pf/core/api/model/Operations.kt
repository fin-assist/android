package ru.finassist.pf.core.api.model

import kotlinx.serialization.Serializable
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.ApiDateTime
import ru.finassist.pf.core.common.time.DateRange

@Serializable(with = OperationKind.Serializer::class)
enum class OperationKind(override val code: String) : ApiCode {
    EXPENSE("expense"), INCOME("income"), OWN_TRANSFER("own_transfer"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<OperationKind>("OperationKind", entries, UNKNOWN)
}

@Serializable(with = OperationStatus.Serializer::class)
enum class OperationStatus(override val code: String) : ApiCode {
    POSTED("posted"), PENDING("pending"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<OperationStatus>("OperationStatus", entries, UNKNOWN)
}

/** Account as shown in lists; [spoken] (TalkBack label) is present only in details. */
@Serializable
data class Account(
    val typeName: String,
    val mask: String? = null,
    val spoken: String? = null,
)

/** Row of the feed / search results. For `own_transfer` [fromAccount]/[toAccount] are set instead of [account]. */
@Serializable
data class OperationItem(
    val id: String,
    val kind: OperationKind,
    val occurredAt: ApiDateTime,
    val amount: Money,
    val currency: String,
    val title: String,
    val categoryId: String,
    val categoryName: String,
    val categoryIcon: String,
    val isCategoryManual: Boolean,
    val note: String? = null,
    val status: OperationStatus,
    val account: Account? = null,
    val fromAccount: Account? = null,
    val toAccount: Account? = null,
)

/** Operation details screen. Optional fields come only when the source provides them. */
@Serializable
data class OperationDetails(
    val id: String,
    val kind: OperationKind,
    val occurredAt: ApiDateTime,
    val amount: Money,
    val currency: String,
    val title: String,
    val categoryId: String,
    val categoryName: String,
    val categoryIcon: String,
    val isCategoryManual: Boolean,
    val note: String? = null,
    val status: OperationStatus,
    val account: Account? = null,
    val fromAccount: Account? = null,
    val toAccount: Account? = null,
    val description: String? = null,
    val postedAt: ApiDateTime? = null,
    val noteDetails: String? = null,
    val bankCategory: String? = null,
    val mcc: String? = null,
    val sourceName: String,
) {
    fun toItem() = OperationItem(
        id, kind, occurredAt, amount, currency, title, categoryId, categoryName, categoryIcon,
        isCategoryManual, note, status, account, fromAccount, toAccount,
    )
}

@Serializable
data class MonthSummary(
    val month: String,
    val range: DateRange,
    val expense: Money,
    val income: Money,
    val dataTo: ApiDateTime? = null,
)

@Serializable
data class FeedState(
    val stale: Boolean,
    val lastOperationAt: ApiDateTime? = null,
    val lastUploadedAt: ApiDateTime? = null,
)

/**
 * Feed page (`summary`, `state`, `range`, `nextBefore`) or search result (`totalCount`, `range`,
 * `hasOlderData`). Grouping by day/month is done on the client in the profile time zone.
 */
@Serializable
data class OperationsList(
    val items: List<OperationItem>,
    val summary: List<MonthSummary>? = null,
    val state: FeedState? = null,
    val range: DateRange? = null,
    val nextBefore: ApiDateTime? = null,
    val totalCount: Int? = null,
    val hasOlderData: Boolean? = null,
)

/** Query of `listOperations`. Feed = all filters empty (+ optional [before]); search = at least one filter. */
data class OperationsQuery(
    val filter: OperationsFilter = OperationsFilter(),
    val allTime: Boolean = false,
    val before: ApiDateTime? = null,
)

@Serializable
data class ChangeCategoryRequest(val categoryId: String)

@Serializable
data class ChangeCategoryResponse(
    val items: List<OperationDetails>,
    val replacedId: String? = null,
)

@Serializable(with = CategoryKind.Serializer::class)
enum class CategoryKind(override val code: String) : ApiCode {
    EXPENSE("expense"), INCOME("income"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<CategoryKind>("CategoryKind", entries, UNKNOWN)
}

@Serializable
data class Category(
    val id: String,
    val name: String,
    val icon: String,
    val isSystem: Boolean,
    val kinds: List<CategoryKind>,
    val assignable: Boolean,
    val note: String? = null,
)

@Serializable
data class CategoriesList(val categories: List<Category>)

/** System category keys the client relies on (own transfers are never expenses, refunds reduce a purchase). */
object SystemCategories {
    const val OWN_TRANSFER = "cat_own_transfer"
    const val REFUND = "cat_refund"
}
