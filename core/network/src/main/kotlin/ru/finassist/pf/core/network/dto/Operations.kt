package ru.finassist.pf.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class AccountDto(val typeName: String, val mask: String? = null, val spoken: String? = null)

/** One shape for feed items and details: optional fields are absent where the endpoint does not send them. */
@Serializable
data class OperationDto(
    val id: String,
    val kind: String,
    val occurredAt: String,
    val amount: Long,
    val currency: String,
    val title: String,
    val categoryId: String,
    val categoryName: String,
    val categoryIcon: String,
    val isCategoryManual: Boolean,
    val note: String? = null,
    val status: String,
    val account: AccountDto? = null,
    val fromAccount: AccountDto? = null,
    val toAccount: AccountDto? = null,
    // details only
    val description: String? = null,
    val postedAt: String? = null,
    val noteDetails: String? = null,
    val bankCategory: String? = null,
    val mcc: String? = null,
    val sourceName: String? = null,
)

@Serializable
data class OperationsListDto(
    val items: List<OperationDto>,
    val summary: List<MonthSummaryDto>? = null,
    val state: FeedStateDto? = null,
    val range: RangeDto? = null,
    val nextBefore: String? = null,
    val totalCount: Int? = null,
    val hasOlderData: Boolean? = null,
)

@Serializable
data class MonthSummaryDto(val month: String, val range: RangeDto, val expense: Long, val income: Long, val dataTo: String? = null)

@Serializable
data class FeedStateDto(val stale: Boolean, val lastOperationAt: String? = null, val lastUploadedAt: String? = null)

@Serializable
data class ChangeCategoryRequestDto(val categoryId: String)

@Serializable
data class ChangeCategoryResponseDto(val items: List<OperationDto>, val replacedId: String? = null)

@Serializable
data class CategoriesListDto(val categories: List<CategoryDto>)

@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val icon: String,
    val isSystem: Boolean,
    val kinds: List<String>,
    val assignable: Boolean,
    val note: String? = null,
)
