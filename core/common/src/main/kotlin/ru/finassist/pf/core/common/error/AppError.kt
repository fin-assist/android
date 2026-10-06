package ru.finassist.pf.core.common.error

import java.time.OffsetDateTime

/**
 * Error as the UI sees it. API error codes are mapped here by the network layer (or thrown directly by the
 * mock); screens branch on the subtype, never on HTTP status or raw code strings.
 */
sealed class AppError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {

    /** No network / timeout / connection reset — "Нет сети" states. */
    class Offline(cause: Throwable? = null) : AppError("offline", cause)

    /** Session is gone (`INVALID_TOKEN`, `TOKEN_REVOKED` after a failed refresh): go to the sign-in screen. */
    class Unauthorized(val code: String) : AppError(code)

    /** `RATE_LIMITED` with the moment to retry at (may be null if the server did not say). */
    class RateLimited(val retryAt: OffsetDateTime?) : AppError("rate_limited")

    /** 409 `REQUEST_IN_PROGRESS`: the same idempotent request is still running — retry after [retryAfterSeconds]. */
    class InProgress(val retryAfterSeconds: Int?) : AppError("request_in_progress")

    /**
     * Any other API error with a machine-readable [code] (`WRONG_BANK`, `LIMIT_EXCEEDED`, …). Screens that know
     * the code handle it; others show a generic message by [httpStatus].
     */
    class Api(
        val code: String,
        val httpStatus: Int,
        val details: List<FieldError> = emptyList(),
        val retryAt: OffsetDateTime? = null,
        val resetsAt: OffsetDateTime? = null,
        message: String? = null,
    ) : AppError(message ?: code)

    /** 5xx or a malformed response. */
    class Server(val httpStatus: Int, cause: Throwable? = null) : AppError("server_$httpStatus", cause)

    /** Anything unexpected on the client side. */
    class Unknown(cause: Throwable) : AppError(cause.message, cause)

    data class FieldError(val field: String?, val code: String, val message: String)
}

/** Converts any throwable into an [AppError]; already-typed errors pass through. */
fun Throwable.toAppError(): AppError = when (this) {
    is AppError -> this
    is java.io.IOException -> AppError.Offline(this)
    else -> AppError.Unknown(this)
}
