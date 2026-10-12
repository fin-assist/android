package ru.finassist.pf

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
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

/**
 * The upload screen against a picker result that comes back as an activity result — the one part Maestro skips
 * (it taps the e2e build's own list of bundled files). Espresso-Intents answers the picker intent with a file Uri,
 * as DocumentsUI would, and the test checks that the file goes all the way to the import result.
 *
 * Runs on the mock e2e build (the tested build type, with JaCoCo probes — docs/testing.md) under the test
 * orchestrator with `clearPackageData`, so it always starts signed out. In that build `PickStatementDocument`
 * opens [E2eDocumentPickerActivity] instead of `ACTION_OPEN_DOCUMENT`; the system intent itself is checked by
 * `PickStatementDocumentTest` (Robolectric) in `:feature:statements:impl`.
 */
@RunWith(AndroidJUnit4::class)
class StatementPickerTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() = Intents.init()

    @After
    fun tearDown() = Intents.release()

    @Test
    fun uploadsTheDocumentReturnedBySystemPicker() {
        signInWithExistingAccount()

        compose.onNodeWithTag("operations.feed.upload_new").performClick()
        compose.waitForTag("statements.upload.pick")

        // The bundled fixture statement, already loaded by the e2e build: the import reaches its result («nothing
        // new»), which is all the test checks.
        intending(hasComponent(E2eDocumentPickerActivity::class.java.name))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(fixtureCopy())))
        compose.onNodeWithTag("statements.upload.pick").performScrollTo().performClick()

        intended(hasComponent(E2eDocumentPickerActivity::class.java.name))
        compose.waitForTag("statements.result", timeoutMs = 30_000)
    }

    private fun signInWithExistingAccount() {
        compose.waitForTag("auth.phone.input")
        compose.onNodeWithTag("auth.phone.input").performTextInput(EXISTING_PHONE)
        compose.onNodeWithTag("auth.phone.submit").performClick()
        // The mock «receives the call» after a few seconds, then asks for a new passcode twice.
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

    /** Waits for [tag]; on timeout logs the semantics tree (logcat, tag `PfTest`) so the report shows what was on screen. */
    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForTag(tag: String, timeoutMs: Long = 10_000) {
        try {
            waitUntil(timeoutMs) { hasTag(tag) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            onRoot(useUnmergedTree = true).printToLog("PfTest")
            throw AssertionError("no node with tag $tag after $timeoutMs ms; semantics tree is in logcat (PfTest)", e)
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.hasTag(tag: String, waitMs: Long): Boolean =
        runCatching { waitUntil(waitMs) { hasTag(tag) } }.isSuccess

    private companion object {
        /** `MockConfig.existingPhone` without +7. */
        const val EXISTING_PHONE = "9161234567"
        const val PASSCODE = "1234"
    }
}
