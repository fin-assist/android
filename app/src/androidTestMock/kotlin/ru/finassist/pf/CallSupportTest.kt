package ru.finassist.pf

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.finassist.pf.e2e.E2eControlActivity

/** Espresso-Intents observes the Android mail handoff without sending a message. */
@RunWith(AndroidJUnit4::class)
class CallSupportTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() {
        Intents.init()
        Intents.intending(hasAction(Intent.ACTION_SENDTO))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
    }

    @After fun tearDown() = Intents.release()

    @Test fun opensSupportDraftWhileWaitingAndAfterExpiry() {
        waitForTag("auth.phone.document", 60_000)
        configureExpiringCall()
        waitForTag("auth.phone.document")
        compose.onNodeWithTag("auth.phone.age").performScrollTo().performClick()
        compose.onNodeWithTag("auth.phone.consent").performScrollTo().performClick()
        compose.onNodeWithTag("auth.phone.input").performScrollTo().performTextInput("9161234567")
        compose.onNodeWithTag("auth.phone.input").performImeAction()

        waitForTag("auth.call.dial")
        compose.onNodeWithTag("auth.call.dial").performScrollTo().assertIsDisplayed()
        assertDraftAfterTap(1)

        waitForTag("auth.call.retry", 40_000)
        compose.onNodeWithTag("auth.call.dial").assertDoesNotExist()
        assertDraftAfterTap(2)
        compose.onNodeWithTag("auth.call.change_number").performScrollTo().performClick()
        waitForTag("auth.phone.input")
    }

    private fun assertDraftAfterTap(count: Int) {
        compose.onNodeWithTag("auth.call.support").performScrollTo().performClick()
        compose.waitUntil(10_000) { drafts().size == count }
        val draft = drafts().last()
        assertEquals("mailto:", draft.dataString)
        assertArrayEquals(arrayOf("admin@plainfin.ru"), draft.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Не получилось подтвердить номер телефона", draft.getStringExtra(Intent.EXTRA_SUBJECT))
        assertFalse(draft.hasExtra(Intent.EXTRA_TEXT))
        assertFalse(draft.hasExtra(Intent.EXTRA_STREAM))
        assertNull(draft.clipData)
        compose.onNodeWithTag("auth.call.support").assertIsDisplayed()
    }

    private fun drafts() = Intents.getIntents().filter { it.action == Intent.ACTION_SENDTO }

    private fun configureExpiringCall() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = compose.activity
            val control = Intent(activity, E2eControlActivity::class.java)
                .setData(Uri.parse("pfe2e://config?call_delay_ms=600000&call_ttl_ms=20000"))
            activity.startActivity(control)
        }
        waitForTag("e2e.config.applied")
        compose.waitUntil(15_000) {
            val button = compose.onAllNodesWithTag("e2e.config.continue")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).singleOrNull()
            button != null && !button.config.contains(SemanticsProperties.Disabled)
        }
        compose.onNodeWithTag("e2e.config.continue").performClick()
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 15_000) = compose.waitUntil(timeoutMs) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }
}
