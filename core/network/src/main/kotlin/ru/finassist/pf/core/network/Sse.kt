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
import java.io.IOException

/** One SSE event: `event:` name (defaults to `message`) and the raw `data:` payload. */
internal data class SseEvent(val type: String, val data: String)

/**
 * Opens an SSE stream as a cold flow. The server sends the full current state first and closes the stream
 * after a terminal event, so the flow completes normally on close — and also when it closes the stream without one
 * (its own failure, api.md «SSE»). A dropped connection surfaces as [AppError.Offline]. Errors before the stream
 * starts (401, 404, 429) are mapped from the HTTP response like any other call. Re-opening after a drop is not done
 * here: callers wrap the stream in `reopeningStream` (core/api), shared with the fake backend's streams.
 */
internal fun OkHttpClient.sse(request: Request, tlsFailures: TlsFailureReporter? = null): Flow<SseEvent> = callbackFlow {
    val listener = object : EventSourceListener() {
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            trySendBlocking(SseEvent(type ?: "message", data))
        }

        override fun onClosed(eventSource: EventSource) {
            close()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            // `RealEventSource` swaps the client's EventListener for NONE: a refused certificate is reported here.
            if (t is IOException) tlsFailures?.onFailure(t)
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
