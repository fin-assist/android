package ru.finassist.pf.feature.assistant.impl.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
internal fun rememberVoiceInput(factoryOverride: VoiceRecognizerFactory? = null, onResult: (String) -> Unit): VoiceInput {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val result by rememberUpdatedState(onResult)
    val defaultFactory = remember(context) { AndroidVoiceRecognizerFactory(context) }
    val factory by rememberUpdatedState(factoryOverride ?: defaultFactory)
    val session = remember { VoiceSession() }
    val recognizer = remember { mutableStateOf<VoiceRecognizer?>(null) }
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
        val onDevice = !onDeviceFailed.value && factory.isOnDeviceAvailable()
        try {
            if (!onDevice && !factory.isSystemAvailable()) {
                session.fail(id, VoiceFailure.UNAVAILABLE)
                return
            }
            val speech = factory.create(onDevice, object : VoiceRecognizerListener {
                override fun ready() {
                    if (session.ready(id)) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                override fun level(rmsDb: Float) = session.level(id, rmsDb)
                override fun end() { session.finish(id) }
                override fun results(text: String?) {
                    if (!session.accepts(id)) return
                    val recognized = session.result(id, text)
                    release()
                    recognized?.let(result)
                }
                override fun error(code: Int) {
                    if (!session.accepts(id)) return
                    val failure = voiceFailure(code)
                    // A service may exist without a Russian model. Offer the system provider on the next attempt.
                    if (fallsBackToSystem(onDevice, failure)) onDeviceFailed.value = true
                    session.fail(id, failure)
                    release()
                }
            })
            recognizer.value = speech
            speech.start()
        } catch (error: RuntimeException) {
            val failure = voiceFailure(error)
            if (fallsBackToSystem(onDevice, failure)) onDeviceFailed.value = true
            session.fail(id, failure)
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
    return remember(context, view, permission) { VoiceInput(
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
                    try { recognizer.value?.stop() } catch (_: RuntimeException) {
                        session.fail(id, VoiceFailure.RECOGNITION)
                        release()
                    }
                }
            }
        },
        cancel = ::cancel,
    ) }
}

private fun hasMic(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
