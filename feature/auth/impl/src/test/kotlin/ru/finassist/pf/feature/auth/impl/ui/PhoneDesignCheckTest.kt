package ru.finassist.pf.feature.auth.impl.ui

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
    private fun capture(name: String, state: PhoneUiState = PhoneUiState(), dark: Boolean = false) =
        DesignCheck.capture(name, dark = dark) { PhoneContent(state, onInput = {}, onSubmit = {}) }

    @Test fun phone() = capture("Phone")

    @Test fun phoneDark() = capture("PhoneDark", dark = true)

    @Test fun phoneFormatError() = capture(
        "PhoneFormatError",
        PhoneUiState(input = TextFieldValue("91612345", TextRange(8)), error = "В номере не хватает цифр"),
    )

    @Test fun phoneLoggedOut() = capture("PhoneLoggedOut", PhoneUiState(notice = "Вы вышли из аккаунта. Войдите по номеру телефона"))

    @Test fun phoneDeleted() = DesignCheck.capture("PhoneDeleted") { DeletedScreen(onDone = {}) }
}
