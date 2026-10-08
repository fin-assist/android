package ru.finassist.pf.feature.assistant.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.screenshot.DesignCheck

/** Artboards `AiConsent` / `AiConsentDark` (390×932) and `AiConsentError` (390×968): consent to share data with the assistant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiConsentDesignCheckTest {
    private val state = AiConsentUiState(
        document = ConsentDocument(
            ConsentType.ASSISTANT, version = "1", title = "Согласие на передачу данных помощнику",
            url = "https://example.ru/legal/assistant-consent/1",
        ),
    )

    private fun capture(name: String, state: AiConsentUiState = this.state, dark: Boolean = false, heightDp: Int = 932) =
        DesignCheck.capture(name, dark = dark, heightDp = heightDp) {
            AiConsentContent(state, onBack = {}, onAccepted = {}, onOpenDocument = {}, onGrant = {})
        }

    @Test fun aiConsent() = capture("AiConsent")

    @Test fun aiConsentDark() = capture("AiConsentDark", dark = true)

    /** «Продолжить» without the checkbox. */
    @Test fun aiConsentError() = capture(
        "AiConsentError",
        state.copy(error = "Отметьте согласие, чтобы задавать вопросы помощнику"),
        heightDp = 968,
    )
}
