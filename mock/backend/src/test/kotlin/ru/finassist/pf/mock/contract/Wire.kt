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
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.UserMessage

/**
 * The HTTP side the in-process fake backend does not have, built for the contract tests only (FIN-123): what a
 * server would put on the wire for the result of a mock call.
 *
 * - A result is the operation's 2xx status with the body serialized by [WireJson].
 * - An [AppError] is the error the HTTP client maps back to it (`core/network`, `ErrorMapping.kt`): [AppError.Api]
 *   carries its status and code; [AppError.Unauthorized] is 401, [AppError.RateLimited] 429 `RATE_LIMITED` with `Retry-After`,
 *   [AppError.InProgress] 409 `REQUEST_IN_PROGRESS` with `Retry-After`, [AppError.UpdateRequired] 400
 *   `CLIENT_VERSION_UNSUPPORTED`, [AppError.Maintenance] 503 `MAINTENANCE` with `error.until` and `Retry-After` (FIN-22),
 *   [AppError.Server] its status with `INTERNAL_ERROR`. `message` is the mock's text or, without one, the code.
 * - [AppError.Offline] and [AppError.Unknown] are not server responses: the test fails.
 */
internal object Wire {
    /**
     * [ApiJson] — the configuration the HTTP client reads responses with — writing every field, as a server does:
     * `encodeDefaults` and `explicitNulls` are off in [ApiJson] because it only ever writes requests, where a default
     * need not be sent and `null` is never sent (api.md, «Нотация»). A server writes an empty field as `null` (the
     * examples of api.md, the expected responses of `fixtures/calc`), so `null` is written here, and a field that is
     * `null` where the schema is not `nullable` fails the check.
     */
    val WireJson: Json = Json(ApiJson) {
        encodeDefaults = true
        explicitNulls = true
    }

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
    fun errorEvent(code: String, message: String, presentation: ErrorPresentation = ErrorPresentation()) = "error" to buildJsonObject {
        put("code", code)
        put("message", message)
        (presentation.userMessage as? UserMessage.Safe)?.let { put("user_message", it.text) }
        put("severity", presentation.severity.name.lowercase())
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
            is AppError.RateLimited -> Triple(
                429,
                ApiError(ErrorCodes.RATE_LIMITED, ErrorCodes.RATE_LIMITED, retryAt = e.retryAt),
                e.retryAfterSeconds?.let { mapOf("Retry-After" to it.toString()) }.orEmpty(),
            )
            is AppError.InProgress -> Triple(
                409,
                ApiError(ErrorCodes.REQUEST_IN_PROGRESS, ErrorCodes.REQUEST_IN_PROGRESS),
                e.retryAfterSeconds?.let { mapOf("Retry-After" to it.toString()) }.orEmpty(),
            )
            is AppError.UpdateRequired -> Triple(
                400,
                ApiError(ErrorCodes.CLIENT_VERSION_UNSUPPORTED, ErrorCodes.CLIENT_VERSION_UNSUPPORTED),
                emptyMap(),
            )
            is AppError.Maintenance -> Triple(
                503,
                ApiError(ErrorCodes.MAINTENANCE, ErrorCodes.MAINTENANCE, until = e.until),
                e.retryAfterSeconds?.let { mapOf("Retry-After" to it.toString()) }.orEmpty(),
            )
            is AppError.Server -> Triple(e.httpStatus, ApiError(ErrorCodes.INTERNAL_ERROR, ErrorCodes.INTERNAL_ERROR), emptyMap())
            is AppError.Offline, is AppError.Unknown -> throw AssertionError("${op.id} $path: ${e::class.simpleName} is not a server response", e)
        }
        val rendered = error.copy(
            userMessage = e.presentation.userMessage, severity = e.presentation.severity,
            retryable = e.requestRetryable,
        )
        val datedHeaders = e.response?.timing?.serverDate?.let {
            headers + ("Date" to java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(it.atZone(java.time.ZoneOffset.UTC)))
        } ?: headers
        return Exchange(op, path, status, WireJson.encodeToString(ErrorResponse.serializer(), ErrorResponse(rendered)), datedHeaders, code = error.code)
    }

    private const val STREAM_TIMEOUT_MS = 10_000L
}
