package ru.finassist.pf.feature.assistant.impl.ui

import android.speech.SpeechRecognizer

/** Platform error constants are compile-time ints, so this policy does not need a recognition service. */
internal fun voiceFailure(error: Int): VoiceFailure = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceFailure.NO_SPEECH
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceFailure.PERMISSION
    else -> VoiceFailure.RECOGNITION
}

internal fun voiceFailure(error: RuntimeException): VoiceFailure =
    if (error is SecurityException) VoiceFailure.PERMISSION else VoiceFailure.UNAVAILABLE

internal fun fallsBackToSystem(onDevice: Boolean, failure: VoiceFailure): Boolean =
    onDevice && (failure == VoiceFailure.RECOGNITION || failure == VoiceFailure.UNAVAILABLE)
