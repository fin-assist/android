package ru.finassist.pf.feature.assistant.impl.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Platform recognition boundary. Session policy and draft handling remain in the caller. */
interface VoiceRecognizerFactory {
    fun isOnDeviceAvailable(): Boolean
    fun isSystemAvailable(): Boolean
    /** Creates an idle instance; callbacks begin only after [VoiceRecognizer.start]. */
    fun create(onDevice: Boolean, listener: VoiceRecognizerListener): VoiceRecognizer
}

/** One recognizer instance, owned and destroyed by one voice-input session. */
interface VoiceRecognizer {
    fun start()
    fun stop()
    fun destroy()
}

/** Recognition events delivered on the main thread, including callbacks arriving after cancellation. */
interface VoiceRecognizerListener {
    fun ready()
    fun level(rmsDb: Float)
    fun end()
    fun results(text: String?)
    fun error(code: Int)
}

internal class AndroidVoiceRecognizerFactory(private val context: Context) : VoiceRecognizerFactory {
    override fun isOnDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun isSystemAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun create(onDevice: Boolean, listener: VoiceRecognizerListener): VoiceRecognizer {
        val speech = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        val callback = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.ready()
            override fun onRmsChanged(rmsdB: Float) = listener.level(rmsdB)
            override fun onEndOfSpeech() = listener.end()
            override fun onResults(results: Bundle?) = listener.results(
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(),
            )
            override fun onError(error: Int) = listener.error(error)
            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
        return object : VoiceRecognizer {
            override fun start() {
                speech.setRecognitionListener(callback)
                speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
            }
            override fun stop() = speech.stopListening()
            override fun destroy() = speech.destroy()
        }
    }
}
