package ru.finassist.pf

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.finassist.pf.core.designsystem.components.PfChatComposer
import ru.finassist.pf.core.designsystem.components.PfTestTags
import ru.finassist.pf.core.designsystem.theme.PfTheme

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class AssistantComposerKeyboardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun requestsSentenceCapitalizationAndSendsTheTypedCase() {
        val draft = mutableStateOf("")
        val sent = mutableListOf<String>()
        val editor = AtomicReference<Pair<EditorInfo, InputConnection>>()
        val interceptor = PlatformTextInputInterceptor { request, _ ->
            val info = EditorInfo()
            val connection = request.createInputConnection(info)
            editor.set(info to connection)
            try {
                awaitCancellation()
            } finally {
                editor.set(null)
                connection.closeConnection()
            }
        }
        compose.setContent {
            PfTheme {
                InterceptPlatformTextInput(interceptor) {
                    PfChatComposer(
                        value = draft.value,
                        onValueChange = { draft.value = it },
                        onSend = {
                            sent += draft.value
                            draft.value = ""
                        },
                        onVoice = {},
                        sendEnabled = draft.value.isNotBlank(),
                    )
                }
            }
        }

        compose.onNodeWithTag(PfTestTags.CHAT_INPUT).performTouchInput { click() }
        compose.waitUntil(5_000) { editor.get() != null }
        compose.runOnIdle {
            val (info, connection) = checkNotNull(editor.get())
            assertTrue(info.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0)
            assertTrue(connection.commitText("первый вопрос", 1))
        }
        compose.onNodeWithTag(PfTestTags.CHAT_SEND).performClick()
        compose.runOnIdle {
            val (info, connection) = checkNotNull(editor.get())
            assertEquals("", connection.getTextBeforeCursor(30, 0).toString())
            assertTrue(info.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0)
            assertTrue(connection.commitText("Второй вопрос", 1))
        }
        compose.onNodeWithTag(PfTestTags.CHAT_SEND).performClick()
        compose.runOnIdle { assertEquals(listOf("первый вопрос", "Второй вопрос"), sent) }
    }
}
