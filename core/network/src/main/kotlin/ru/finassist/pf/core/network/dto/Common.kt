package ru.finassist.pf.core.network.dto

import kotlinx.serialization.Serializable

/*
 * Wire DTOs for api.md / openapi.yaml (1.0.0-mvp). Property names are camelCase and mapped to snake_case by the
 * shared Json instance (JsonNamingStrategy.SnakeCase). Open `code` fields are plain Strings here; features map
 * them to enums with an Unknown fallback (see `codes/`). Closed `enum` fields are Kotlin enums.
 * Date-times are ISO 8601 strings with offset; parsing happens in mappers, not here.
 */

@Serializable
data class RangeDto(val from: String, val to: String)

@Serializable
data class OperationsFilterDto(
    val from: String? = null,
    val to: String? = null,
    val q: String? = null,
    val categoryId: String? = null,
    val kind: OperationKindFilterDto? = null,
    val amountFrom: Long? = null,
    val amountTo: Long? = null,
    val transferMode: String? = null,
    val selection: String? = null,
    val selectionName: String? = null,
)

/** Closed set (OpenAPI `enum`). */
@Serializable
enum class OperationKindFilterDto { expense, income }

@Serializable
data class AnalyticsParamsDto(val period: String, val date: String, val transferMode: String)

@Serializable
data class ErrorResponseDto(val error: ErrorDto)

@Serializable
data class ErrorDto(
    val code: String,
    val message: String,
    val details: List<ErrorDetailDto>? = null,
    val retryAt: String? = null,
    val resetsAt: String? = null,
)

@Serializable
data class ErrorDetailDto(val field: String? = null, val code: String, val message: String)

/** `error` event after a stream has started (generic shape shared by all SSE endpoints). */
@Serializable
data class StreamErrorDto(val code: String, val message: String, val remainingLimit: Int? = null)
