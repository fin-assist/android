package ru.finassist.pf.e2e

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class E2eControlReceiptTest {
    @get:Rule val compose = createComposeRule()

    @Test fun receiptStaysUntilExplicitAcknowledgementAfterTheObservationDwell() {
        val enabled = mutableStateOf(false)
        var acknowledged = 0
        compose.setContent { E2eControlReceipt(null, enabled.value) { acknowledged++ } }
        compose.onNodeWithTag(E2eControlActivity.TAG_APPLIED).assertIsDisplayed()
        compose.onNodeWithTag("e2e.config.continue").assertIsNotEnabled()
        compose.runOnIdle { enabled.value = true }
        compose.mainClock.advanceTimeBy(60_000)
        compose.onNodeWithTag(E2eControlActivity.TAG_APPLIED).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, acknowledged) }
        compose.onNodeWithTag("e2e.config.continue").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, acknowledged) }
    }

    @Test fun anUnmetCommandCannotBeAcknowledgedAsApplied() {
        compose.setContent { E2eControlReceipt("predicate was not met", true) { error("must not acknowledge") } }
        compose.onNodeWithTag(E2eControlActivity.TAG_UNMET).assertIsDisplayed()
        compose.onNodeWithText("pfe2e: predicate was not met").assertIsDisplayed()
        compose.onNodeWithTag(E2eControlActivity.TAG_APPLIED).assertDoesNotExist()
        compose.onNodeWithTag("e2e.config.continue").assertDoesNotExist()
    }
}
