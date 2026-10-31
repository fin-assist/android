package ru.finassist.pf.feature.assistant.impl.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.StateFlow

internal class VoiceInput(
    val state: StateFlow<VoiceState>,
    val start: () -> Unit,
    val finish: () -> Unit,
    val cancel: () -> Unit,
)

/**
 * Prefer an on-device model; otherwise use the system recognition service with EXTRA_PREFER_OFFLINE.
 * The system service decides whether its recognition stays offline. Only its final text enters the draft.
 */
@Composable
internal fun rememberVoiceInput(onResult: (String) -> Unit): VoiceInput {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val result by rememberUpdatedState(onResult)
    val session = remember { VoiceSession() }
    val recognizer = remember { mutableStateOf<SpeechRecognizer?>(null) }
    val activeId = remember { mutableStateOf<Int?>(null) }
    val permissionId = remember { mutableStateOf<Int?>(null) }
    val onDeviceFailed = remember { mutableStateOf(false) }

    fun release() {
        recognizer.value?.destroy()
        recognizer.value = null
    }
    fun cancel() {
        session.cancel()
        activeId.value = null
        release()
    }
    fun listen(id: Int) {
        if (!session.accepts(id)) return
        val onDevice = !onDeviceFailed.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        try {
            val speech = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context)
                else {
                    session.fail(id, VoiceFailure.UNAVAILABLE)
                    return
                }
            recognizer.value = speech
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (session.ready(id)) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                override fun onRmsChanged(rmsdB: Float) = session.level(id, rmsdB)
                override fun onEndOfSpeech() { session.finish(id) }
                override fun onResults(results: Bundle?) {
                    if (!session.accepts(id)) return
                    val text = session.result(id, results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                    release()
                    text?.let(result)
                }
                override fun onError(error: Int) {
                    if (!session.accepts(id)) return
                    val failure = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceFailure.NO_SPEECH
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceFailure.PERMISSION
                        else -> VoiceFailure.RECOGNITION
                    }
                    // A service may exist without a Russian model. Offer the system provider on the next attempt.
                    if (onDevice && failure == VoiceFailure.RECOGNITION) onDeviceFailed.value = true
                    session.fail(id, failure)
                    release()
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
        } catch (_: SecurityException) {
            session.fail(id, VoiceFailure.PERMISSION)
            release()
        } catch (_: RuntimeException) {
            if (onDevice) onDeviceFailed.value = true
            session.fail(id, VoiceFailure.UNAVAILABLE)
            release()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = permissionId.value
        permissionId.value = null
        if (id != null && session.accepts(id)) {
            if (granted) listen(id) else session.fail(id, VoiceFailure.PERMISSION)
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancel() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            cancel()
        }
    }
    return VoiceInput(
        state = session.state,
        start = {
            // Wait for the previous permission dialog even when its session was cancelled.
            if (permissionId.value == null) {
                session.begin()?.let { id ->
                    activeId.value = id
                    if (hasMic(context)) listen(id) else {
                        permissionId.value = id
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        },
        finish = {
            activeId.value?.let { id ->
                if (session.finish(id)) {
                    try { recognizer.value?.stopListening() } catch (_: RuntimeException) {
                        session.fail(id, VoiceFailure.RECOGNITION)
                        release()
                    }
                }
            }
        },
        cancel = ::cancel,
    )
}

private fun hasMic(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
