package ru.finassist.pf.feature.assistant.impl.ui

import android.Manifest
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.Lifecycle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceInputBoundaryTest {
    @Test fun `service failure selects system on the next explicit attempt only`() = withInput { h ->
        h.input.start()
        assertEquals(VoicePhase.PREPARING, h.input.state.value.phase)
        assertEquals(listOf(true), h.factory.created.map { it.onDevice })
        h.factory.latest.listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        assertEquals(VoiceFailure.RECOGNITION, h.input.state.value.failure)
        assertEquals(1, h.factory.latest.destroyed)
        assertEquals(1, h.factory.created.size, "failure cannot start another recognizer automatically")
        h.input.start()
        assertEquals(listOf(true, false), h.factory.created.map { it.onDevice })
        assertTrue(h.results.isEmpty())
    }

    @Test fun `silence keeps the preferred provider and a repeated finish stops one session`() = withInput { h ->
        h.input.start()
        h.factory.latest.listener.error(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(VoiceFailure.NO_SPEECH, h.input.state.value.failure)
        h.input.start()
        assertEquals(listOf(true, true), h.factory.created.map { it.onDevice })
        val active = h.factory.latest
        active.listener.ready()
        active.listener.ready()
        h.input.start()
        assertEquals(2, h.factory.created.size)
        active.listener.level(4f)
        assertEquals(.5f, h.input.state.value.level)
        h.input.finish()
        h.input.finish()
        assertEquals(VoicePhase.RECOGNIZING, h.input.state.value.phase)
        assertEquals(1, active.stopped)
        active.listener.results("  на такси  ")
        assertEquals(listOf("на такси"), h.results)
        assertEquals(VoicePhase.IDLE, h.input.state.value.phase)
        assertEquals(1, active.destroyed)
    }

    @Test fun `cancelled recognizer cannot change the replacement session or its transcript`() = withInput { h ->
        h.input.start()
        val cancelled = h.factory.latest
        h.input.cancel()
        assertEquals(1, cancelled.destroyed)
        h.input.start()
        val current = h.factory.latest
        cancelled.listener.ready()
        cancelled.listener.results("устаревший текст")
        cancelled.listener.error(SpeechRecognizer.ERROR_SERVER)
        assertEquals(VoicePhase.PREPARING, h.input.state.value.phase)
        assertTrue(h.results.isEmpty())
        assertEquals(0, current.destroyed)
        current.listener.ready()
        current.listener.end()
        assertEquals(VoicePhase.RECOGNIZING, h.input.state.value.phase)
        current.listener.results("новый текст")
        assertEquals(listOf("новый текст"), h.results)
    }

    @Test fun `stop exception ends the active input and ignores later results`() = withInput { h ->
        h.input.start()
        val active = h.factory.latest
        active.listener.ready()
        active.stopFailure = IllegalStateException("closed fixture recognizer")
        h.input.finish()
        assertEquals(VoicePhase.ERROR, h.input.state.value.phase)
        assertEquals(VoiceFailure.RECOGNITION, h.input.state.value.failure)
        assertEquals(1, active.destroyed)
        active.listener.results("поздний текст")
        assertTrue(h.results.isEmpty())
    }

    @Test fun `background stop cancels recognition before a late result`() = withInput { h ->
        h.input.start()
        val active = h.factory.latest
        active.listener.ready()
        h.controller.pause().stop()
        drainFrames()
        assertEquals(VoicePhase.IDLE, h.input.state.value.phase)
        assertEquals(1, active.destroyed)
        active.listener.results("после ухода в фон")
        assertTrue(h.results.isEmpty())
    }

    @Test fun `absent services and start permission failures preserve explicit retry`() = withInput { h ->
        h.factory.onDeviceAvailable = false
        h.factory.systemAvailable = false
        h.input.start()
        assertEquals(VoiceFailure.UNAVAILABLE, h.input.state.value.failure)
        assertTrue(h.factory.created.isEmpty())
        h.factory.onDeviceAvailable = true
        h.factory.startFailure = SecurityException("fixture permission refusal")
        h.input.start()
        assertEquals(VoiceFailure.PERMISSION, h.input.state.value.failure)
        assertEquals(1, h.factory.latest.destroyed)
        h.factory.startFailure = null
        h.input.start()
        assertEquals(listOf(true, true), h.factory.created.map { it.onDevice }, "permission refusal cannot select another provider")
        assertFalse(h.input.state.value.phase == VoicePhase.LISTENING, "ready requires an actual callback")
    }

    private fun withInput(test: (Harness) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        val activity = controller.setup().get()
        val h = Harness(controller)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        activity.setContent {
            val input = rememberVoiceInput(factoryOverride = h.factory, onResult = h.results::add)
            SideEffect { h.input = input }
        }
        drainFrames()
        try { test(h) } finally {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
            controller.destroy()
            drainFrames()
        }
    }

    private class Harness(val controller: ActivityController<ComponentActivity>) {
        val factory = Factory()
        val results = mutableListOf<String>()
        lateinit var input: VoiceInput
    }

    private class Factory : VoiceRecognizerFactory {
        var onDeviceAvailable = true
        var systemAvailable = true
        var startFailure: RuntimeException? = null
        val created = mutableListOf<Recognizer>()
        val latest get() = created.last()
        override fun isOnDeviceAvailable() = onDeviceAvailable
        override fun isSystemAvailable() = systemAvailable
        override fun create(onDevice: Boolean, listener: VoiceRecognizerListener): VoiceRecognizer =
            Recognizer(onDevice, listener, startFailure).also { created += it }
    }
    private class Recognizer(val onDevice: Boolean, val listener: VoiceRecognizerListener, val startFailure: RuntimeException?) : VoiceRecognizer {
        var stopped = 0
        var destroyed = 0
        var stopFailure: RuntimeException? = null
        override fun start() { startFailure?.let { throw it } }
        override fun stop() { stopped++; stopFailure?.let { throw it } }
        override fun destroy() { destroyed++ }
    }

    private fun drainFrames() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
}
