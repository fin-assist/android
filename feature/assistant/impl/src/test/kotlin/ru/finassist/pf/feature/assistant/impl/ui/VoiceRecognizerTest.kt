package ru.finassist.pf.feature.assistant.impl.ui

import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSpeechRecognizer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceRecognizerTest {
    @Test fun `android recognizer preserves request options and forwards only final results`() {
        val events = mutableListOf<String>()
        val listener = object : VoiceRecognizerListener {
            override fun ready() { events += "ready" }
            override fun level(rmsDb: Float) { events += "level:$rmsDb" }
            override fun end() { events += "end" }
            override fun results(text: String?) { events += "result:$text" }
            override fun error(code: Int) { events += "error:$code" }
        }
        val factory = AndroidVoiceRecognizerFactory(RuntimeEnvironment.getApplication())
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        assertTrue(factory.isOnDeviceAvailable())
        val input = factory.create(onDevice = true, listener)
        val shadow = shadowOf(requireNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
        input.start()
        shadowOf(Looper.getMainLooper()).idle()
        val intent = shadow.lastRecognizerIntent
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertEquals("ru-RU", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        shadow.triggerOnReadyForSpeech(Bundle())
        shadow.triggerOnRmsChanged(4f)
        shadow.triggerOnPartialResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("промежуточный текст"))
        })
        shadow.triggerOnEndOfSpeech()
        shadow.triggerOnResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("на такси", "вторая гипотеза"))
        })
        shadow.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("ready", "level:4.0", "end", "result:на такси", "error:7"), events)
        input.stop()
        input.destroy()
        assertTrue(shadow.isDestroyed)
    }

    @Test @Config(sdk = [28]) fun `pre Android 13 does not query or select an on-device recognizer`() {
        val factory = AndroidVoiceRecognizerFactory(RuntimeEnvironment.getApplication())
        assertFalse(factory.isOnDeviceAvailable())
        assertFalse(factory.isSystemAvailable(), "this fixture has no recognition service")
        val input = factory.create(onDevice = false, listener = NoopListener)
        input.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH,
            shadowOf(requireNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())).lastRecognizerIntent.action)
        input.destroy()
    }

    @Test fun `missing final results forward absence instead of a fabricated transcript`() {
        val results = mutableListOf<String?>()
        val input = AndroidVoiceRecognizerFactory(RuntimeEnvironment.getApplication()).create(true, object : VoiceRecognizerListener by NoopListener {
            override fun results(text: String?) { results += text }
        })
        input.start()
        shadowOf(Looper.getMainLooper()).idle()
        val shadow = shadowOf(requireNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
        shadow.triggerOnResults(Bundle())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf<String?>(null), results)
        input.destroy()
    }

    private object NoopListener : VoiceRecognizerListener {
        override fun ready() = Unit
        override fun level(rmsDb: Float) = Unit
        override fun end() = Unit
        override fun results(text: String?) = Unit
        override fun error(code: Int) = Unit
    }
}
