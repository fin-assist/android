package ru.finassist.pf.core.network.sse

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.network.client.ApiErrorMapper
import ru.finassist.pf.core.network.client.AuthHeaderInterceptor
import ru.finassist.pf.core.network.client.PfJson
import ru.finassist.pf.core.network.dto.StreamErrorDto
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** One SSE event: `event:` name and raw `data:` payload. */
data class SseEvent(val name: String, val data: String)

/**
 * Minimal SSE reader on OkHttp's `okhttp-sse`. The flow completes when the server closes the stream after a
 * terminal event and fails with [AppError] on transport errors or non-2xx before the stream starts.
 * Reconnection is the caller's business: the contract guarantees the first event is the full current state,
 * so re-opening the stream never loses anything (api.md «SSE»).
 *
 * The contract's `error` event is surfaced as a normal [SseEvent] — its meaning differs per endpoint
 * (import: PROCESSING_FAILED/CANCELLED; assistant: MODEL_ERROR/CANNOT_ANSWER with `remaining_limit`).
 */
@Singleton
class SseClient @Inject constructor(
    private val client: OkHttpClient,
    @Named("apiBaseUrl") private val baseUrl: String,
) {
    fun stream(path: String, headers: Map<String, String> = emptyMap(), noAuth: Boolean = false): Flow<SseEvent> = callbackFlow {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/" + path.trimStart('/'))
            .header("Accept", "text/event-stream")
            .apply {
                headers.forEach { (k, v) -> header(k, v) }
                if (noAuth) header(AuthHeaderInterceptor.NO_AUTH, "1")
            }
            .build()

        val source: EventSource = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                trySendBlocking(SseEvent(type ?: "message", data))
            }

            override fun onClosed(eventSource: EventSource) { close() }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val error = when {
                    response != null && !response.isSuccessful ->
                        ApiErrorMapper.fromHttp(response.code, runCatching { response.body.string() }.getOrNull(), response.header("Retry-After"))
                    t != null -> ApiErrorMapper.map(t)
                    else -> AppError.Offline()
                }
                close(error)
            }
        })
        awaitClose { source.cancel() }
    }
}

/** Decodes the generic `error` event body; tolerant to missing fields. */
fun SseEvent.asStreamError(): StreamErrorDto =
    runCatching { PfJson.decodeFromString(StreamErrorDto.serializer(), data) }.getOrElse { StreamErrorDto("INTERNAL_ERROR", data) }
