package ru.finassist.pf.core.api.model

import kotlinx.serialization.Serializable
import ru.finassist.pf.core.common.time.ApiDateTime

/** Wire format of every error response: exactly one `error` object. */
@Serializable
data class ErrorResponse(val error: ApiError)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: List<ApiErrorDetail>? = null,
    val retryAt: ApiDateTime? = null,
    val resetsAt: ApiDateTime? = null,
)

@Serializable
data class ApiErrorDetail(
    val field: String? = null,
    val code: String,
    val message: String,
)

/** Error codes from api.md §8 that screens branch on. Unknown codes are handled by HTTP status. */
object ErrorCodes {
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val INVALID_PHONE = "INVALID_PHONE"
    const val INVALID_TOKEN = "INVALID_TOKEN"
    const val TOKEN_EXPIRED = "TOKEN_EXPIRED"
    const val TOKEN_REVOKED = "TOKEN_REVOKED"
    const val CONSENT_REQUIRED = "CONSENT_REQUIRED"
    const val LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    const val NOT_FOUND = "NOT_FOUND"
    const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"
    const val REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS"
    const val FILE_TOO_LARGE = "FILE_TOO_LARGE"
    const val WRONG_FORMAT = "WRONG_FORMAT"
    const val CSV_NOT_ACCEPTED = "CSV_NOT_ACCEPTED"
    const val WRONG_BANK = "WRONG_BANK"
    const val CONSENT_OUTDATED = "CONSENT_OUTDATED"
    const val TOO_MANY_RESULTS = "TOO_MANY_RESULTS"
    const val SELECTION_NOT_FOUND = "SELECTION_NOT_FOUND"
    const val CATEGORY_NOT_ASSIGNABLE = "CATEGORY_NOT_ASSIGNABLE"
    const val RETRY_NOT_ALLOWED = "RETRY_NOT_ALLOWED"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    // Stream-only codes
    const val PROCESSING_FAILED = "PROCESSING_FAILED"
    const val CANCELLED = "CANCELLED"
    const val MODEL_ERROR = "MODEL_ERROR"
    const val CANNOT_ANSWER = "CANNOT_ANSWER"
}
