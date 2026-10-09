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
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasCategories
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.hasItem
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The upload screen against the real system picker contract — the one part Maestro skips (the e2e build swaps
 * the picker for a list of bundled files). Espresso-Intents answers `ACTION_OPEN_DOCUMENT` with a file Uri,
 * as DocumentsUI would, and the test checks that the file goes all the way to the import result.
 *
 * Runs on `mockDebug` under the test orchestrator with `clearPackageData`, so it always starts signed out.
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

        // The bundled fixture statement. Whether it adds operations depends on what mockDebug preloaded (a local
        // private statement, if present, goes first), so the test checks only that the import reaches its result.
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(fixtureCopy())))
        compose.onNodeWithTag("statements.upload.pick").performScrollTo().performClick()

        intended(allOf(hasAction(Intent.ACTION_OPEN_DOCUMENT), hasCategories(hasItem(Intent.CATEGORY_OPENABLE))))
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
