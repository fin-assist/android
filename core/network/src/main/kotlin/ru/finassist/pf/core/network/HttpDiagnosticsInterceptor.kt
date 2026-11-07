package ru.finassist.pf.core.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Debug-only HTTP diagnostics: request data and exception messages never enter the log. */
internal class HttpDiagnosticsInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val method = when (request.method) {
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE" -> request.method
            else -> "OTHER"
        }
        return try {
            chain.proceed(request).also { Log.d(TAG, "$method ${it.code}") }
        } catch (error: IOException) {
            Log.d(TAG, "$method failed")
            throw error
        }
    }

    private companion object {
        const val TAG = "PfHttp"
    }
}
