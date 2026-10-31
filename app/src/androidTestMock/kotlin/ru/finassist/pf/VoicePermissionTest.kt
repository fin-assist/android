package ru.finassist.pf

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AskResponse
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.MessagesList
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.designsystem.theme.LocalPfClock
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.tracking.NoopTracker
import ru.finassist.pf.feature.assistant.impl.ui.ChatScreen
import ru.finassist.pf.feature.assistant.impl.ui.ChatViewModel
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

/** Real permission dialog + actual ChatScreen/VoiceInput; no recognition service or audio is needed. */
@RunWith(AndroidJUnit4::class)
class VoicePermissionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deniedMicrophonePreservesDraftWithoutSendingQuestion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // The orchestrator's clearPackageData gives every native test a fresh runtime-permission state.
        assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO))
        val asks = AtomicInteger()
        val now = OffsetDateTime.parse("2026-10-08T12:00:00+03:00")
        val clock = object : Clock {
            override fun now() = now
            override val zone = Clock.MOSCOW
        }
        val api = object : AssistantApi {
            override suspend fun listMessages(limit: Int?, beforeId: String?) = MessagesList(emptyList(), hasOlder = false)
            override suspend fun getLimit() = AssistantLimit(5, 5, now.plusDays(1))
            override fun streamAnswer(answerId: String) = emptyFlow<ru.finassist.pf.core.api.model.AnswerEvent>()
            override suspend fun ask(key: IdempotencyKey, request: AskRequest): AskResponse {
                asks.incrementAndGet()
                error("Permission refusal must not send a question")
            }
            override suspend fun retry(key: IdempotencyKey, answerId: String): RetryResponse = error("No answer to retry")
        }
        val profile = object : ProfileApi {
            override suspend fun getProfile(): Profile = error("No profile mutation or consent read is needed")
            override suspend fun updateProfile(update: ProfileUpdate): Profile = error("unused")
            override suspend fun deleteAccount() = error("unused")
            override suspend fun grantAssistantConsent(grant: AssistantConsentGrant): Profile = error("unused")
            override suspend fun revokeAssistantConsent() = error("unused")
        }
        val store = ViewModelStore()
        lateinit var vm: ChatViewModel
        compose.runOnUiThread {
            vm = ChatViewModel(SavedStateHandle(), api, NoopTracker, clock, ElapsedClock { android.os.SystemClock.elapsedRealtime() }, profile)
            store.put("voice-permission-chat", vm)
        }
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        try {
            compose.setContent {
                PfTheme {
                    CompositionLocalProvider(LocalPfClock provides clock) {
                        ChatScreen(onBack = {}, onOpenConsent = { error("Dictation must not request assistant consent") },
                            onOpenSearch = {}, onOpenAnalytics = {}, consentResult = mutableStateOf(null),
                            onConsentResultConsumed = {}, vm = vm)
                    }
                }
            }
            compose.waitUntil(10_000) { !vm.state.value.loading && vm.state.value.limit != null }
            compose.onNodeWithTag("ds.chat.input").performTextInput(DRAFT)
            compose.onNodeWithTag("ds.chat.voice").performClick()
            var deny: AccessibilityNodeInfo? = null
            compose.waitUntil(10_000) {
                val root = automation.rootInActiveWindow
                deny = listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
                    .asSequence().flatMap { pkg -> root?.findAccessibilityNodeInfosByViewId("$pkg:id/permission_deny_button").orEmpty().asSequence() }
                    .firstOrNull()
                deny != null
            }
            assertTrue("The system permission denial must be clicked", deny!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("assistant.voice.error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Нужен доступ к микрофону", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("assistant.voice.cancel").performClick()
            compose.onNodeWithTag("assistant.voice.panel").assertDoesNotExist()
            compose.onNodeWithTag("ds.chat.input").assertTextEquals(DRAFT)
            compose.onNodeWithTag("assistant.question").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(DRAFT, vm.state.value.draft)
                assertEquals(5, vm.state.value.limit?.remaining)
                assertEquals(0, asks.get())
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            compose.runOnUiThread { store.clear() }
        }
    }

    private companion object { const val DRAFT = "Мои расходы" }
}
