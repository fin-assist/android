package ru.finassist.pf.feature.auth.impl.ui

import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.screenshot.DesignCheck
import ru.finassist.pf.feature.auth.impl.domain.PhoneFormat

/** Artboards `Consent`, `ConsentDark`, `ConsentError`: personal data consent at registration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConsentDesignCheckTest {
    private val state = ConsentUiState(
        phoneDisplay = "+7 916 123-45-67",
        document = ConsentDocument(ConsentType.PERSONAL_DATA, version = "1", title = "Текст согласия", url = "https://example.ru/legal/pd-consent/1"),
    )

    private fun capture(name: String, state: ConsentUiState = this.state, dark: Boolean = false) =
        DesignCheck.capture(name, dark = dark) {
            ConsentContent(state, onBack = {}, onOpenDocument = {}, onAccepted = {}, onSubmit = {})
        }

    @Test fun consent() = capture("Consent")

    @Test fun consentDark() = capture("ConsentDark", dark = true)

    @Test fun consentError() = capture(
        "ConsentError",
        state.copy(error = "Без согласия мы не сможем создать аккаунт и хранить ваши данные"),
    )
    // FIN-223 fixture: proposed artboard; snapshots are not executed under the current instruction.
    @Test fun serverWarning() = capture("ConsentServerWarning", state.copy(formError = "Не получилось создать аккаунт", errorPresentation = ErrorPresentation(UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"), ErrorSeverity.WARNING)))

}
