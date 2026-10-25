package ru.finassist.pf.core.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import ru.finassist.pf.core.common.error.ErrorResponseInfo
import ru.finassist.pf.core.common.time.ResponseTiming
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
internal fun mapHttpError(
    status: Int,
    body: String?,
    retryAfterHeader: String?,
    dateHeader: String? = null,
    receivedElapsedMillis: Long? = null,
): AppError {
    val parsed: ApiError? = body?.takeIf { it.isNotBlank() }?.let {
        runCatching { ApiJson.decodeFromString(ErrorResponse.serializer(), it).error }.getOrNull()
    }
    if (parsed == null) {
        return if (status >= 500) AppError.Server(status) else AppError.Api(code = "HTTP_$status", httpStatus = status)
    }
    val details = parsed.details.orEmpty().map { AppError.FieldError(it.field, it.code, it.message) }
    val wait = retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    val timing = ResponseTiming(
        serverDate = dateHeader?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() },
        receivedElapsedMillis = receivedElapsedMillis,
    )
    val metadata = runCatching { (ApiJson.parseToJsonElement(body!!) as JsonObject)["error"] as JsonObject }.getOrNull().orEmpty()
    val info = ErrorResponseInfo(
        parsed.code, status, details, parsed.retryAt, parsed.resetsAt, parsed.until,
        parsed.presentation, parsed.retryable, timing, metadata = metadata, retryAfterSeconds = wait,
    )
    return when (parsed.code) {
        ErrorCodes.INVALID_TOKEN, ErrorCodes.TOKEN_REVOKED ->
            if (status == 401) AppError.Unauthorized(parsed.code, info) else parsed.toApi(status, info)
        ErrorCodes.CLIENT_VERSION_UNSUPPORTED -> AppError.UpdateRequired(info)
        ErrorCodes.MAINTENANCE -> AppError.Maintenance(parsed.until, wait?.takeIf { it <= Int.MAX_VALUE }?.toInt(), info)
        ErrorCodes.RATE_LIMITED -> AppError.RateLimited(parsed.retryAt, wait, info)
        ErrorCodes.REQUEST_IN_PROGRESS -> AppError.InProgress(wait?.takeIf { it <= Int.MAX_VALUE }?.toInt(), info)
        else -> if (status >= 500) AppError.Server(status, response = info) else parsed.toApi(status, info)
    }
}

private fun ApiError.toApi(status: Int, info: ErrorResponseInfo) = AppError.Api(
    code = code,
    httpStatus = status,
    details = details.orEmpty().map { AppError.FieldError(it.field, it.code, it.message) },
    retryAt = retryAt,
    resetsAt = resetsAt,
    response = info,
)

internal fun Response.toAppError(): AppError {
    val body = runCatching { peekBody(ERROR_BODY_LIMIT).string() }.getOrNull()
    return mapHttpError(code, body, header("Retry-After"), header("Date"), request.tag(ResponseTiming::class.java)?.receivedElapsedMillis)
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
