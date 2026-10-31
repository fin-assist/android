package ru.finassist.pf.feature.assistant.impl.ui

import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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

    /** Short viewport and larger text keep the checkbox/error and fixed action gap in scrollable content. */
    @Test fun shortViewportLargeText() = DesignCheck.capture("AiConsentLargeText", heightDp = 640, fullHeight = false) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
            AiConsentContent(state.copy(error = CONSENT_REQUIRED, formError = "Не получилось сохранить согласие"),
                onBack = {}, onAccepted = {}, onOpenDocument = {}, onGrant = {})
        }
    }

    /** «Продолжить» without the checkbox. */
    @Test fun aiConsentError() = capture(
        "AiConsentError",
        state.copy(error = CONSENT_REQUIRED),
        heightDp = 968,
    )
    // FIN-223 fixture: proposed artboard; snapshots are not executed under the current instruction.
    @Test fun serverWarning() = capture("AiConsentServerWarning", state.copy(formError = "Не получилось сохранить согласие", errorPresentation = ErrorPresentation(UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"), ErrorSeverity.WARNING)))

    @Test fun documentRecovery() = capture("AiConsentDocumentRecovery", state.copy(documentRefreshRequired = true, documentRefreshReady = true,
        retryReady = false, requestRetryable = false, formError = "Не получилось загрузить документ согласия"))
    @Test fun documentRecoveryDark() = capture("AiConsentDocumentRecoveryDark", state.copy(documentRefreshRequired = true, documentRefreshReady = true,
        retryReady = false, requestRetryable = false, formError = "Не получилось загрузить документ согласия"), dark = true)

}
