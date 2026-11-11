package ru.finassist.pf.providers.telemetry.appmetrica

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.finassist.pf.core.tracking.Breadcrumb
import ru.finassist.pf.core.tracking.CrashKey
import ru.finassist.pf.core.tracking.CrashValue
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.NonFatal
import ru.finassist.pf.core.tracking.Severity
import ru.finassist.pf.core.tracking.Slug
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppMetricaTelemetryTest {
    @Test
    fun `mock can report crashes without automatic sessions`() {
        val config = appMetricaConfig(UUID.randomUUID().toString(), sessions = false)
        assertEquals(true, config.crashReporting)
        assertEquals(true, config.anrMonitoring)
        assertEquals(false, config.sessionsAutoTrackingEnabled)
        assertEquals(false, config.nativeCrashReporting)
        assertEquals(false, config.locationTracking)
        assertEquals(false, config.advIdentifiersTracking)
        assertEquals(false, config.revenueAutoTrackingEnabled)
        assertEquals(false, config.appOpenTrackingEnabled)
    }

    @Test
    fun `reporting builds can collect sessions and sanitize crash messages and causes`() {
        val config = appMetricaConfig(UUID.randomUUID().toString(), sessions = true)
        assertEquals(true, config.sessionsAutoTrackingEnabled)

        val original = IllegalStateException("statement title", IllegalArgumentException("counterparty"))
        original.addSuppressed(IllegalArgumentException("phone number"))
        val sent = assertNotNull(assertNotNull(config.crashTransformer).process(original))
        assertEquals(IllegalStateException::class.java.name, sent.message)
        assertEquals(IllegalArgumentException::class.java.name, sent.cause?.message)
        assertEquals(original.stackTrace.toList(), sent.stackTrace.toList())
        assertTrue(sent.suppressed.isEmpty())
    }

    @Test
    fun `unscrubbable crashes are dropped`() {
        val original = object : RuntimeException() {
            override val cause: Throwable? get() = error("scrubbing failed")
        }
        assertNull(scrubCrash(original))
    }

    @Test
    fun `disabled runtime makes no SDK calls and SDK failures do not escape`() {
        var called = false
        AppMetricaRuntime(enabled = false, RecordingGateway()).call { called = true }
        assertFalse(called)

        AppMetricaRuntime(enabled = true, RecordingGateway()).call {
            called = true
            error("SDK failed")
        }
        assertTrue(called)
    }

    @Test
    fun `the injected runtime does not enable SDK calls for blank keys`() {
        val context = RuntimeEnvironment.getApplication()
        for (key in listOf("", "  ")) {
            val runtime = AppMetricaRuntime(context, apiKey = key, sessions = true)
            var called = false
            runtime.call { called = true }
            assertFalse(called)
        }
    }

    @Test
    fun `Android receives a scrubbed exception or a safe fallback`() {
        var forwarded: Pair<Thread, Throwable>? = null
        val handler = ScrubbingExceptionHandler { thread, cause -> forwarded = thread to cause }
        val thread = Thread.currentThread()
        handler.uncaughtException(thread, IllegalArgumentException("statement title"))

        assertSame(thread, assertNotNull(forwarded).first)
        assertEquals("java.lang.IllegalArgumentException", assertNotNull(forwarded).second.message)

        handler.uncaughtException(thread, object : RuntimeException() {
            override val cause: Throwable? get() = error("scrubbing failed")
        })
        assertEquals("Uncaught exception could not be scrubbed", assertNotNull(forwarded).second.message)
    }

    @Test
    fun `install wraps the current Android handler once`() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var forwarded: Throwable? = null
        val system = Thread.UncaughtExceptionHandler { _, cause -> forwarded = cause }
        try {
            Thread.setDefaultUncaughtExceptionHandler(system)
            ScrubbingExceptionHandler.install()
            val installed = assertNotNull(Thread.getDefaultUncaughtExceptionHandler())
            ScrubbingExceptionHandler.install()

            assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())
            installed.uncaughtException(Thread.currentThread(), IllegalArgumentException("raw value"))
            assertEquals("java.lang.IllegalArgumentException", assertNotNull(forwarded).message)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `product event names and parameters are preserved and sign out clears the profile`() {
        val sdk = RecordingGateway()
        val tracker = AppMetricaTracker(AppMetricaRuntime(enabled = true, sdk))
        val params = mapOf("reason" to "maintenance")

        tracker.setUserId("opaque-user")
        tracker.track(Events.STATEMENTS_UPLOAD_INTERRUPTED, params)
        tracker.track(Events.AUTH_LOGGED_OUT)
        tracker.setUserId(null)

        assertEquals(listOf("opaque-user", null), sdk.profiles)
        assertEquals(
            listOf("statements.upload.interrupted" to params, "auth.logged_out" to emptyMap()),
            sdk.events,
        )
    }

    @Test
    fun `diagnostics keep bounded context and send only scrubbed errors`() {
        val sdk = RecordingGateway()
        val reporter = AppMetricaCrashReporter(AppMetricaRuntime(enabled = true, sdk))
        reporter.setUserId("opaque-user")
        reporter.setKey(CrashKey.RC_VERSION, CrashValue.of("abcd"))
        repeat(8) { reporter.breadcrumb(Breadcrumb.ScreenOpened(Slug.of("screen$it"))) }
        reporter.report(NonFatal(
            screen = Slug.of("analytics"),
            part = Slug.of("regular_payments"),
            cause = IllegalArgumentException("counterparty"),
            severity = Severity.ERROR,
            code = Slug.of("invalid_response"),
        ))

        assertEquals("opaque-user", sdk.context["user_id"])
        assertEquals("abcd", sdk.context["rc_ver"])
        assertEquals((2..7).joinToString("\n") { "screen screen$it" }, sdk.context["breadcrumbs"])
        val (issueKey, severity, cause) = sdk.errors.single()
        assertEquals("analytics.regular_payments", issueKey)
        assertEquals("ERROR", severity)
        assertEquals("java.lang.IllegalArgumentException [invalid_response]", cause.message)

        reporter.setUserId(null)
        reporter.setKey(CrashKey.RC_VERSION, null)
        assertFalse("user_id" in sdk.context)
        assertFalse("rc_ver" in sdk.context)
    }

    private class RecordingGateway : AppMetricaGateway {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val profiles = mutableListOf<String?>()
        val errors = mutableListOf<Triple<String, String, Throwable>>()
        val context = mutableMapOf<String, String>()

        override fun event(name: String, params: Map<String, String>) {
            events += name to params
        }

        override fun profile(userId: String?) {
            profiles += userId
        }

        override fun error(issueKey: String, severity: String, cause: Throwable) {
            errors += Triple(issueKey, severity, cause)
        }

        override fun context(key: String, value: String?) {
            if (value == null) context.remove(key) else context[key] = value
        }
    }
}
