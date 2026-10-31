package ru.finassist.pf.feature.assistant.impl.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class VoicePhase { IDLE, PREPARING, LISTENING, RECOGNIZING, ERROR }
internal enum class VoiceFailure { PERMISSION, NO_SPEECH, UNAVAILABLE, RECOGNITION }

internal data class VoiceState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val level: Float = 0f,
    val failure: VoiceFailure? = null,
) {
    val active: Boolean get() = phase in listOf(VoicePhase.PREPARING, VoicePhase.LISTENING, VoicePhase.RECOGNIZING)
}

/** Session ids reject callbacks from a cancelled or replaced recognizer, including a late permission result. */
internal class VoiceSession {
    private val mutableState = MutableStateFlow(VoiceState())
    val state = mutableState.asStateFlow()
    private var generation = 0

    fun begin(): Int? {
        if (state.value.active) return null
        mutableState.value = VoiceState(VoicePhase.PREPARING)
        return ++generation
    }

    fun accepts(id: Int) = generation == id && state.value.active

    fun ready(id: Int): Boolean {
        if (!accepts(id) || state.value.phase != VoicePhase.PREPARING) return false
        mutableState.value = VoiceState(VoicePhase.LISTENING)
        return true
    }

    fun level(id: Int, rms: Float) {
        if (!accepts(id) || state.value.phase != VoicePhase.LISTENING) return
        mutableState.value = state.value.copy(level = if (rms.isFinite()) ((rms + 2f) / 12f).coerceIn(0f, 1f) else 0f)
    }

    fun finish(id: Int): Boolean {
        if (!accepts(id) || state.value.phase != VoicePhase.LISTENING) return false
        mutableState.value = VoiceState(VoicePhase.RECOGNIZING)
        return true
    }

    fun result(id: Int, text: String?): String? {
        if (!accepts(id)) return null
        val recognized = text?.trim()?.takeIf { it.isNotEmpty() }
        mutableState.value = if (recognized == null) VoiceState(VoicePhase.ERROR, failure = VoiceFailure.NO_SPEECH) else VoiceState()
        return recognized
    }

    fun fail(id: Int, failure: VoiceFailure) {
        if (accepts(id)) mutableState.value = VoiceState(VoicePhase.ERROR, failure = failure)
    }

    fun cancel() {
        generation++
        mutableState.value = VoiceState()
    }
}
