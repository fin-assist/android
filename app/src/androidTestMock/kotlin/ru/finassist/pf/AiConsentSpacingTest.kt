package ru.finassist.pf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.assistant.impl.ui.AiConsentContent
import ru.finassist.pf.feature.assistant.impl.ui.AiConsentUiState

/** Maestro cannot assert measured Compose geometry; this runs in the native mockE2e suite. */
@RunWith(AndroidJUnit4::class)
class AiConsentSpacingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun keepsTwentyFourDpAfterConsentContentWhenScrolledAtLargeFont() {
        val formError = mutableStateOf<String?>(null)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                PfTheme {
                    Box(Modifier.fillMaxWidth().height(640.dp)) {
                        AiConsentContent(
                            AiConsentUiState(error = "Без согласия помощник не сможет ответить", formError = formError.value),
                            onBack = {}, onAccepted = {}, onOpenDocument = {}, onGrant = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("assistant.consent.submit").assertIsNotDisplayed()
        assertMinimumGap()
        compose.runOnIdle { formError.value = "Не получилось сохранить согласие" }
        assertMinimumGap()
    }

    private fun assertMinimumGap() {
        val button = compose.onNodeWithTag("assistant.consent.submit")
        button.performScrollTo().assertIsDisplayed()
        val body = compose.onNodeWithTag("assistant.consent.body")
        body.assertIsDisplayed()
        val gap = button.getUnclippedBoundsInRoot().top - body.getUnclippedBoundsInRoot().bottom
        assertTrue("Expected at least 24 dp after the complete consent/error block, got $gap", gap >= 24.dp)
    }
}
