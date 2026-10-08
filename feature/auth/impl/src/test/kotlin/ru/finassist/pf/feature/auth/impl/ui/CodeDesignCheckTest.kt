package ru.finassist.pf.feature.auth.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.screenshot.DesignCheck
import ru.finassist.pf.feature.auth.impl.domain.PhoneFormat

/** Artboards `Code`, `CodeDark`, `CodeExpired`, `CodeLocked`: confirmation by a call from +7 916 123-45-67. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CodeDesignCheckTest {
    private val state = CallUiState(phoneDisplay = "+7 916 123-45-67", callbackNumber = "8 800 000-00-00")

    private fun capture(name: String, state: CallUiState = this.state, dark: Boolean = false) =
        DesignCheck.capture(name, dark = dark) {
            CallContent(state, onDial = {}, onRequestAgain = {}, onRetry = {}, onChangeNumber = {})
        }

    @Test fun code() = capture("Code")

    @Test fun codeDark() = capture("CodeDark", dark = true)

    @Test fun codeExpired() = capture("CodeExpired", state.copy(phase = CallPhase.EXPIRED))

    @Test fun codeLocked() = capture("CodeLocked", state.copy(phase = CallPhase.LOCKED, lockedUntil = "14:32"))
}
