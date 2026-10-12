package ru.finassist.pf.providers.crash.tracer

import ru.finassist.pf.core.tracking.Breadcrumb
import ru.finassist.pf.core.tracking.CrashKey
import ru.finassist.pf.core.tracking.CrashValue
import ru.finassist.pf.core.tracking.NonFatal
import ru.finassist.pf.core.tracking.ScrubbedThrowable
import ru.finassist.pf.core.tracking.Severity
import ru.finassist.pf.core.tracking.Slug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.ok.tracer.Severity as TracerSeverity

class TracerCrashReporterTest {
    private class FakeTracer(private val failing: Boolean = false) : TracerGateway {
        val calls = mutableListOf<String>()
        var reported: Throwable? = null
        var severity: TracerSeverity? = null

        private fun record(call: String) {
            calls += call
            if (failing) error("sdk failure")
        }

        override fun report(severity: TracerSeverity, throwable: Throwable, issueKey: String) {
            this.severity = severity
            reported = throwable
            record("report $issueKey")
        }

        override fun log(message: String) = record("log $message")
        override fun setUserId(userId: String?) = record("user $userId")
        override fun setKey(key: String, value: String?) = record("key $key=$value")
    }

    private val tracer = FakeTracer()
    private val reporter = TracerCrashReporter(enabled = true, tracer = tracer)

    private fun nonFatal(cause: Throwable, severity: Severity = Severity.WARNING, code: String? = "server_503") =
        NonFatal(Slug.of("analytics"), Slug.of("regular_payments"), cause, severity, code?.let(Slug::of))

    @Test fun `user id is set after sign-in and cleared after sign-out`() {
        reporter.setUserId("01a107db")
        reporter.setUserId(null)
        assertEquals(listOf("user 01a107db", "user null"), tracer.calls)
    }

    @Test fun `context keys are set and removed`() {
        reporter.setKey(CrashKey.RC_VERSION, CrashValue.of("0a1b2c3d"))
        reporter.setKey(CrashKey.experiment(Slug.of("new_tiles")), CrashValue.of("b"))
        reporter.setKey(CrashKey.RC_VERSION, null)
        assertEquals(listOf("key rc_ver=0a1b2c3d", "key exp_new_tiles=b", "key rc_ver=null"), tracer.calls)
    }

    @Test fun `breadcrumbs go to the log buffer`() {
        reporter.breadcrumb(Breadcrumb.ScreenOpened(Slug.of("analytics.home")))
        assertEquals(listOf("log screen analytics.home"), tracer.calls)
    }

    @Test fun `a non-fatal is grouped by its issue key`() {
        reporter.report(nonFatal(IllegalStateException("boom")))
        assertEquals(listOf("report analytics.regular_payments"), tracer.calls)
    }

    @Test fun `severities map one to one, a degradation is never fatal by default`() {
        reporter.report(nonFatal(RuntimeException()))
        assertEquals(TracerSeverity.WARNING, tracer.severity)
        reporter.report(nonFatal(RuntimeException(), Severity.ERROR))
        assertEquals(TracerSeverity.ERROR, tracer.severity)
        reporter.report(nonFatal(RuntimeException(), Severity.FATAL))
        assertEquals(TracerSeverity.FATAL, tracer.severity)
    }

    @Test fun `the exception message stays on the device`() {
        val cause = IllegalArgumentException("cannot parse amount of «Пятёрочка» 2 340 ₽ for +79161234567")
        reporter.report(nonFatal(cause))

        val sent = assertIs<ScrubbedThrowable>(tracer.reported)
        assertEquals("java.lang.IllegalArgumentException [server_503]", sent.message)
        assertEquals("java.lang.IllegalArgumentException", sent.type)
        assertTrue(sent.stackTrace.contentEquals(cause.stackTrace))
    }

    @Test fun `a build without a token never touches the sdk`() {
        val off = TracerCrashReporter(enabled = false, tracer = tracer)
        off.setUserId("01a107db")
        off.setKey(CrashKey.RC_VERSION, CrashValue.of("defaults"))
        off.breadcrumb(Breadcrumb.ScreenOpened(Slug.of("a")))
        off.report(nonFatal(RuntimeException()))
        assertEquals(emptyList(), tracer.calls)
    }

    @Test fun `an sdk failure does not reach the caller`() {
        val broken = FakeTracer(failing = true)
        val reporter = TracerCrashReporter(enabled = true, tracer = broken)
        reporter.setUserId("01a107db")
        reporter.setKey(CrashKey.RC_VERSION, null)
        reporter.breadcrumb(Breadcrumb.ScreenOpened(Slug.of("a")))
        reporter.report(nonFatal(RuntimeException()))
        assertEquals(4, broken.calls.size)
    }
}
