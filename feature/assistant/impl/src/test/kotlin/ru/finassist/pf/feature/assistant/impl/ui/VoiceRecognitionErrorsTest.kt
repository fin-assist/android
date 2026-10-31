package ru.finassist.pf.feature.assistant.impl.ui

import android.speech.SpeechRecognizer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceRecognitionErrorsTest {
    @Test fun `silence and missing permission do not abandon the on-device model`() {
        for (code in listOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            val failure = voiceFailure(code)
            assertEquals(VoiceFailure.NO_SPEECH, failure)
            assertFalse(fallsBackToSystem(true, failure))
        }
        val denied = voiceFailure(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertEquals(VoiceFailure.PERMISSION, denied)
        assertFalse(fallsBackToSystem(true, denied))
    }

    @Test fun `missing Russian model and every other service error select system on next attempt`() {
        for (code in listOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_SERVER, 999)) {
            val failure = voiceFailure(code)
            assertEquals(VoiceFailure.RECOGNITION, failure)
            assertTrue(fallsBackToSystem(true, failure))
            assertFalse(fallsBackToSystem(false, failure))
        }
    }

    @Test fun `recognizer creation exception is unavailable and only on-device can switch provider`() {
        for (error in listOf(IllegalStateException(), UnsupportedOperationException())) {
            val failure = voiceFailure(error)
            assertEquals(VoiceFailure.UNAVAILABLE, failure)
            assertTrue(fallsBackToSystem(true, failure))
        }
        val denied = voiceFailure(SecurityException())
        assertEquals(VoiceFailure.PERMISSION, denied)
        assertFalse(fallsBackToSystem(true, denied))
        for (failure in VoiceFailure.entries) assertFalse(fallsBackToSystem(false, failure))
    }
}
