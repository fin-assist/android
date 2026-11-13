package ru.finassist.pf.core.blocks

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/** Screen-local queue for the same anchors and popup used by manual help. */
class TooltipOnboarding {
    internal class Anchor(val order: Int, val reveal: suspend () -> Unit)

    internal val anchors = mutableStateMapOf<List<String>, Anchor>()
    internal var current by mutableStateOf<List<String>?>(null)
        private set

    /** Reveals eligible anchors one at a time, taking ties in the supplied response order. */
    suspend fun show(responseOrder: List<List<String>>, claim: suspend () -> Boolean) {
        snapshotFlow {
            responseOrder.distinct().mapNotNull { key -> anchors[key]?.let { key to it } }
                .sortedBy { it.second.order }
        }.collectLatest { sequence ->
            if (sequence.isNotEmpty() && claim()) showSequence(sequence)
        }
    }

    private suspend fun showSequence(sequence: List<Pair<List<String>, Anchor>>) {
        try {
            for ((key, anchor) in sequence) {
                if (anchors[key] !== anchor) continue
                anchor.reveal()
                if (anchors[key] !== anchor) continue
                current = key
                snapshotFlow { current == key && anchors[key] === anchor }.first { showing -> !showing }
            }
        } finally {
            current = null
        }
    }

    internal fun dismiss(key: List<String>) {
        if (current == key) current = null
    }
}

/** Installs the queue only in screens that support automatic help. */
val LocalTooltipOnboarding = compositionLocalOf<TooltipOnboarding?> { null }

internal fun onboardingOrder(tags: List<String>, order: Int?): Int? =
    order?.takeIf { it > 0 && "onboarding" in tags }
