package ru.finassist.pf

import android.util.Log
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        // Reveal the stable row before querying its value; sheet closure precedes preference publication.
        compose.onNodeWithTag("profile.theme").performScrollTo()
        var lastCount = -1
        try {
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithText(label).fetchSemanticsNodes().size.also { lastCount = it } == 1
            }
        } catch (failure: ComposeTimeoutException) {
            // Only the theme row is logged: the rest of Profile contains personal information.
            val diagnostics = buildString {
                append("theme=$value label=$label lastMergedCount=$lastCount")
                append(" preference=" + runCatching {
                    runBlocking { withTimeout(1000) { compose.activity.preferences.theme.first() } }
                }.fold({ it }, { it.javaClass.simpleName }))
                for (unmerged in listOf(false, true)) {
                    append("\n" + runCatching {
                        val count = compose.onAllNodesWithText(label, useUnmergedTree = unmerged).fetchSemanticsNodes().size
                        val rows = compose.onAllNodesWithTag("profile.theme", useUnmergedTree = unmerged)
                        val nodes = rows.fetchSemanticsNodes()
                        "unmerged=$unmerged labelCount=$count themeRows=${nodes.size} " +
                            nodes.map { it.boundsInRoot }.toString() +
                            if (nodes.size == 1) "\n" + rows[0].printToString() else ""
                    }.getOrElse { "diagnostics unavailable: ${it.javaClass.simpleName}" })
                }
                append("\nprofileGutter=" + runCatching { profileBackground() }
                    .fold({ it.toString() }, { it.javaClass.simpleName }))
            }
            Log.e("PfTestTheme", diagnostics)
            throw AssertionError("Theme label count did not reach one within $WAIT_MS ms; $diagnostics", failure)
        }
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
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
