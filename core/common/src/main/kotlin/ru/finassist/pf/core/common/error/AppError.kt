package ru.finassist.pf.core.common.error

import java.time.OffsetDateTime

/**
 * Everything a screen may need to branch on. Mapped from HTTP errors (by `error.code`), transport failures
 * and stream `error` events. Unknown codes collapse into [Http] with the raw code kept for logs.
 */
sealed class AppError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    /** No connectivity, DNS failure, timeout before a response, connection reset. */
    class Offline(cause: Throwable? = null) : AppError("offline", cause)

    /** 401 that survived a token refresh → session is over. */
    object Unauthorized : AppError("unauthorized") { private fun readResolve(): Any = Unauthorized }

    class RateLimited(val retryAt: OffsetDateTime?) : AppError("rate limited")

    class Validation(val details: List<Detail>) : AppError("validation") {
        data class Detail(val field: String?, val code: String, val message: String)
    }

    object NotFound : AppError("not found") { private fun readResolve(): Any = NotFound }

    /** Idempotency: the first request with this key is still running; retry after [retryAfterSeconds]. */
    class RequestInProgress(val retryAfterSeconds: Int?) : AppError("request in progress")
    object IdempotencyConflict : AppError("idempotency conflict") { private fun readResolve(): Any = IdempotencyConflict }

    // Domain codes (api.md §8)
    object InvalidPhone : AppError("INVALID_PHONE") { private fun readResolve(): Any = InvalidPhone }
    object TokenExpired : AppError("TOKEN_EXPIRED") { private fun readResolve(): Any = TokenExpired }
    object ConsentRequired : AppError("CONSENT_REQUIRED") { private fun readResolve(): Any = ConsentRequired }
    object ConsentOutdated : AppError("CONSENT_OUTDATED") { private fun readResolve(): Any = ConsentOutdated }
    class LimitExceeded(val resetsAt: OffsetDateTime?) : AppError("LIMIT_EXCEEDED")
    object FileTooLarge : AppError("FILE_TOO_LARGE") { private fun readResolve(): Any = FileTooLarge }
    object WrongFormat : AppError("WRONG_FORMAT") { private fun readResolve(): Any = WrongFormat }
    object CsvNotAccepted : AppError("CSV_NOT_ACCEPTED") { private fun readResolve(): Any = CsvNotAccepted }
    object WrongBank : AppError("WRONG_BANK") { private fun readResolve(): Any = WrongBank }
    object TooManyResults : AppError("TOO_MANY_RESULTS") { private fun readResolve(): Any = TooManyResults }
    object SelectionNotFound : AppError("SELECTION_NOT_FOUND") { private fun readResolve(): Any = SelectionNotFound }
    object CategoryNotAssignable : AppError("CATEGORY_NOT_ASSIGNABLE") { private fun readResolve(): Any = CategoryNotAssignable }
    object RetryNotAllowed : AppError("RETRY_NOT_ALLOWED") { private fun readResolve(): Any = RetryNotAllowed }

    /** Any other HTTP error: unknown code or 5xx. Screens show a generic error by [status]. */
    class Http(val status: Int, val code: String?, val serverMessage: String?) : AppError("http $status $code")

    /** Malformed response (JSON that does not match the contract). Treated like a server error in UI. */
    class Malformed(cause: Throwable) : AppError("malformed response", cause)

    val isRetryable: Boolean
        get() = this is Offline || this is RequestInProgress || (this is Http && status >= 500)
}
