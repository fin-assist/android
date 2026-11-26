package ru.finassist.pf.feature.assistant.impl.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.finassist.pf.core.api.RequestOwner
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import java.time.OffsetDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantAboutSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scrollsToQuotaRulesAndDocumentAtLargeFontScale() {
        val state = ChatUiState(
            loading = false,
            limit = AssistantLimit(3, 5, OffsetDateTime.parse("2026-10-10T00:00:00+03:00")),
            hasLimitMetadata = true,
        )
        compose.setContent {
            PfTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    AssistantChatHeader(state, ZoneId.of("Europe/Moscow"), {}, about = null, initiallyOpen = false, refreshLimit = null)
                }
            }
        }

        compose.onNodeWithTag("assistant.about.open").assertIsDisplayed().performClick()
        compose.onNodeWithText("Что можно спросить").assertIsDisplayed()
        compose.onNodeWithText("по загруженным банковским данным", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("«Как составить личный бюджет?»").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("assistant.about.limit").performScrollTo().assertIsDisplayed()
        val rules = compose.onNodeWithTag("assistant.about.rules")
        rules.performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("вопрос не засчитывается в суточный лимит", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("не исчерпали суточный лимит вопросов", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("из загруженных банковских данных", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("assistant.about.document").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ds.sheet.close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("assistant.about.sheet").assertDoesNotExist()
    }

    @Test fun waitsForSessionBeforeRestoringTheSheetAndRulesForTheSameLogin() {
        assertRestoration(otherOwner = null)
    }

    @Test fun anotherConfirmedLoginDoesNotRestoreThePreviousSheetOrRules() {
        assertRestoration(otherOwner = RequestOwner("other-user", "other-login"))
    }

    private fun assertRestoration(otherOwner: RequestOwner?) {
        val fixture = AssistantAboutTestFixture()
        val initialVm = fixture.viewModel()
        val restoredSession = MutableStateFlow<SessionState>(SessionState.Unknown)
        val restoredVm = fixture.viewModel(object : SessionRepository by fixture {
            override val state = restoredSession
        })
        var restoring = false
        try {
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                PfTheme {
                    val vm = remember { if (restoring) restoredVm else initialVm }
                    AssistantChatHeader(ChatUiState(), ZoneId.of("Europe/Moscow"), {}, vm, initiallyOpen = false, refreshLimit = null)
                }
            }
            compose.onNodeWithTag("assistant.about.sheet").assertIsDisplayed()
            compose.onNodeWithTag("assistant.about.rules").performScrollTo().performClick()
            compose.onNodeWithText("по загруженным банковским данным", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("из загруженных банковских данных", substring = true).performScrollTo().assertIsDisplayed()
            val rules = compose.onNodeWithText("Если часто задавать вопросы вне темы", substring = true)
            rules.performScrollTo().assertIsDisplayed()

            compose.runOnIdle { restoring = true }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("assistant.about.sheet").assertDoesNotExist()
            rules.assertDoesNotExist()

            if (otherOwner != null) runBlocking {
                fixture.confirmIdentity(otherOwner)
                check(fixture.onboarding.claim("assistant_chat", otherOwner.userId, otherOwner.authSessionEpoch))
            }
            val owner = otherOwner ?: fixture.owner
            compose.runOnIdle { restoredSession.value = SessionState.SignedIn(owner.userId, owner.authSessionEpoch) }
            if (otherOwner == null) {
                compose.onNodeWithTag("assistant.about.sheet").assertIsDisplayed()
                rules.performScrollTo().assertIsDisplayed()
            } else {
                compose.onNodeWithTag("assistant.about.sheet").assertDoesNotExist()
                compose.onNodeWithTag("assistant.about.open").performClick()
                compose.onNodeWithTag("assistant.about.sheet").assertIsDisplayed()
                rules.assertDoesNotExist()
            }
        } finally {
            initialVm.viewModelScope.cancel()
            restoredVm.viewModelScope.cancel()
        }
    }
}
