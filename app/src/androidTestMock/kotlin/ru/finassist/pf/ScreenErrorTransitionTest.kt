package ru.finassist.pf

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.StatementsList
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.api.model.Upload
import ru.finassist.pf.core.api.model.UploadStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ConnectionEnvironment
import ru.finassist.pf.core.common.error.ConnectionEnvironmentReader
import ru.finassist.pf.core.designsystem.components.LocalConnectionEnvironmentReader
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.profile.impl.ui.ProfileActions
import ru.finassist.pf.feature.profile.impl.ui.ProfileContent
import ru.finassist.pf.feature.profile.impl.ui.ProfileErrorOrigin
import ru.finassist.pf.feature.profile.impl.ui.ProfileFlags
import ru.finassist.pf.feature.profile.impl.ui.ProfileUiState
import ru.finassist.pf.feature.statements.impl.ui.HistoryContent
import ru.finassist.pf.feature.statements.impl.ui.HistoryErrorOrigin
import ru.finassist.pf.feature.statements.impl.ui.HistoryUiState
import java.time.OffsetDateTime

/** State injection exercises actual screen fallback/action branches; no production test hook is needed. */
@RunWith(AndroidJUnit4::class)
class ScreenErrorTransitionTest {
    @get:Rule val compose = createComposeRule()
    private val now = OffsetDateTime.parse("2026-10-08T12:00:00+03:00")
    private val reader = ConnectionEnvironmentReader { ConnectionEnvironment.VPN }

    @Test fun profileReadFailureReplacesThemeOutcomeAndRetries() {
        val initial = ProfileUiState(
            loading = false,
            profile = Profile("u1", "+79160000001", Theme.SYSTEM, "Europe/Moscow", createdAt = now),
            flags = ProfileFlags(assistant = false, deleteAccount = false, upload = false),
            errorOrigin = ProfileErrorOrigin.THEME,
            errorPresentation = AppError.Offline().presentation,
            snackbar = THEME_OUTCOME,
        )
        var state by mutableStateOf(initial)
        var retries = 0
        var themeWrites = 0
        val actions = ProfileActions({}, {}, {}, {}, {}, {})
        compose.setContent {
            PfTheme(darkTheme = false) {
                CompositionLocalProvider(LocalConnectionEnvironmentReader provides reader) {
                    ProfileContent(
                        state, actions,
                        onRetry = {
                            retries++
                            state = initial.copy(profile = initial.profile!!.copy(phone = "+79160000002"),
                                errorPresentation = null, errorOrigin = null, snackbar = null)
                        },
                        onRevokeConsent = {}, onThemeSheet = {}, onTheme = { themeWrites++ },
                        onSupport = {}, onAskLogout = {}, onLogout = {}, onSnackbarShown = {},
                    )
                }
            }
        }
        compose.onNodeWithText("$THEME_OUTCOME\n$VPN_HINT", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(RETRY, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(errorOrigin = ProfileErrorOrigin.PROFILE,
                errorPresentation = AppError.Offline().presentation, snackbar = null, retryReady = true)
        }
        compose.onNodeWithText(THEME_OUTCOME, substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Не получилось обновить профиль\n$VPN_HINT", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(RETRY, useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.onNodeWithTag("profile.phone").assertTextEquals("+7 916 000-00-02")
        compose.onNodeWithText(TITLE, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(THEME_OUTCOME, substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(RETRY, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, themeWrites) }
    }

    @Test fun historyReadFailureReplacesDeleteOutcomeAndRetries() {
        val upload = Upload("u1", "before.ofx", UploadStatus.DONE, now, operationCount = 3, unreadCount = 0,
            periodFrom = "2026-10-01", periodTo = "2026-10-08", sourceName = "Т-Банк")
        val initial = HistoryUiState(loading = false, list = StatementsList(uploads = listOf(upload)),
            uploadEnabled = false, errorOrigin = HistoryErrorOrigin.DELETE,
            errorPresentation = AppError.Offline().presentation, snackbar = DELETE_OUTCOME)
        var state by mutableStateOf(initial)
        var retries = 0
        var deleteWrites = 0
        compose.setContent {
            PfTheme(darkTheme = false) {
                CompositionLocalProvider(LocalConnectionEnvironmentReader provides reader) {
                    HistoryContent(
                        state, onBack = {}, onUpload = {}, onOpenUnread = { _, _ -> },
                        onRetry = {
                            retries++
                            state = initial.copy(list = StatementsList(uploads = listOf(upload.copy(fileName = "restored.ofx"))),
                                errorPresentation = null, errorOrigin = null, snackbar = null)
                        },
                        onAskDelete = {}, onDelete = { deleteWrites++ }, onSnackbarShown = {},
                    )
                }
            }
        }
        compose.onNodeWithText("$DELETE_OUTCOME\n$VPN_HINT", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(RETRY, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(errorOrigin = HistoryErrorOrigin.READ,
                errorPresentation = AppError.Offline().presentation, snackbar = null, retryReady = true)
        }
        compose.onNodeWithText(DELETE_OUTCOME, substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Не получилось загрузить историю\n$VPN_HINT", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(RETRY, useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.onNodeWithText("restored.ofx", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("before.ofx", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(TITLE, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(DELETE_OUTCOME, substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(RETRY, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, deleteWrites) }
    }

    private companion object {
        const val THEME_OUTCOME = "Не получилось сохранить тему"
        const val DELETE_OUTCOME = "Загрузка не удалена"
        const val VPN_HINT = "Попробуйте отключить VPN и повторить подключение"
        const val TITLE = "Не удаётся подключиться к серверу"
        const val RETRY = "Повторить"
    }
}
