package ru.finassist.pf.feature.assistant.impl.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Voice input handle: [start] begins listening, [listening] drives the «Слушаю…» status. */
class VoiceInput(val start: () -> Unit, private val listeningState: MutableState<Boolean>) {
    val listening: Boolean get() = listeningState.value
}

/**
 * Speech to text on the device (mvp-scope: recognition on device, the result is sent at once).
 *
 * Android 13+ with an on-device model: `SpeechRecognizer.createOnDeviceSpeechRecognizer` — audio never leaves
 * the phone (needs RECORD_AUDIO). Otherwise the system recognizer activity with `EXTRA_PREFER_OFFLINE`: the
 * best available; whether it stays offline is up to the device's recognizer.
 */
@Composable
fun rememberVoiceInput(onResult: (String) -> Unit): VoiceInput {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val listening = remember { mutableStateOf(false) }
    val recognizer = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            null
        }
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                listening.value = false
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(result)
            }
            override fun onError(error: Int) { listening.value = false }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose { recognizer?.destroy() }
    }
    val systemActivity = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(result)
        }
    }
    fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    fun listenOnDevice() {
        listening.value = true
        recognizer?.startListening(intent())
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listenOnDevice()
    }
    return remember(recognizer) {
        VoiceInput(
            start = {
                when {
                    recognizer == null -> runCatching { systemActivity.launch(intent()) }
                    hasMic(context) -> listenOnDevice()
                    else -> permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            listeningState = listening,
        )
    }
}

private fun hasMic(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
