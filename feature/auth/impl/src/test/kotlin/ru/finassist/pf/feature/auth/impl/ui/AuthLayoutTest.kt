package ru.finassist.pf.feature.auth.impl.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import ru.finassist.pf.core.designsystem.components.PfMark
import ru.finassist.pf.core.designsystem.theme.PfTheme
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h500dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `phone header and retry are reachable in the space left by the keyboard`() {
        val message = "Не получилось отправить номер. Попробуйте ещё раз"
        val state = PhoneUiState(
            input = TextFieldValue("9161234567"),
            ageConfirmed = true,
            pdConsentAccepted = true,
            document = ConsentDocument(ConsentType.PERSONAL_DATA, "2026-09-28", "Согласие на обработку персональных данных", "https://example.test/pd"),
            error = message,
            errorPresentation = ErrorPresentation(UserMessage.from(message), ErrorSeverity.WARNING),
            newIntentRequired = true,
        )
        compose.setContent {
            PfTheme {
                Box(Modifier.height(350.dp)) {
                    PhoneContent(state, onInput = {}, onSubmit = {})
                }
            }
        }

        val title = compose.onNodeWithText("Понятные финансы")
        title.assertIsDisplayed()
        compose.onNodeWithTag(AuthTags.PHONE_SUBMIT).performScrollTo().assertIsDisplayed()
        title.performScrollTo().assertIsDisplayed()
    }

    @Test fun `mark renders a square inside rectangular bounds`() {
        var accent = 0
        lateinit var view: View
        compose.setContent {
            PfTheme {
                accent = PfTheme.colors.accent.toArgb()
                view = LocalView.current
                PfMark(Modifier.requiredSize(width = 56.dp, height = 22.dp).testTag("mark"), size = 56.dp)
            }
        }

        val bounds = compose.onNodeWithTag("mark").fetchSemanticsNode().boundsInRoot
        val pixels = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
        compose.runOnIdle {
            val canvas = Canvas(pixels)
            canvas.translate(-bounds.left, -bounds.top)
            view.draw(canvas)
        }
        val row = (0 until pixels.width).filter { pixels.getPixel(it, pixels.height / 2) == accent }
        val column = (0 until pixels.height).filter { pixels.getPixel(pixels.width / 2, it) == accent }
        assertEquals(22, column.last() - column.first() + 1)
        assertEquals(column.last() - column.first(), row.last() - row.first())
    }

    @Test fun `call charge copy is visible before the dial action at large font scale`() {
        compose.setContent {
            PfTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
                    CallContent(
                        CallUiState(phoneDisplay = "+7 916 123-45-67", callbackNumber = "+7 495 000-00-00"),
                        onDial = {}, onRequestAgain = {}, onRetry = {}, onChangeNumber = {}, onSupport = {},
                    )
                }
            }
        }

        val charge = compose.onNodeWithText("За звонок может взиматься плата в соответствии с тарифом вашего оператора.")
        charge.performScrollTo().assertIsDisplayed()
        val dial = compose.onNodeWithTag(AuthTags.CALL_DIAL)
        val beforeDial = charge.fetchSemanticsNode().positionInRoot.y < dial.fetchSemanticsNode().positionInRoot.y
        assertTrue(beforeDial)
        dial.performScrollTo().assertIsDisplayed()
    }

    @Test fun `support can be reached and opened in call failure states`() {
        val lockedMessage = "Слишком много попыток. Позвонить можно будет через 10 минут"
        val failureStates = listOf(
            CallPhase.OFFLINE to "Не удаётся подключиться к серверу",
            CallPhase.ERROR to "Не получилось проверить звонок. Попробуйте ещё раз",
            CallPhase.LOCKED to lockedMessage,
        )
        val phase = mutableStateOf(CallPhase.OFFLINE)
        var supportClicks = 0
        compose.setContent {
            PfTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
                    CallContent(
                        CallUiState(phoneDisplay = "+7 916 123-45-67", callbackNumber = "+7 495 000-00-00", phase = phase.value, lockText = lockedMessage),
                        onDial = {}, onRequestAgain = {}, onRetry = {}, onChangeNumber = {}, onSupport = { supportClicks++ },
                    )
                }
            }
        }

        failureStates.forEachIndexed { index, (currentPhase, message) ->
            compose.runOnIdle { phase.value = currentPhase }
            compose.onNodeWithText(message, substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag(AuthTags.CALL_SUPPORT).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(index + 1, supportClicks, currentPhase.name) }
        }
    }
}
