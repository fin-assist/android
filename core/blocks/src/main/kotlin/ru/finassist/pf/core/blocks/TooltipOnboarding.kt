package ru.finassist.pf.core.blocks

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.first

/** Screen-local queue for the same anchors and popup used by manual help. */
class TooltipOnboarding {
    internal class Anchor(val position: () -> Offset?, val reveal: suspend () -> Unit)

    internal val anchors = mutableStateMapOf<List<String>, Anchor>()
    internal var current by mutableStateOf<List<String>?>(null)
        private set
    private var skipped = false

    /** Takes one snapshot of placed anchors in reading order, including anchors outside the viewport. */
    suspend fun show(layoutDirection: LayoutDirection, claim: suspend () -> Boolean) {
        if (skipped) return
        val horizontalDirection = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
        val sequence = snapshotFlow {
            anchors.mapNotNull { (key, anchor) -> anchor.position()?.let { key to it } }
                .sortedWith(compareBy<Pair<List<String>, Offset>> { it.second.y }
                    .thenBy { it.second.x * horizontalDirection })
                .map { it.first }
        }.first { it.isNotEmpty() }
        if (!claim()) return
        try {
            for (key in sequence) {
                if (skipped) break
                val anchor = anchors[key]?.takeIf { it.position() != null } ?: continue
                anchor.reveal()
                if (anchors[key] !== anchor || anchor.position() == null) continue
                current = key
                snapshotFlow { current == key && anchors[key] === anchor }.first { showing -> !showing }
                current = null
            }
        } finally {
            current = null
        }
    }

    internal fun dismiss(key: List<String>) {
        if (current == key) current = null
    }

    internal fun skip() {
        skipped = true
        current = null
    }
}

/** Installs the queue only in screens that support automatic help. */
val LocalTooltipOnboarding = compositionLocalOf<TooltipOnboarding?> { null }

internal fun hasOnboardingTag(tags: List<String>): Boolean = "onboarding" in tags

internal fun canOnboard(tags: List<String>, order: Int?): Boolean =
    order != null && order > 0 && hasOnboardingTag(tags)
