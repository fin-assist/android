package ru.finassist.pf.feature.auth.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.screenshot.DesignCheck
import ru.finassist.pf.feature.auth.impl.domain.PhoneFormat

/**
 * Artboards `Code`, `CodeDark`, `CodeExpired`, `CodeLocked`: confirmation by a call from +7 916 123-45-67 to
 * +7 495 000-00-00. FIN-166 states (artboards FIN-173): `CodeChecking`, `CodeCheckingDark` — the device deadline
 * passed, the number is gone until the stream answers; `CodeNoDeadline`, `CodeNoDeadlineDark` — waiting without a time
 * (no `expires_in` and no `Date`, or `pending` after the device deadline).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CodeDesignCheckTest {
    private val state = CallUiState(phoneDisplay = "+7 916 123-45-67", callbackNumber = "+7 495 000-00-00", waitUntil = "12:34")

    private fun capture(name: String, state: CallUiState = this.state, dark: Boolean = false) =
        DesignCheck.capture(name, dark = dark) {
            CallContent(state, onDial = {}, onRequestAgain = {}, onRetry = {}, onChangeNumber = {})
        }

    @Test fun code() = capture("Code")

    @Test fun codeDark() = capture("CodeDark", dark = true)

    @Test fun codeExpired() = capture("CodeExpired", state.copy(phase = CallPhase.EXPIRED))

    @Test fun codeChecking() = capture("CodeChecking", state.copy(phase = CallPhase.CHECKING, waitUntil = null))

    @Test fun codeCheckingDark() = capture("CodeCheckingDark", state.copy(phase = CallPhase.CHECKING, waitUntil = null), dark = true)

    @Test fun codeNoDeadline() = capture("CodeNoDeadline", state.copy(waitUntil = null))

    @Test fun codeNoDeadlineDark() = capture("CodeNoDeadlineDark", state.copy(waitUntil = null), dark = true)

    @Test fun codeLocked() = capture("CodeLocked", state.copy(phase = CallPhase.LOCKED, lockText = "Слишком много попыток. Позвонить можно будет через 10 минут, в 14:32"))
    // FIN-223 proposed artboard; snapshot execution is not authorized.
    @Test fun serverError() = capture("CodeServerError", state.copy(phase = CallPhase.ERROR,
        errorPresentation = ru.finassist.pf.core.common.error.ErrorPresentation(ru.finassist.pf.core.common.error.UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"))))
    @Test fun serverErrorDark() = capture("CodeServerErrorDark", state.copy(phase = CallPhase.ERROR,
        errorPresentation = ru.finassist.pf.core.common.error.ErrorPresentation(ru.finassist.pf.core.common.error.UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"))), dark = true)
}
