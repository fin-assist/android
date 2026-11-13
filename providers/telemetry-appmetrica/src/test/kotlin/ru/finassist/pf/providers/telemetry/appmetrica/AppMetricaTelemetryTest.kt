package ru.finassist.pf.providers.telemetry.appmetrica

import android.content.Context
import io.appmetrica.analytics.AppMetricaConfig

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
import java.io.File
import java.nio.file.Files
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
        assertNull(config.userProfileID)
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
    fun `activation clears identity before app events and preserves other app files`() {
        val context = RuntimeEnvironment.getApplication()
        val sdk = RecordingGateway()
        val legacyReports = listOf(
            "appmetrica/analytics/db/component_main.db",
            "appmetrica/analytics/db/component_main.db-wal",
            "appmetrica/analytics/db/service_main.db",
            "appmetrica/analytics/db/client.db",
            "appmetrica/analytics/crashes/old.json",
            "appmetrica/analytics/native_crashes/old.dmp",
            "db_metrica_${context.packageName}",
            "db_metrica_${context.packageName}-journal",
            "metrica_data.db",
            "metrica_client_data.db",
        ).map { seedFile(context.noBackupFilesDir, it, "legacy account-linked data") }
        val installId = seedFile(context.noBackupFilesDir, "appmetrica/analytics/uuid.dat", "sdk-uuid")
        val appFile = seedFile(context.noBackupFilesDir, "app-state", "session")
        sdk.context["user_id"] = "previous-account"
        sdk.profile = "previous-account"
        sdk.beforeActivation = { assertTrue(legacyReports.none(File::exists)) }

        assertTrue(activateAppMetrica(context, UUID.randomUUID().toString(), sessions = true, sdk))
        val tracker = AppMetricaTracker(AppMetricaRuntime(enabled = true, sdk))
        tracker.track(Events.AUTH_VERIFIED, mapOf("user_id" to "current-account"))

        assertEquals(listOf("clear environment", "activate", "clear profile", "auth.verified"), sdk.calls)
        assertNull(sdk.profile)
        assertFalse("user_id" in sdk.context)
        assertEquals(listOf("auth.verified" to emptyMap()), sdk.events)
        assertEquals("sdk-uuid", installId.readText())
        assertEquals("session", appFile.readText())
    }

    @Test
    fun `clean install and later launches keep the new reporting queue`() {
        val context = RuntimeEnvironment.getApplication()
        val sdk = RecordingGateway()
        val key = UUID.randomUUID().toString()

        assertTrue(activateAppMetrica(context, key, sessions = false, sdk))
        val fresh = seedFile(context.noBackupFilesDir, "appmetrica/analytics/db/component_main.db", "technical event")
        assertTrue(activateAppMetrica(context, key, sessions = false, sdk))

        assertEquals("technical event", fresh.readText())
    }

    @Test
    fun `SDK remains off if migration cannot finish and retries on next activation`() {
        val context = RuntimeEnvironment.getApplication()
        val sdk = RecordingGateway()
        val key = UUID.randomUUID().toString()
        val marker = File(context.noBackupFilesDir, "pf_appmetrica_account_context_removed")
        assertTrue(marker.mkdir())

        assertFalse(activateAppMetrica(context, key, sessions = false, sdk))
        assertTrue(sdk.calls.isEmpty())
        assertTrue(marker.delete())
        assertTrue(activateAppMetrica(context, key, sessions = false, sdk))
    }

    @Test
    fun `migration never follows a reporting directory link`() {
        val context = RuntimeEnvironment.getApplication()
        val sdk = RecordingGateway()
        val root = context.noBackupFilesDir
        val unrelated = seedFile(root, "other-state/settings", "keep")
        val reports = File(root, "appmetrica/analytics/db")
        reports.parentFile?.mkdirs()
        Files.createSymbolicLink(reports.toPath(), unrelated.parentFile.toPath())

        assertTrue(activateAppMetrica(context, UUID.randomUUID().toString(), sessions = false, sdk))

        assertFalse(Files.isSymbolicLink(reports.toPath()))
        assertEquals("keep", unrelated.readText())
    }

    @Test
    fun `account lifecycle events do not gain account context`() {
        val sdk = RecordingGateway()
        val runtime = AppMetricaRuntime(enabled = true, sdk)
        val tracker = AppMetricaTracker(runtime)
        val reporter = AppMetricaCrashReporter(runtime)

        reporter.breadcrumb(Breadcrumb.SessionChanged(Breadcrumb.SessionCrumb.SIGNED_IN))
        tracker.track(Events.AUTH_LOGGED_OUT)
        reporter.breadcrumb(Breadcrumb.SessionChanged(Breadcrumb.SessionCrumb.SIGNED_OUT))
        tracker.track(Events.AUTH_VERIFIED)
        reporter.breadcrumb(Breadcrumb.SessionChanged(Breadcrumb.SessionCrumb.SIGNED_IN))
        tracker.track(Events.PROFILE_ACCOUNT_DELETED)
        reporter.breadcrumb(Breadcrumb.SessionChanged(Breadcrumb.SessionCrumb.SIGNED_OUT))

        assertEquals(
            listOf("auth.logged_out", "auth.verified", "profile.account.deleted").map { it to emptyMap() },
            sdk.events,
        )
        assertEquals(mapOf("breadcrumbs" to "session signed_in\nsession signed_out\nsession signed_in\nsession signed_out"), sdk.context)
        assertNull(sdk.profile)
    }

    @Test
    fun `product names and safe dimensions survive while account keys and raw server values are filtered`() {
        val sdk = RecordingGateway()
        val tracker = AppMetricaTracker(AppMetricaRuntime(enabled = true, sdk))
        val params = mapOf("reason" to "maintenance")
        val accountData = mapOf(
            "user_id" to "plainfin-user",
            "profile_id" to "hash-of-user",
            "phone" to "+79990000000",
            "request_id" to "request-from-api",
            "auth_session_epoch" to "account-session",
            "consent_id" to "consent-evidence",
        )

        tracker.track(Events.STATEMENTS_UPLOAD_INTERRUPTED, params + accountData)
        tracker.track(Events.AUTH_LOGGED_OUT, accountData)
        tracker.track(Events.STATEMENTS_UPLOAD_FAILED, mapOf("code" to "account_slug"))
        tracker.track(Events.ANALYTICS_BLOCK_EXPOSED, mapOf("block" to "account_slug", "exp" to "analytics_order:b"))
        tracker.track(Events.ANALYTICS_BLOCK_EXPOSED, mapOf("block" to "tiles", "exp" to "analytics_order:account_slug"))

        assertEquals(
            listOf(
                "statements.upload.interrupted" to params,
                "auth.logged_out" to emptyMap(),
                "statements.upload.failed" to emptyMap(),
                "analytics.block.exposed" to mapOf("block" to "unknown", "exp" to "analytics_order:b"),
                "analytics.block.exposed" to mapOf("block" to "tiles"),
            ),
            sdk.events,
        )
    }

    @Test
    fun `every supported product dimension passes its known technical value`() {
        val sdk = RecordingGateway()
        val tracker = AppMetricaTracker(AppMetricaRuntime(enabled = true, sdk))
        val knownParameters = listOf(
            Events.APPLOCK_UNLOCK_FAILED to ("attempt" to "3"),
            Events.STATEMENTS_UPLOAD_COMPLETED to ("new_count" to "12"),
            Events.STATEMENTS_UPLOAD_FAILED to ("code" to "WRONG_BANK"),
            Events.STATEMENTS_UPLOAD_INTERRUPTED to ("reason" to "update_required"),
            Events.OPERATIONS_SEARCH_SUBMITTED to ("count" to "0"),
            Events.OPERATIONS_CATEGORY_CHANGED to ("split" to "false"),
            Events.ANALYTICS_PERIOD_CHANGED to ("period" to "rolling_quarter"),
            Events.ANALYTICS_TRANSFERS_CHANGED to ("mode" to "without"),
            Events.ANALYTICS_TILE_OPENED to ("block" to "tiles"),
            Events.ANALYTICS_BLOCK_EXPOSED to ("block" to "balance"),
            Events.ASSISTANT_ANSWER_DONE to ("charged" to "true"),
            Events.ASSISTANT_ANSWER_FAILED to ("code" to "MODEL_ERROR"),
            Events.PROFILE_THEME_CHANGED to ("theme" to "system"),
        )
        for ((event, param) in knownParameters) {
            tracker.track(event, mapOf(param, "request_id" to "account-linked-request"))
        }
        tracker.track(Events.OPERATIONS_SEARCH_SUBMITTED, mapOf("count" to "+79990000000"))
        tracker.track(Events.STATEMENTS_UPLOAD_COMPLETED, mapOf("new_count" to "-1"))

        assertEquals(
            knownParameters.map { (event, param) -> event.name to mapOf(param) } +
                listOf("operations.search.submitted" to emptyMap(), "statements.upload.completed" to emptyMap()),
            sdk.events,
        )
    }

    @Test
    fun `diagnostics keep bounded context and send only scrubbed errors`() {
        val sdk = RecordingGateway()
        val reporter = AppMetricaCrashReporter(AppMetricaRuntime(enabled = true, sdk))
        reporter.setKey(CrashKey.RC_VERSION, CrashValue.of("abcd"))
        repeat(8) { reporter.breadcrumb(Breadcrumb.ScreenOpened(Slug.of("screen$it"))) }
        reporter.report(NonFatal(
            screen = Slug.of("analytics"),
            part = Slug.of("regular_payments"),
            cause = IllegalArgumentException("counterparty"),
            severity = Severity.ERROR,
            code = Slug.of("invalid_response"),
        ))

        assertEquals("abcd", sdk.context["rc_ver"])
        assertEquals((2..7).joinToString("\n") { "screen screen$it" }, sdk.context["breadcrumbs"])
        val (issueKey, severity, cause) = sdk.errors.single()
        assertEquals("analytics.regular_payments", issueKey)
        assertEquals("ERROR", severity)
        assertEquals("java.lang.IllegalArgumentException [invalid_response]", cause.message)

        reporter.setKey(CrashKey.RC_VERSION, null)
        assertFalse("user_id" in sdk.context)
        assertFalse("rc_ver" in sdk.context)
    }

    private fun seedFile(root: File, name: String, content: String): File =
        File(root, name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private class RecordingGateway : AppMetricaGateway {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val calls = mutableListOf<String>()
        var profile: String? = null
        var beforeActivation: () -> Unit = {}
        val errors = mutableListOf<Triple<String, String, Throwable>>()
        val context = mutableMapOf<String, String>()

        override fun activate(context: Context, config: AppMetricaConfig) {
            beforeActivation()
            calls += "activate"
        }

        override fun clearErrorEnvironment() {
            context.clear()
            calls += "clear environment"
        }

        override fun clearProfile() {
            profile = null
            calls += "clear profile"
        }

        override fun event(name: String, params: Map<String, String>) {
            calls += name
            events += name to params
        }

        override fun error(issueKey: String, severity: String, cause: Throwable) {
            errors += Triple(issueKey, severity, cause)
        }

        override fun context(key: String, value: String?) {
            if (value == null) context.remove(key) else context[key] = value
        }
    }
}
