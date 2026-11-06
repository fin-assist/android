package ru.finassist.pf

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.model.AnswerDoneEvent
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AnswerSnapshotEvent
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AskResponse
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.Consent
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.Message
import ru.finassist.pf.core.api.model.MessageRole
import ru.finassist.pf.core.api.model.MessagesList
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.designsystem.theme.LocalPfClock
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.tracking.NoopTracker
import ru.finassist.pf.feature.assistant.impl.ui.ChatScreen
import ru.finassist.pf.feature.assistant.impl.ui.ChatItem
import ru.finassist.pf.feature.assistant.impl.ui.ChatViewModel
import ru.finassist.pf.feature.assistant.impl.ui.VoiceRecognizer
import ru.finassist.pf.feature.assistant.impl.ui.VoiceRecognizerFactory
import ru.finassist.pf.feature.assistant.impl.ui.VoiceRecognizerListener
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

/** Actual voice/session/chat UI; only platform callbacks and declared server APIs are deterministic fakes. */
@RunWith(AndroidJUnit4::class)
class VoiceDictationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = OffsetDateTime.parse("2026-10-08T12:00:00+03:00")
    private val clock = object : Clock {
        override fun now() = now
        override val zone = Clock.MOSCOW
    }
    private val recognizers = ScriptedVoiceRecognizers()
    private val api = VoiceChatApi(now)
    private val consentResult = mutableStateOf<String?>(null)
    private val consentEntries = AtomicInteger()
    private var backNavigations = 0
    private lateinit var vm: ChatViewModel

    @Test fun dictationEventsPreserveDraftAndRequireExplicitSend() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.RECORD_AUDIO))
        val store = ViewModelStore()
        compose.runOnUiThread {
            vm = ChatViewModel(SavedStateHandle(), api, NoopTracker, clock,
                ElapsedClock { android.os.SystemClock.elapsedRealtime() }, VoiceProfileApi(api, now))
            store.put("voice-dictation-chat", vm)
        }
        try {
            compose.setContent {
                PfTheme {
                    CompositionLocalProvider(LocalPfClock provides clock) {
                        ChatScreen(
                            onBack = { backNavigations++ }, onOpenConsent = { consentEntries.incrementAndGet() },
                            onOpenSearch = {}, onOpenAnalytics = {}, consentResult = consentResult,
                            onConsentResultConsumed = { consentResult.value = null }, vm = vm,
                            speechRecognitionFactory = recognizers,
                        )
                    }
                }
            }
            compose.waitUntil(10_000) { !vm.state.value.loading && vm.state.value.limit != null }
            compose.onNodeWithText("Предыдущий ответ 3").assertIsDisplayed()
            compose.onNodeWithTag(INPUT).performTextInput(DRAFT)

            finishAddsEditableTextWithoutSending()
            cancellationRetiresLateCallbacks()
            backAndBackgroundCancelActualSession()
            errorsKeepDraftAndChooseTheNextProvider()
            consentHandoffAndExplicitSendUseOnlyEditedText()
        } finally {
            compose.runOnUiThread { store.clear() }
        }
    }

    private fun finishAddsEditableTextWithoutSending() {
        val count = recognizers.instances.size
        clickTwiceInOneFrame(MIC)
        assertPhase("preparing", "Подготавливаем микрофон…")
        compose.onNodeWithTag("assistant.voice.listening").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(count + 1, recognizers.instances.size)
            assertEquals(1, recognizers.latest.starts)
            assertTrue(recognizers.latest.onDevice)
        }
        val recognizer = recognizers.latest
        compose.runOnIdle { recognizer.listener.ready(); recognizer.listener.ready() }
        assertPhase("listening", "Говорите, я слушаю")
        compose.runOnIdle { assertEquals("Repeated ready must retain one recognizer", count + 1, recognizers.instances.size) }
        compose.runOnIdle { recognizer.listener.level(-20f) }
        assertLevel(0f)
        compose.runOnIdle { recognizer.listener.level(30f) }
        assertLevel(1f)
        clickTwiceInOneFrame("assistant.voice.finish")
        assertPhase("recognizing", "Распознаём речь…")
        compose.runOnIdle { assertEquals(1, recognizer.stops) }
        compose.runOnIdle { recognizer.listener.end(); recognizer.listener.results("на такси") }
        compose.onNodeWithTag("assistant.voice.panel").assertDoesNotExist()
        assertDraft(VOICE_DRAFT)
        assertNoVoiceSubmission()
        compose.runOnIdle { assertEquals(1, recognizer.destroys) }
    }

    private fun cancellationRetiresLateCallbacks() {
        compose.onNodeWithTag(MIC).performClick()
        val cancelled = recognizers.latest
        compose.runOnIdle { cancelled.listener.ready() }
        compose.onNodeWithTag("assistant.voice.cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, cancelled.destroys)
            cancelled.listener.results("отменённый результат")
        }
        assertDraft(VOICE_DRAFT)
        compose.onNodeWithTag("assistant.voice.panel").assertDoesNotExist()

        // The cancelled callback must also be ignored while a replacement session is active.
        compose.onNodeWithTag(MIC).performClick()
        val current = recognizers.latest
        compose.runOnIdle { cancelled.listener.ready(); cancelled.listener.error(SpeechRecognizer.ERROR_SERVER); cancelled.listener.results("старый результат") }
        assertPhase("preparing", "Подготавливаем микрофон…")
        compose.runOnIdle { assertEquals(VOICE_DRAFT, vm.state.value.draft); assertEquals(0, current.destroys) }
        compose.onNodeWithTag("assistant.voice.cancel").performClick()
        assertDraft(VOICE_DRAFT)
        assertNoVoiceSubmission()
    }

    private fun backAndBackgroundCancelActualSession() {
        compose.onNodeWithTag(MIC).performClick()
        val backed = recognizers.latest
        assertPhase("preparing", "Подготавливаем микрофон…")
        // The active panel removes the composer. Observe actual IME retirement, then send
        // an OS Back key rather than invoking the app's dispatcher directly.
        compose.waitUntil(10_000) {
            var imeVisible = true
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                imeVisible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) ?: true
            }
            !imeVisible
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.runOnIdle {
            assertEquals(0, backNavigations)
            assertEquals(1, backed.destroys)
            backed.listener.results("результат после Back")
        }
        compose.onNodeWithTag("assistant.voice.panel").assertDoesNotExist()
        assertDraft(VOICE_DRAFT)
        assertNoVoiceSubmission()

        compose.onNodeWithTag(MIC).performClick()
        val backgrounded = recognizers.latest
        compose.runOnIdle { backgrounded.listener.ready() }
        assertPhase("listening", "Говорите, я слушаю")
        // ActivityScenario drives a real ON_STOP; no synthetic call to VoiceInput.cancel is made.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertEquals(1, backgrounded.destroys)
            backgrounded.listener.ready()
            backgrounded.listener.results("результат из фона")
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithTag("assistant.voice.panel").assertDoesNotExist()
        assertDraft(VOICE_DRAFT)
        assertNoVoiceSubmission()
    }

    private fun errorsKeepDraftAndChooseTheNextProvider() {
        compose.onNodeWithTag(MIC).performClick()
        compose.runOnIdle { recognizers.latest.listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
        assertPhase("error", "Не удалось расслышать речь")
        assertDraft(VOICE_DRAFT)
        assertNoVoiceSubmission()
        compose.onNodeWithTag("assistant.voice.retry").performClick()
        compose.runOnIdle { assertTrue("Silence must not select the system fallback", recognizers.latest.onDevice) }
        compose.runOnIdle { recognizers.latest.listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
        assertPhase("error", "Нужен доступ к микрофону")
        assertDraft(VOICE_DRAFT)
        assertNoVoiceSubmission()
        compose.onNodeWithTag("assistant.voice.retry").performClick()
        compose.runOnIdle { assertTrue("Permission refusal must not change the provider", recognizers.latest.onDevice) }
        compose.runOnIdle { recognizers.latest.listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }
        assertPhase("error", "Не получилось распознать речь")
        assertDraft(VOICE_DRAFT)
        assertNoVoiceSubmission()
        compose.onNodeWithTag("assistant.voice.retry").performClick()
        compose.runOnIdle { assertFalse("A failed on-device model selects the system provider next", recognizers.latest.onDevice) }
        assertPhase("preparing", "Подготавливаем микрофон…")
        compose.runOnIdle { recognizers.latest.listener.error(SpeechRecognizer.ERROR_SERVER) }
        assertPhase("error", "Не получилось распознать речь")
        assertDraft(VOICE_DRAFT)
        assertNoVoiceSubmission()
        compose.onNodeWithTag("assistant.voice.retry").performClick()
        compose.runOnIdle { assertFalse(recognizers.latest.onDevice) }
        compose.onNodeWithTag("assistant.voice.cancel").performClick()

        compose.runOnIdle { recognizers.systemAvailable = false }
        val before = recognizers.instances.size
        compose.onNodeWithTag(MIC).performClick()
        assertPhase("error", "Распознавание речи недоступно")
        compose.runOnIdle { assertEquals(before, recognizers.instances.size) }
        assertDraft(VOICE_DRAFT)
        assertNoVoiceSubmission()
        compose.runOnIdle { recognizers.systemAvailable = true }
        compose.onNodeWithTag("assistant.voice.retry").performClick()
        assertPhase("preparing", "Подготавливаем микрофон…")
        compose.onNodeWithTag("assistant.voice.cancel").performClick()
        assertDraft(VOICE_DRAFT)
        assertNoVoiceSubmission()
    }

    private fun consentHandoffAndExplicitSendUseOnlyEditedText() {
        compose.onNodeWithTag(INPUT).performTextReplacement(EDITED)
        compose.onNodeWithTag("ds.chat.send").performClick()
        compose.waitUntil(10_000) { consentEntries.get() == 1 }
        assertDraft(EDITED)
        compose.runOnIdle {
            assertEquals(listOf(EDITED), api.attempts)
            assertTrue(api.accepted.isEmpty())
            assertEquals(5, vm.state.value.limit?.remaining)
            assertEquals(ORIGINAL_QUESTION_IDS,
                vm.state.value.items.filterIsInstance<ChatItem.Question>().map { it.id })
        }
        // Only the navigation-result handoff is simulated here; Qase12 owns the actual consent-screen journey.
        compose.runOnIdle { api.hasConsent = true; consentResult.value = "true" }
        compose.waitUntil(10_000) { !vm.state.value.consentRequired }
        compose.runOnIdle { assertEquals(listOf(EDITED), api.attempts); assertTrue(api.accepted.isEmpty()) }
        compose.onNodeWithTag("ds.chat.send").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { !vm.state.value.generating && vm.state.value.items.any { it.id == "voice-new-q" } }
        compose.onNodeWithText(EDITED).assertIsDisplayed()
        // The empty field still merges the visible placeholder; assert its editable value separately.
        assertEquals("", compose.onNodeWithTag(INPUT).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle {
            assertEquals("", vm.state.value.draft)
            assertEquals(listOf(EDITED, EDITED), api.attempts)
            assertEquals(listOf(EDITED), api.accepted)
            assertEquals(4, vm.state.value.limit?.remaining)
            assertEquals(ORIGINAL_QUESTION_IDS + "voice-new-q",
                vm.state.value.items.filterIsInstance<ChatItem.Question>().map { it.id })
        }
    }

    private fun assertNoVoiceSubmission() = compose.runOnIdle {
        assertTrue(api.attempts.isEmpty())
        assertTrue(api.accepted.isEmpty())
        assertEquals(5, vm.state.value.limit?.remaining)
        assertEquals(ORIGINAL_QUESTION_IDS,
            vm.state.value.items.filterIsInstance<ChatItem.Question>().map { it.id })
    }

    private fun assertDraft(expected: String) {
        compose.onNodeWithTag(INPUT).assertTextEquals(expected)
        compose.runOnIdle { assertEquals(expected, vm.state.value.draft) }
    }

    private fun assertPhase(phase: String, title: String) {
        compose.onNodeWithTag("assistant.voice.$phase", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun assertLevel(expected: Float) {
        val range = compose.onNodeWithTag("assistant.voice.level").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(expected, range.current, 0.001f)
    }

    /** Two queued actions from the same visible node exercise rapid input before the next composition frame. */
    private fun clickTwiceInOneFrame(tag: String) {
        val click = requireNotNull(compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle { assertTrue(click()); assertTrue(click()) }
    }

    private companion object {
        val ORIGINAL_QUESTION_IDS = listOf("voice-old-q1", "voice-old-q2", "voice-old-q3")
        const val INPUT = "ds.chat.input"
        const val MIC = "ds.chat.voice"
        const val DRAFT = "Мои расходы"
        const val VOICE_DRAFT = "Мои расходы на такси"
        const val EDITED = "Мои расходы на такси в сентябре"
    }
}

/** Retains retired listeners deliberately so the real session guard must reject late callbacks. */
private class ScriptedVoiceRecognizers : VoiceRecognizerFactory {
    var systemAvailable = true
    val instances = mutableListOf<Recognizer>()
    val latest get() = instances.last()
    override fun isOnDeviceAvailable() = true
    override fun isSystemAvailable() = systemAvailable
    override fun create(onDevice: Boolean, listener: VoiceRecognizerListener): VoiceRecognizer =
        Recognizer(onDevice, listener).also { instances += it }

    class Recognizer(val onDevice: Boolean, val listener: VoiceRecognizerListener) : VoiceRecognizer {
        var starts = 0
        var stops = 0
        var destroys = 0
        override fun start() { starts++ }
        override fun stop() { stops++ }
        override fun destroy() { destroys++ }
    }
}

private class VoiceChatApi(private val now: OffsetDateTime) : AssistantApi {
    var hasConsent = false
    val attempts = mutableListOf<String>()
    val accepted = mutableListOf<String>()
    private var remaining = 5
    private val history = (1..3).flatMap { n -> listOf(
        Message("voice-old-q$n", MessageRole.USER, text = "Предыдущий вопрос $n", createdAt = now.minusDays(1).minusMinutes((7 - n * 2).toLong())),
        Message("voice-old-a$n", MessageRole.ASSISTANT, status = AnswerStatus.COMPLETE,
            blocks = listOf(Block.text("Предыдущий ответ $n")), createdAt = now.minusDays(1).minusMinutes((6 - n * 2).toLong())),
    ) }.toMutableList()

    override suspend fun listMessages(limit: Int?, beforeId: String?) = MessagesList(history.toList(), hasOlder = false)
    override suspend fun getLimit() = AssistantLimit(remaining, 5, now.plusDays(1))
    override suspend fun ask(key: IdempotencyKey, request: AskRequest): AskResponse {
        attempts += request.text
        if (!hasConsent) throw AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403)
        accepted += request.text
        remaining--
        val question = Message("voice-new-q", MessageRole.USER, text = request.text, createdAt = now)
        history += question
        history += Message("voice-new-a", MessageRole.ASSISTANT, status = AnswerStatus.COMPLETE,
            blocks = listOf(Block.text("Ответ на исправленный вопрос")), createdAt = now)
        return AskResponse(question, "voice-new-a", remaining)
    }
    override fun streamAnswer(answerId: String): Flow<AnswerEvent> = flowOf(
        AnswerEvent.Snapshot(AnswerSnapshotEvent(AnswerStatus.COMPLETE, listOf(Block.text("Ответ на исправленный вопрос")), emptyList())),
        AnswerEvent.Done(AnswerDoneEvent(charged = true, remainingLimit = remaining)),
    )
    override suspend fun retry(key: IdempotencyKey, answerId: String): RetryResponse = error("This dictation journey does not retry answers")
}

private class VoiceProfileApi(private val api: VoiceChatApi, private val now: OffsetDateTime) : ProfileApi {
    override suspend fun getProfile() = Profile("voice-user", "+79160000069", Theme.SYSTEM, "Europe/Moscow",
        consents = listOf(Consent(ConsentType.ASSISTANT, api.hasConsent, "1", "1")), createdAt = now)
    override suspend fun updateProfile(update: ProfileUpdate): Profile = error("No profile update in the dictation journey")
    override suspend fun deleteAccount() = error("No account deletion in the dictation journey")
    override suspend fun grantAssistantConsent(grant: AssistantConsentGrant): Profile = error("Qase12 owns the consent screen")
    override suspend fun revokeAssistantConsent() = error("No consent revocation in the dictation journey")
}
