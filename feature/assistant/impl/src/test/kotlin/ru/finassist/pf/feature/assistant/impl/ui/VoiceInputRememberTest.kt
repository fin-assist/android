package ru.finassist.pf.feature.assistant.impl.ui

import android.Manifest
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // Robolectric 4.14.1's SpeechRecognizer callback shadow supports Android U.
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceInputRememberTest {
    @Test fun `forced recompositions keep voice handle commands and latest result callback`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        val activity = controller.setup().get()
        val generation = mutableIntStateOf(0)
        val observed = mutableListOf<Sample>()
        val results = mutableListOf<Pair<Int, String>>()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        try {
            activity.setContent {
                val current = generation.intValue
                val input = rememberVoiceInput { text -> results += current to text }
                SideEffect { observed += Sample(current, input) }
            }
            drainFrames()
            assertTrue(observed.isNotEmpty(), "the real composable must have run")
            val original = observed.last().input
            fun assertStable() {
                val actual = observed.last().input
                assertSame(original, actual)
                assertSame(original.start, actual.start)
                assertSame(original.finish, actual.finish)
                assertSame(original.cancel, actual.cancel)
            }
            for (next in 1..3) {
                generation.intValue = next
                drainFrames()
                assertEquals(next, observed.last().generation, "the callback capture must force recomposition")
                assertStable()
            }

            original.start()
            drainFrames()
            val recognizer = shadowOf(requireNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
            assertEquals(VoicePhase.PREPARING, original.state.value.phase)
            recognizer.triggerOnReadyForSpeech(Bundle())
            drainFrames()
            assertEquals(VoicePhase.LISTENING, original.state.value.phase)
            generation.intValue = 4
            drainFrames()
            assertEquals(4, observed.last().generation)
            assertStable()
            recognizer.triggerOnResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("на такси"))
            })
            drainFrames()
            assertEquals(listOf(4 to "на такси"), results, "a stable handle must call the latest onResult")
            assertEquals(VoicePhase.IDLE, original.state.value.phase)
        } finally {
            controller.pause().stop().destroy()
            drainFrames()
        }
    }

    private fun drainFrames() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    private data class Sample(val generation: Int, val input: VoiceInput)
}
