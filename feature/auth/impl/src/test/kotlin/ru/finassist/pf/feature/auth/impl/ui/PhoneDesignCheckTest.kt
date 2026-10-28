package ru.finassist.pf.feature.auth.impl.ui

import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.screenshot.DesignCheck

/** Artboards `Phone`, `PhoneDark`, `PhoneFormatError`, `PhoneLoggedOut` and `PhoneDeleted` («Аккаунт удалён»). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneDesignCheckTest {
    private val document = ru.finassist.pf.core.api.model.ConsentDocument(
        ru.finassist.pf.core.api.model.ConsentType.PERSONAL_DATA, "2026-09-28", "Согласие на обработку персональных данных", "https://example.test/pd")
    private fun capture(name: String, state: PhoneUiState = PhoneUiState(document = document), dark: Boolean = false) =
        DesignCheck.capture(name, dark = dark) { PhoneContent(state, onInput = {}, onSubmit = {}) }

    @Test fun phone() = capture("Phone")

    @Test fun phoneDark() = capture("PhoneDark", dark = true)

    @Test fun phoneFormatError() = capture(
        "PhoneFormatError",
        PhoneUiState(document = document, ageConfirmed = true, pdConsentAccepted = true, input = TextFieldValue("91612345", TextRange(8)), error = "В номере не хватает цифр"),
    )

    @Test fun phoneLoggedOut() = capture("PhoneLoggedOut", PhoneUiState(notice = "Вы вышли из аккаунта. Войдите по номеру телефона"))

    @Test fun phoneDeleted() = DesignCheck.capture("PhoneDeleted") { DeletedScreen(onDone = {}) }
    // FIN-223 fixture: proposed artboard; snapshots are not executed under the current instruction.
    @Test fun serverWarning() = capture("PhoneServerWarning", PhoneUiState(error = "Не получилось отправить номер", errorPresentation = ErrorPresentation(UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"), ErrorSeverity.WARNING)))
    @Test fun serverWarningDark() = capture("PhoneServerWarningDark", PhoneUiState(error = "Не получилось отправить номер", errorPresentation = ErrorPresentation(UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"), ErrorSeverity.WARNING)), dark = true)

    // FIN-365 fixtures are authored without executing UI or DesignCheck.
    @Test fun ageOnly() = capture("PhoneAgeOnly", PhoneUiState(document = document, ageConfirmed = true))
    @Test fun consentOnly() = capture("PhoneConsentOnly", PhoneUiState(document = document, pdConsentAccepted = true))
    @Test fun documentUnavailable() = capture("PhoneDocumentUnavailable", PhoneUiState(documentError = "Не получилось загрузить документ согласия", documentRefreshRequired = true))
    @Test fun documentUnavailableDark() = capture("PhoneDocumentUnavailableDark", PhoneUiState(documentError = "Не получилось загрузить документ согласия", documentRefreshRequired = true), dark = true)
    @Test fun restartAfterRefusal() = capture("PhoneRestartAfterRefusal", PhoneUiState(document = document, restartAvailable = true, retryReady = false, error = "Начните вход заново"))
    @Test fun newIntentWait() = capture("PhoneNewIntentWait", PhoneUiState(document = document, ageConfirmed = true, pdConsentAccepted = true, newIntentRequired = true, retryReady = false, requestRetryable = false, restartAvailable = true, error = "Слишком много попыток"))
    @Test fun newIntentWaitAfterEdit() = capture("PhoneNewIntentWaitAfterEdit", PhoneUiState(document = document,
        ageConfirmed = true, pdConsentAccepted = true, input = TextFieldValue("9031234567"), newIntentRequired = true,
        retryReady = false, requestRetryable = false, restartAvailable = true, error = "Слишком много попыток. Повторите в 14:05"))
    @Test fun newIntentWaitAfterRestart() = capture("PhoneNewIntentWaitAfterRestart", PhoneUiState(document = document,
        newIntentRequired = true, retryReady = false, requestRetryable = false, restartAvailable = true, error = "Слишком много попыток. Повторите в 14:05"))

    @Test fun documentRefused() = capture("PhoneDocumentRefused", PhoneUiState(
        documentError = "Не получилось загрузить документ согласия", documentRefreshRequired = true,
        documentRefreshReady = false, restartAvailable = true))
    @Test fun documentRefusedDark() = capture("PhoneDocumentRefusedDark", PhoneUiState(
        documentError = "Не получилось загрузить документ согласия", documentRefreshRequired = true,
        documentRefreshReady = false, restartAvailable = true), dark = true)

    @Test fun documentRefusedWait() = capture("PhoneDocumentRefusedWait", PhoneUiState(
        documentError = "Не получилось загрузить документ согласия", documentRefreshRequired = true,
        documentRefreshReady = false, restartAvailable = false))
    @Test fun documentRefusedWaitDark() = capture("PhoneDocumentRefusedWaitDark", PhoneUiState(
        documentError = "Не получилось загрузить документ согласия", documentRefreshRequired = true,
        documentRefreshReady = false, restartAvailable = false), dark = true)

}
