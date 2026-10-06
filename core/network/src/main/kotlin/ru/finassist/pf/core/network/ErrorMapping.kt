package ru.finassist.pf.core.network

import kotlinx.serialization.SerializationException
import okhttp3.Response
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.ApiError
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.ErrorResponse
import ru.finassist.pf.core.common.error.AppError
import java.io.IOException

/**
 * Maps an HTTP error response to [AppError]. Only `error.code` matters for branching (api.md); HTTP status
 * is the fallback when the code is unknown. Bodies that are not our JSON (an ingress 502 page) become
 * [AppError.Server].
 */
internal fun mapHttpError(status: Int, body: String?, retryAfterHeader: String?): AppError {
    val parsed: ApiError? = body?.takeIf { it.isNotBlank() }?.let {
        runCatching { ApiJson.decodeFromString(ErrorResponse.serializer(), it).error }.getOrNull()
    }
    if (parsed == null) {
        return if (status >= 500) AppError.Server(status) else AppError.Api(code = "HTTP_$status", httpStatus = status)
    }
    return when (parsed.code) {
        ErrorCodes.INVALID_TOKEN, ErrorCodes.TOKEN_REVOKED ->
            if (status == 401) AppError.Unauthorized(parsed.code) else parsed.toApi(status)
        ErrorCodes.RATE_LIMITED -> AppError.RateLimited(parsed.retryAt)
        ErrorCodes.REQUEST_IN_PROGRESS -> AppError.InProgress(retryAfterHeader?.toIntOrNull())
        else -> if (status >= 500) AppError.Server(status) else parsed.toApi(status)
    }
}

private fun ApiError.toApi(status: Int) = AppError.Api(
    code = code,
    httpStatus = status,
    details = details.orEmpty().map { AppError.FieldError(it.field, it.code, it.message) },
    retryAt = retryAt,
    resetsAt = resetsAt,
    message = message,
)

internal fun Response.toAppError(): AppError {
    val body = runCatching { peekBody(ERROR_BODY_LIMIT).string() }.getOrNull()
    return mapHttpError(code, body, header("Retry-After"))
}

/** Any throwable from an HTTP call → [AppError]. */
internal fun Throwable.toNetworkError(): AppError = when (this) {
    is AppError -> this
    is kotlinx.coroutines.CancellationException -> throw this
    is IOException -> AppError.Offline(this)
    is SerializationException -> AppError.Server(200, this)
    else -> AppError.Unknown(this)
}

private const val ERROR_BODY_LIMIT = 64L * 1024
