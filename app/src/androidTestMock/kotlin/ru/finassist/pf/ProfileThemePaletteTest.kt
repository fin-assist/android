package ru.finassist.pf

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual MainActivity preference-to-PfApp-to-palette path; the choice label alone cannot prove a theme change. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ProfileThemePaletteTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun darkChoiceChangesTheRenderedProfilePalette() {
        signIn()
        compose.onNodeWithTag("ds.tab.profile").performClick()
        waitFor("profile.phone")

        selectTheme("light", "Светлая")
        assertBackground(LIGHT_BACKGROUND)
        selectTheme("dark", "Тёмная")
        assertBackground(DARK_BACKGROUND)
    }

    private fun selectTheme(value: String, label: String) {
        compose.onNodeWithTag("profile.theme").performScrollTo().performClick()
        val option = "profile.theme.$value"
        waitFor(option)
        compose.onNodeWithTag(option).performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(option).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(label).assertIsDisplayed()
    }

    private fun assertBackground(expected: Int) {
        // Both samples are in the page's unpainted left gutter, outside cards, text and scrolling indicators.
        // captureToImage reads the rendered window pixels, including PfApp's ancestor background.
        compose.waitUntil(WAIT_MS) { profileBackground().let { it.first == expected && it.second == expected } }
        val (upper, lower) = profileBackground()
        assertEquals("upper profile gutter must use the selected palette", expected, upper)
        assertEquals("lower profile gutter must use the selected palette", expected, lower)
    }

    private fun profileBackground(): Pair<Int, Int> {
        val image = compose.onNodeWithTag(PROFILE_SCREEN).captureToImage().toPixelMap()
        return image[GUTTER_X, image.height / SAMPLE_DIVISOR].toArgb() to
            image[GUTTER_X, image.height * LOWER_SAMPLE_PART / SAMPLE_DIVISOR].toArgb()
    }

    private fun signIn() {
        waitFor("auth.phone.document", DOCUMENT_WAIT_MS)
        compose.onNodeWithTag("auth.phone.age").performScrollTo().performClick()
        compose.onNodeWithTag("auth.phone.consent").performScrollTo().performClick()
        compose.onNodeWithTag(PHONE_INPUT).performScrollTo().performTextInput("9161234567")
        compose.onNodeWithTag(PHONE_INPUT).performImeAction()
        waitFor("applock.setup", AUTH_WAIT_MS)
        enterPin()
        waitFor("applock.setup.repeat")
        enterPin()
        compose.waitUntil(WAIT_MS) {
            hasTag(BIOMETRIC_OFFER) || hasTag("operations.feed")
        }
        if (hasTag(BIOMETRIC_OFFER)) compose.onNodeWithTag("applock.biometric_offer.skip").performClick()
        waitFor("operations.feed", AUTH_WAIT_MS)
    }

    private fun enterPin() = "1234".forEach { compose.onNodeWithTag("ds.numpad.$it").performClick() }
    private fun hasTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String, timeout: Long = WAIT_MS) = compose.waitUntil(timeout) { hasTag(tag) }

    private companion object {
        const val WAIT_MS = 10_000L
        const val AUTH_WAIT_MS = 30_000L
        const val DOCUMENT_WAIT_MS = 60_000L
        const val PHONE_INPUT = "auth.phone.input"
        const val BIOMETRIC_OFFER = "applock.biometric_offer"
        const val PROFILE_SCREEN = "profile.screen"
        const val GUTTER_X = 2
        const val SAMPLE_DIVISOR = 3
        const val LOWER_SAMPLE_PART = 2
        // Independent expected values from the approved v7 light/dark page backgrounds, not production constants.
        const val LIGHT_BACKGROUND = -657672 // #FFF5F6F8
        const val DARK_BACKGROUND = -15855333 // #FF0E111B
    }
}
