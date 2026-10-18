package ru.finassist.pf.core.network

import okhttp3.Call
import okhttp3.EventListener
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.NonFatal
import ru.finassist.pf.core.tracking.Severity
import ru.finassist.pf.core.tracking.Slug
import java.io.IOException
import java.security.cert.CertificateException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Why the server's certificate was refused (FIN-121), for telemetry only: the user sees the same «no network» error
 * as for any other failed connection ([toNetworkError] maps every `IOException` to `AppError.Offline`).
 */
internal enum class TlsFailure(val code: Slug) {
    /** The chain is valid, but none of the pinned keys is in it (network security config `pin-set`). */
    PIN_MISMATCH(Slug.of("tls_pin_mismatch")),

    /** The chain is not trusted: a captive portal, an intercepting proxy, a broken chain on the server. */
    UNTRUSTED(Slug.of("tls_untrusted")),

    /** The certificate does not match the host (or an OkHttp `CertificatePinner` refused it). */
    PEER_UNVERIFIED(Slug.of("tls_peer_unverified")),
    ;

    companion object {
        /**
         * Android reports a pin failure as an `SSLHandshakeException` caused by a `CertificateException` with this
         * message (`NetworkSecurityTrustManager`). The message is only matched here and never sent anywhere.
         */
        private const val PIN_FAILURE_MESSAGE = "Pin verification failed"

        /** `null` for anything that is not a refused certificate (timeouts, resets, protocol errors). */
        fun of(error: IOException): TlsFailure? {
            if (error is SSLPeerUnverifiedException) return PEER_UNVERIFIED
            if (error !is SSLHandshakeException) return null
            val chain = generateSequence<Throwable>(error) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).toList()
            if (chain.none { it is CertificateException }) return null
            return if (chain.any { it.message?.contains(PIN_FAILURE_MESSAGE) == true }) PIN_MISMATCH else UNTRUSTED
        }

        private const val MAX_CAUSES = 8
    }
}

/**
 * Reports a refused server certificate to [CrashReporter] as a non-fatal `network.tls` with the [TlsFailure] code:
 * the fact only — no URL, host, headers or certificate data (the exception message is scrubbed by the provider).
 * Each code is reported once per process: a pin mismatch fails every request, and one report per launch is enough
 * to see a rotation going wrong.
 */
internal class TlsFailureReporter(private val crashReporter: CrashReporter) {
    private val reported = ConcurrentHashMap.newKeySet<TlsFailure>()

    fun onFailure(error: IOException) {
        val failure = TlsFailure.of(error) ?: return
        if (!reported.add(failure)) return
        crashReporter.report(NonFatal(SCREEN, PART, error, Severity.ERROR, failure.code))
    }

    /** One listener per call, all sharing this reporter. */
    fun eventListenerFactory(): EventListener.Factory = EventListener.Factory {
        object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) = onFailure(ioe)
        }
    }

    private companion object {
        val SCREEN = Slug.of("network")
        val PART = Slug.of("tls")
    }
}
