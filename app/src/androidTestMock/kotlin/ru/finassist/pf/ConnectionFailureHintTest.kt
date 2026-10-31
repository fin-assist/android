package ru.finassist.pf

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ConnectionEnvironment
import ru.finassist.pf.core.common.error.ConnectionEnvironmentReader
import ru.finassist.pf.core.designsystem.components.ConnectionFailureState
import ru.finassist.pf.core.designsystem.components.ErrorMessage
import ru.finassist.pf.core.designsystem.components.LocalConnectionEnvironmentReader
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.theme.PfTheme
import org.junit.Assert.assertEquals

/** Native dependency injection verifies presentation wiring without a VPN provider or production test hook. */
@RunWith(AndroidJUnit4::class)
class ConnectionFailureHintTest {
    @get:Rule val compose = createComposeRule()

    @Test fun vpnHintUpdatesAfterRetry() {
        var environment = ConnectionEnvironment.VPN
        var attempt by mutableIntStateOf(0)
        var notice by mutableStateOf(false)
        var retries = 0
        val reader = ConnectionEnvironmentReader { environment }
        compose.setContent {
            PfTheme(darkTheme = false) {
                CompositionLocalProvider(LocalConnectionEnvironmentReader provides reader) {
                    // Each retry fails again, producing a new error presentation just like the feature loaders.
                    key(attempt) {
                        val retry: () -> Unit = { retries++; attempt++ }
                        if (notice) {
                            ErrorMessage(AppError.Offline().presentation, "unused fallback", onRetry = retry)
                        } else {
                            ConnectionFailureState { PfButton("Повторить", onClick = retry) }
                        }
                    }
                }
            }
        }
        // Cover both a full-screen failure and the shared notice used by forms and cached-data errors.
        for (showNotice in listOf(false, true)) {
            compose.runOnIdle {
                environment = ConnectionEnvironment.VPN
                notice = showNotice
                attempt++
            }
            val vpnText = if (showNotice) "$TITLE. $VPN_HINT" else VPN_HINT
            compose.onNodeWithText(vpnText, useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(INTERNET_HINT, substring = true, useUnmergedTree = true).assertDoesNotExist()
            compose.runOnIdle { environment = ConnectionEnvironment.OTHER_NETWORK }
            compose.onNodeWithText("Повторить", useUnmergedTree = true).performClick()
            val internetText = if (showNotice) "$TITLE. $INTERNET_HINT" else INTERNET_HINT
            compose.onNodeWithText(internetText, useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(VPN_HINT, substring = true, useUnmergedTree = true).assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(2, retries) }
    }

    private companion object {
        const val TITLE = "Не удаётся подключиться к серверу"
        const val VPN_HINT = "Попробуйте отключить VPN и повторить подключение"
        const val INTERNET_HINT = "Проверьте интернет и попробуйте ещё раз"
    }
}
