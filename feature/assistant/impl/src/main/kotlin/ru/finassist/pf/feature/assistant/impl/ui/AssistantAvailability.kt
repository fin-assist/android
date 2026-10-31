package ru.finassist.pf.feature.assistant.impl.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Main-layer-owned availability. It survives configuration changes, while a new process/session starts afresh.
 * An explicit entry under a newly enabled flag admits the assistant for the rest of this layer's lifetime.
 */
class AssistantAvailabilityPolicy(initiallyEnabled: Boolean) : ViewModel() {
    private val _enabled = MutableStateFlow(initiallyEnabled)
    val enabled: StateFlow<Boolean> = _enabled

    /** A live disable never revokes an admitted flow; enabling requires an explicit entry. */
    fun enter(flagEnabled: Boolean): Boolean {
        if (flagEnabled) _enabled.value = true
        return _enabled.value
    }
}

/** Missing ownership is denied before an assistant screen/ViewModel can be created. */
val LocalAssistantAvailability = staticCompositionLocalOf<AssistantAvailabilityPolicy?> { null }
