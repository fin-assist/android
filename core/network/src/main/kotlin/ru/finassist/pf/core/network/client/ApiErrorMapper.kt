package ru.finassist.pf.core.network.client

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.network.dto.ErrorDto
import ru.finassist.pf.core.network.dto.ErrorResponseDto
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.OffsetDateTime

/** Maps transport and HTTP failures to [AppError]. Branches on `error.code` first, then on status (api.md «Ошибки»). */
object ApiErrorMapper {
    fun map(t: Throwable): AppError = when (t) {
        is AppError -> t
        is HttpException -> fromHttp(t.code(), t.response()?.errorBody()?.string(), t.response()?.headers()?.get("Retry-After"))
        is SocketTimeoutException, is IOException -> AppError.Offline(t)
        is SerializationException, is IllegalArgumentException -> AppError.Malformed(t)
        else -> AppError.Http(0, null, t.message)
    }

    fun fromHttp(status: Int, body: String?, retryAfter: String? = null): AppError {
        val error: ErrorDto? = body?.let { runCatching { PfJson.decodeFromString(ErrorResponseDto.serializer(), it).error }.getOrNull() }
        return fromCode(status, error, retryAfter)
    }

    fun fromCode(status: Int, error: ErrorDto?, retryAfter: String? = null): AppError = when (error?.code) {
        "VALIDATION_ERROR" -> AppError.Validation(error.details.orEmpty().map { AppError.Validation.Detail(it.field, it.code, it.message) })
        "INVALID_PHONE" -> AppError.InvalidPhone
        "INVALID_TOKEN", "TOKEN_REVOKED" -> AppError.Unauthorized
        "TOKEN_EXPIRED" -> AppError.TokenExpired
        "CONSENT_REQUIRED" -> AppError.ConsentRequired
        "LIMIT_EXCEEDED" -> AppError.LimitExceeded(error.resetsAt?.let(::parseTime))
        "NOT_FOUND" -> AppError.NotFound
        "IDEMPOTENCY_CONFLICT" -> AppError.IdempotencyConflict
        "REQUEST_IN_PROGRESS" -> AppError.RequestInProgress(retryAfter?.toIntOrNull())
        "FILE_TOO_LARGE" -> AppError.FileTooLarge
        "WRONG_FORMAT" -> AppError.WrongFormat
        "CSV_NOT_ACCEPTED" -> AppError.CsvNotAccepted
        "WRONG_BANK" -> AppError.WrongBank
        "CONSENT_OUTDATED" -> AppError.ConsentOutdated
        "TOO_MANY_RESULTS" -> AppError.TooManyResults
        "SELECTION_NOT_FOUND" -> AppError.SelectionNotFound
        "CATEGORY_NOT_ASSIGNABLE" -> AppError.CategoryNotAssignable
        "RETRY_NOT_ALLOWED" -> AppError.RetryNotAllowed
        "RATE_LIMITED" -> AppError.RateLimited(error.retryAt?.let(::parseTime) ?: retryAfter?.toLongOrNull()?.let { OffsetDateTime.now().plusSeconds(it) })
        else -> when (status) {
            401 -> AppError.Unauthorized
            404 -> AppError.NotFound
            429 -> AppError.RateLimited(retryAfter?.toLongOrNull()?.let { OffsetDateTime.now().plusSeconds(it) })
            else -> AppError.Http(status, error?.code, error?.message)
        }
    }

    private fun parseTime(s: String): OffsetDateTime? = runCatching { OffsetDateTime.parse(s) }.getOrNull()
}

/** Wraps an API call so callers deal with [AppError] only. */
suspend inline fun <T> apiCall(crossinline block: suspend () -> T): T =
    try { block() } catch (t: Throwable) { if (t is kotlinx.coroutines.CancellationException) throw t else throw ApiErrorMapper.map(t) }
