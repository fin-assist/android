package ru.finassist.pf

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToLog
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.finassist.pf.e2e.E2eControlActivity

/** The Qase100 companion covers the Android browser handoff; Maestro checks the rest of the sheet. */
@RunWith(AndroidJUnit4::class)
class AssistantAboutDocumentTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() {
        Intents.init()
        intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
    }

    @After fun tearDown() = Intents.release()

    @Test fun opensCurrentDocumentOnlyAfterATapAndRecoversFromAnOfflineRead() {
        signInWithFixture()
        configure("consent_type=assistant&consent_publish=about-1&consent_read=offline&expect_consent_reads=0")

        compose.onNodeWithTag("ds.tab.assistant").performClick()
        compose.waitUntil(15_000) { hasTag("assistant.about.sheet") }
        configure("consent_type=assistant&expect_consent_reads=0")
        assertTrue(browserDestinations().isEmpty())

        openDocumentLink()
        compose.waitUntil(10_000) { hasTag("ds.error.error") }
        compose.onNodeWithText("Не удаётся подключиться к серверу", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertTrue(browserDestinations().isEmpty())

        configure("consent_type=assistant&consent_read=ready&expect_consent_reads=1")
        compose.onNodeWithText("Повторить").performScrollTo().performClick()
        val first = "https://example.ru/legal/assistant-consent/about-1"
        compose.waitUntil(10_000) { first in browserDestinations() }
        assertEquals(listOf(first), browserDestinations())

        configure("consent_type=assistant&consent_publish=about-2&expect_consent_reads=2")
        openDocumentLink()
        val latest = "https://example.ru/legal/assistant-consent/about-2"
        compose.waitUntil(10_000) { latest in browserDestinations() }
        assertEquals(listOf(first, latest), browserDestinations())
        configure("consent_type=assistant&expect_consent_reads=3")
    }

    private fun signInWithFixture() {
        configure("sign_in=existing", timeoutMs = 60_000)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = compose.activity
            // ActivityScenario matches lifecycle events against the original launcher intent.
            activity.startActivity(Intent.makeMainActivity(activity.componentName))
        }
        try {
            compose.waitUntil(40_000) { hasTag("operations.feed") || hasTag("applock.setup") || hasTag("applock.unlock") }
            when {
                hasTag("applock.setup") -> {
                    enterFixturePasscode()
                    compose.waitUntil(10_000) { hasTag("applock.setup.repeat") }
                    enterFixturePasscode()
                    compose.waitUntil(30_000) { hasTag("operations.feed") || hasTag("applock.biometric_offer") }
                    if (hasTag("applock.biometric_offer")) compose.onNodeWithTag("applock.biometric_offer.skip").performClick()
                }
                hasTag("applock.unlock") -> enterFixturePasscode()
            }
            compose.waitUntil(40_000) { hasTag("operations.feed") }
        } catch (failure: ComposeTimeoutException) {
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("PfTest") }
            throw failure
        }
    }

    private fun enterFixturePasscode() = "1234".forEach { digit -> compose.onNodeWithTag("ds.numpad.$digit").performClick() }

    private fun openDocumentLink() {
        compose.onNodeWithTag("assistant.about.document").performScrollTo().performClick()
    }

    private fun browserDestinations() = Intents.getIntents().filter { it.action == Intent.ACTION_VIEW }.map { it.dataString }

    private fun hasTag(tag: String) =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun configure(query: String, timeoutMs: Long = 15_000) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.startActivity(Intent(compose.activity, E2eControlActivity::class.java).setData(Uri.parse("pfe2e://config?$query")))
        }
        compose.waitUntil(timeoutMs) { hasTag("e2e.config.applied") || hasTag("e2e.config.unmet") }
        compose.onNodeWithTag("e2e.config.unmet").assertDoesNotExist()
        compose.waitUntil(10_000) {
            val button = compose.onAllNodesWithTag("e2e.config.continue")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).singleOrNull()
            button != null && !button.config.contains(SemanticsProperties.Disabled)
        }
        compose.onNodeWithTag("e2e.config.continue").performClick()
        compose.waitUntil(10_000) { !hasTag("e2e.config.applied") }
    }
}
