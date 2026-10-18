package ru.finassist.pf.mock.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.ApiError
import ru.finassist.pf.core.api.model.ApiErrorDetail
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.ErrorResponse
import ru.finassist.pf.core.common.error.AppError

/**
 * The HTTP side the in-process fake backend does not have, built for the contract tests only (FIN-123): what a
 * server would put on the wire for the result of a mock call.
 *
 * - A result is the operation's 2xx status with the body serialized by [WireJson].
 * - An [AppError] is the error the HTTP client maps back to it (`core/network`, `ErrorMapping.kt`): [AppError.Api]
 *   carries its status and code; [AppError.Unauthorized] is 401, [AppError.RateLimited] 429 `RATE_LIMITED`,
 *   [AppError.InProgress] 409 `REQUEST_IN_PROGRESS` with `Retry-After`, [AppError.Server] its status with
 *   `INTERNAL_ERROR`. `message` is the mock's text or, without one, the code.
 * - [AppError.Offline] and [AppError.Unknown] are not server responses: the test fails.
 */
internal object Wire {
    /**
     * [ApiJson] — the configuration the HTTP client reads responses with — writing every field, as a server does:
     * `encodeDefaults` is off in [ApiJson] (it only ever writes requests, where a default need not be sent), and a
     * response field equal to its Kotlin default would otherwise be missing from the body. `null` is still omitted.
     */
    val WireJson: Json = Json(ApiJson) { encodeDefaults = true }

    class Exchange(
        val operation: Contract.Operation,
        /** The concrete path of the request, `{id}` filled in. */
        val path: String,
        val status: Int,
        val body: String?,
        val headers: Map<String, String> = emptyMap(),
        /** `event` → `data` of a stream, in order; empty for a plain response. */
        val events: List<Pair<String, String>> = emptyList(),
        /** `error.code` of an error response. */
        val code: String? = null,
    ) {
        override fun toString() = "${operation.method} $path → $status ${code ?: ""}".trim()
    }

    /** Runs [call] and renders its result ([serializer] null for 204) or its error. */
    suspend fun <T> exchange(operationId: String, path: String, serializer: KSerializer<T>?, call: suspend () -> T): Exchange {
        val op = Contract.operation(operationId)
        return try {
            val result = call()
            Exchange(op, path, op.success, serializer?.let { WireJson.encodeToString(it, result) })
        } catch (e: AppError) {
            error(op, path, e)
        }
    }

    /**
     * Collects a stream: an error before the first event is an HTTP error response (api.md, «SSE»); events are
     * rendered by [render] (`event` name and `data`).
     */
    suspend fun <E> stream(operationId: String, path: String, flow: Flow<E>, render: (E) -> Pair<String, String>): Exchange {
        val op = Contract.operation(operationId)
        return try {
            val events = withTimeout(STREAM_TIMEOUT_MS) { flow.toList() }
            Exchange(op, path, op.success, body = null, events = events.map(render))
        } catch (e: AppError) {
            error(op, path, e)
        }
    }

    fun <T> event(name: String, serializer: KSerializer<T>, data: T) = name to WireJson.encodeToString(serializer, data)

    /** The `{code, message}` payload of an `error` event (`StreamError`, `SseError`). */
    fun errorEvent(code: String, message: String) = "error" to buildJsonObject {
        put("code", code)
        put("message", message)
    }.toString()

    fun error(op: Contract.Operation, path: String, e: AppError): Exchange {
        val (status, error, headers) = when (e) {
            is AppError.Api -> Triple(
                e.httpStatus,
                ApiError(
                    code = e.code,
                    message = e.message ?: e.code,
                    details = e.details.takeIf { it.isNotEmpty() }?.map { ApiErrorDetail(it.field, it.code, it.message) },
                    retryAt = e.retryAt,
                    resetsAt = e.resetsAt,
                ),
                emptyMap(),
            )
            is AppError.Unauthorized -> Triple(401, ApiError(e.code, e.code), emptyMap())
            is AppError.RateLimited -> Triple(429, ApiError(ErrorCodes.RATE_LIMITED, ErrorCodes.RATE_LIMITED, retryAt = e.retryAt), emptyMap())
            is AppError.InProgress -> Triple(
                409,
                ApiError(ErrorCodes.REQUEST_IN_PROGRESS, ErrorCodes.REQUEST_IN_PROGRESS),
                e.retryAfterSeconds?.let { mapOf("Retry-After" to it.toString()) }.orEmpty(),
            )
            is AppError.Server -> Triple(e.httpStatus, ApiError(ErrorCodes.INTERNAL_ERROR, ErrorCodes.INTERNAL_ERROR), emptyMap())
            is AppError.Offline, is AppError.Unknown -> throw AssertionError("${op.id} $path: ${e::class.simpleName} is not a server response", e)
        }
        return Exchange(op, path, status, WireJson.encodeToString(ErrorResponse.serializer(), ErrorResponse(error)), headers, code = error.code)
    }

    private const val STREAM_TIMEOUT_MS = 10_000L
}
