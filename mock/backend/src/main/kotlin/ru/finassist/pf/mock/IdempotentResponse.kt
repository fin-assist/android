package ru.finassist.pf.mock

import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ErrorResponseInfo

/** Stores request-dependent responses for 24 hours; request gates belong before this call. */
internal suspend fun <T : Any> MockBackend.idempotentResponse(
    key: String,
    request: Any,
    block: suspend () -> T,
): T {
    val response = idempotent(key, request, expiresAt = now().plusHours(24)) {
        try {
            StoredResponse(value = block())
        } catch (error: AppError.Api) {
            if (error.httpStatus !in setOf(400, 413, 422) || error.code == ErrorCodes.CLIENT_VERSION_UNSUPPORTED) throw error
            StoredResponse<T>(error = error.response ?: ErrorResponseInfo(
                code = error.code,
                httpStatus = error.httpStatus,
                details = error.details.toList(),
                retryAt = error.retryAt,
                resetsAt = error.resetsAt,
            ))
        }
    }
    response.error?.let { error ->
        // Keep response data only; retaining a Throwable would keep the original coroutine's stack trace alive.
        throw AppError.Api(
            code = error.code,
            httpStatus = error.httpStatus,
            details = error.details,
            retryAt = error.retryAt,
            resetsAt = error.resetsAt,
            response = error,
        )
    }
    return checkNotNull(response.value)
}

private data class StoredResponse<T : Any>(val value: T? = null, val error: ErrorResponseInfo? = null)
