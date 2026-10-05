package ru.finassist.pf.core.network

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

/** One SSE event: `event:` name (defaults to `message`) and the raw `data:` payload. */
internal data class SseEvent(val type: String, val data: String)

/**
 * Opens an SSE stream as a cold flow. The server sends the full current state first and closes the stream
 * after a terminal event, so the flow completes normally on close. A dropped connection surfaces as
 * [AppError.Offline]; the caller decides whether to re-collect. Errors before the stream starts (401, 404)
 * are mapped from the HTTP response like any other call.
 */
internal fun OkHttpClient.sse(request: Request): Flow<SseEvent> = callbackFlow {
    val listener = object : EventSourceListener() {
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            trySendBlocking(SseEvent(type ?: "message", data))
        }

        override fun onClosed(eventSource: EventSource) {
            close()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            val error: AppError = when {
                response != null && !response.isSuccessful -> response.toAppError()
                t != null -> t.toNetworkError()
                else -> AppError.Offline()
            }
            close(error)
        }
    }
    val source = EventSources.createFactory(this@sse).newEventSource(request, listener)
    awaitClose { source.cancel() }
}
