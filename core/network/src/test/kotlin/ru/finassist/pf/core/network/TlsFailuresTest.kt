package ru.finassist.pf.core.network

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.tracking.Severity
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TlsFailuresTest {
    private val server = MockWebServer()
    private lateinit var stack: NetworkStack

    @Before
    fun setUp() {
        server.start()
        stack = NetworkStack(server)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a pin failure of the platform trust manager is a pin mismatch`() {
        assertEquals(TlsFailure.PIN_MISMATCH, TlsFailure.of(pinFailure()))
    }

    @Test
    fun `an untrusted chain is told apart from a pin mismatch`() {
        val untrusted = SSLHandshakeException("handshake").apply {
            initCause(CertificateException("java.security.cert.CertPathValidatorException: Trust anchor not found"))
        }
        assertEquals(TlsFailure.UNTRUSTED, TlsFailure.of(untrusted))
        val expired = SSLHandshakeException("handshake").apply { initCause(CertificateExpiredException("expired")) }
        assertEquals(TlsFailure.UNTRUSTED, TlsFailure.of(expired))
    }

    @Test
    fun `a certificate for another host is peer unverified`() {
        assertEquals(TlsFailure.PEER_UNVERIFIED, TlsFailure.of(SSLPeerUnverifiedException("Hostname api.example.ru not verified")))
    }

    @Test
    fun `failures that are not about the certificate are not tls failures`() {
        assertNull(TlsFailure.of(SSLHandshakeException("Connection reset by peer")))
        assertNull(TlsFailure.of(SSLException("Read error")))
        assertNull(TlsFailure.of(SocketTimeoutException("timeout")))
        assertNull(TlsFailure.of(IOException("unexpected end of stream")))
    }

    @Test
    fun `a refused certificate is the usual offline error for the user`() {
        assertIs<AppError.Offline>(pinFailure().toNetworkError())
    }

    @Test
    fun `a refused certificate is reported once with its code and nothing about the request`() {
        val client = stack.client.newBuilder().addInterceptor(Interceptor { throw pinFailure() }).build()
        val request = Request.Builder().url(server.url("/v1/operations?query=+79001234567")).build()

        repeat(2) { assertFailsWith<SSLHandshakeException> { client.newCall(request).execute() } }

        val report = stack.crashReporter.reports.single()
        assertEquals("network.tls", report.issueKey)
        assertEquals("tls_pin_mismatch", report.code?.value)
        assertEquals(Severity.ERROR, report.severity)
        assertIs<SSLHandshakeException>(report.cause)
    }

    @Test
    fun `each kind of refusal is reported once`() {
        val reporter = TlsFailureReporter(stack.crashReporter)
        reporter.onFailure(pinFailure())
        reporter.onFailure(SSLPeerUnverifiedException("x"))
        reporter.onFailure(SSLPeerUnverifiedException("y"))
        assertEquals(listOf("tls_pin_mismatch", "tls_peer_unverified"), stack.crashReporter.reports.map { it.code?.value })
    }

    @Test
    fun `ordinary network failures and successful calls are not reported`() {
        server.enqueue(MockResponse().setBody("{}"))
        stack.client.newCall(Request.Builder().url(server.url("/v1/profile")).build()).execute().close()
        val offline = stack.client.newBuilder().addInterceptor(Interceptor { throw SocketTimeoutException("timeout") }).build()
        assertFailsWith<SocketTimeoutException> { offline.newCall(Request.Builder().url(server.url("/")).build()).execute() }
        assertTrue(stack.crashReporter.reports.isEmpty())
    }

    /** What Android's network security config throws when no pinned key is in the chain. */
    private fun pinFailure() = SSLHandshakeException("java.security.cert.CertificateException: Pin verification failed").apply {
        initCause(CertificateException("Pin verification failed"))
    }
}
