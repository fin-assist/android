package ru.finassist.pf

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.finassist.pf.e2e.StageRun
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.finassist.pf.e2e.E2eDocumentPickerActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real stage upload of the document returned as an ActivityResult. The host creates a fresh synthetic
 * account before this test; no account/token/fixture state is shared with the mock backend.
 * The test-only picker component is intercepted, as in the original native case. ACTION_OPEN_DOCUMENT
 * intent construction remains covered by PickStatementDocumentTest; this does not claim a DocumentsUI run.
 */
@RunWith(AndroidJUnit4::class)
class StageStatementPickerTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityScenario<MainActivity>
    private lateinit var phone: String

    @Before
    fun setUp() {
        Intents.init()
        val args = InstrumentationRegistry.getArguments()
        val run = StageRun(requireNotNull(args.getString("stageRunId")), requireNotNull(args.getString("stagePhone"))).validated()
        phone = run.phone.removePrefix("+7")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        target.openFileOutput("stage-run.json", android.content.Context.MODE_PRIVATE).use {
            it.write(Json.encodeToString(run).toByteArray())
        }
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        if (::activity.isInitialized) activity.close()
        Intents.release()
    }

    @Test
    fun uploadsTheDocumentReturnedBySystemPicker() {
        signInWithExistingAccount()

        compose.onNodeWithTag("operations.feed.upload_new").performClick()
        compose.waitForTag("statements.upload.pick")

        // The bundled fixture statement, preloaded by the host for this run-owned account: the import reaches its result («nothing
        // new»), which is all the test checks.
        intending(hasComponent(E2eDocumentPickerActivity::class.java.name))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(fixtureCopy())))
        compose.onNodeWithTag("statements.upload.pick").performScrollTo().performClick()

        intended(hasComponent(E2eDocumentPickerActivity::class.java.name))
        compose.waitForTag("statements.result", timeoutMs = 30_000)
    }

    private fun signInWithExistingAccount() {
        compose.waitForTag("auth.phone.document")
        compose.onNodeWithTag("auth.phone.age").performScrollTo().performClick()
        compose.onNodeWithTag("auth.phone.consent").performScrollTo().performClick()
        compose.waitForTag("auth.phone.input")
        compose.onNodeWithTag("auth.phone.input").performScrollTo().performTextInput(phone)
        // Submit through the keyboard's «Готово» (the same `onSubmit` as «Продолжить»), not a click: typing focuses
        // the field, the keyboard slides in and the sign-in screen (`imePadding`) moves the button up with it. A click
        // computed during that animation lands where the button was and is dropped — FIN-63, run 38304375031: the
        // number was never sent. An IME action has no coordinates.
        compose.onNodeWithTag("auth.phone.input").performImeAction()
        // Step 1: the number is accepted — the call screen, or already the passcode setup if stage verified the
        // call» between two polls. A miss here is the submit, not the session switch below.
        compose.waitUntilTagged("auth.call", "applock.setup", timeoutMs = 10_000)
        // Step 2: the stage fake-phone verifies the call after a few seconds, then asks for a new passcode twice.
        // FIN-88 fixed the session subscription race. A regression must fail with diagnostics, not recover by
        // recreating the activity (the temporary FIN-63 workaround).
        compose.waitForTag("applock.setup", timeoutMs = 30_000)
        enterCode()
        compose.waitForTag("applock.setup.repeat")
        enterCode()
        if (compose.hasTag("applock.biometric_offer", waitMs = 2_000)) {
            compose.onNodeWithTag("applock.biometric_offer.skip").performClick()
        }
        compose.waitForTag("operations.feed.upload_new", timeoutMs = 30_000)
    }

    private fun enterCode() = PASSCODE.forEach { d -> compose.onNodeWithTag("ds.numpad.$d").performClick() }

    /** A file in the app's own cache: readable through `ContentResolver` like a picked document. */
    private fun fixtureCopy(): Uri {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(target.cacheDir, "picked/fixture.ofx").apply { parentFile?.mkdirs() }
        target.assets.open("statements/fixture.ofx").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file)
    }

    private fun SemanticsNodeInteractionsProvider.hasTag(tag: String) =
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /**
     * Waits for [tag]. On timeout the error carries what the screen showed (semantics tree in logcat, tag `PfTest`)
     * and how the main thread behaved during the whole wait — a screen that never changed can also mean a starved
     * main thread (FIN-63), which a probe taken after the fact would not see.
     */
    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForTag(tag: String, timeoutMs: Long = 10_000) =
        waitUntilTagged(tag, timeoutMs = timeoutMs)

    /** [waitForTag] for any of [tags]: passes as soon as one of them is on screen. */
    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilTagged(
        vararg tags: String,
        timeoutMs: Long = 10_000,
    ) {
        val watch = MainThreadWatch().also { it.start() }
        try {
            waitUntil(timeoutMs) { tags.any { hasTag(it) } }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val report = watch.stop()
            Log.w("PfTest", report)
            // After the probe: fetching semantics itself waits for the UI thread.
            onRoot(useUnmergedTree = true).printToLog("PfTest")
            val wanted = tags.joinToString(" / ")
            val message = "no node with tag $wanted after $timeoutMs ms; semantics tree is in logcat (PfTest); $report"
            throw AssertionError(message, e)
        } finally {
            watch.stop()
        }
    }

    /**
     * Pings the main looper every [PERIOD_MS] from its own thread and keeps the slowest reply; a reply later than
     * [STALL_MS] also captures the main thread's stack at that moment.
     */
    private class MainThreadWatch {
        private val handler = Handler(Looper.getMainLooper())
        @Volatile private var running = false
        @Volatile private var maxLatencyMs = 0L
        /** When the ping still waiting for a reply was sent; 0 between pings. */
        @Volatile private var pendingSince = 0L
        @Volatile private var stalledStack: String? = null
        private var thread: Thread? = null

        fun start() {
            running = true
            thread = Thread({
                while (running) {
                    val latch = CountDownLatch(1)
                    val sent = SystemClock.uptimeMillis()
                    pendingSince = sent
                    handler.post { latch.countDown() }
                    if (!latch.await(STALL_MS, TimeUnit.MILLISECONDS)) {
                        stalledStack = stalledStack ?: Looper.getMainLooper().thread.stackTrace.joinToString("\n    at ")
                        latch.await()
                    }
                    maxLatencyMs = maxOf(maxLatencyMs, SystemClock.uptimeMillis() - sent)
                    pendingSince = 0L
                    SystemClock.sleep(PERIOD_MS)
                }
            }, "pf-main-thread-watch").also { it.isDaemon = true; it.start() }
        }

        /** Idempotent; returns the report of the watched period. */
        fun stop(): String {
            running = false
            // A ping still unanswered is the slowest one: the main thread may not have come back at all.
            val pending = pendingSince.takeIf { it > 0L }?.let { SystemClock.uptimeMillis() - it } ?: 0L
            val slowest = if (pending > maxLatencyMs) "at least $pending ms (no reply yet)" else "$maxLatencyMs ms"
            val stack = stalledStack
            return "main thread: slowest reply $slowest" + if (stack != null) "; stack at the stall:\n    at $stack" else ""
        }

        private companion object {
            const val PERIOD_MS = 500L
            const val STALL_MS = 2_000L
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.hasTag(tag: String, waitMs: Long): Boolean =
        runCatching { waitUntil(waitMs) { hasTag(tag) } }.isSuccess

    private companion object {
        const val PASSCODE = "1234"
    }
}
