package ru.finassist.pf.core.blocks

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TooltipOnboardingTest {
    @Test
    fun usesReadingOrderAndOnlyRevealsTheFrozenSequence() = runTest {
        val queue = TooltipOnboarding()
        val top = listOf("assistant")
        val left = listOf("group", "left")
        val right = listOf("group", "right")
        val offscreen = listOf("offscreen")
        val removed = listOf("removed")
        val added = listOf("added")
        val revealed = mutableListOf<List<String>>()
        fun anchor(key: List<String>, x: Float, y: Float) = TooltipOnboarding.Anchor({ Offset(x, y) }) {
            assertNull(queue.current)
            revealed.add(key)
        }
        // Deliberately not in reading order. The last anchor can be outside the viewport.
        queue.anchors[offscreen] = anchor(offscreen, 0f, 1100f)
        queue.anchors[right] = anchor(right, 20f, 200f)
        queue.anchors[top] = anchor(top, 80f, 20f)
        queue.anchors[left] = anchor(left, 0f, 200f)
        queue.anchors[removed] = anchor(removed, 0f, 400f)

        var claims = 0
        val job = launch { queue.show(LayoutDirection.Ltr) { ++claims == 1 } }
        runCurrent()
        assertEquals(top, queue.current)
        Snapshot.withMutableSnapshot {
            queue.anchors.remove(removed)
            queue.anchors[added] = anchor(added, 0f, 40f)
            queue.anchors[right] = anchor(right, 0f, 100f)
        }
        for (expected in listOf(top, left, right, offscreen)) {
            runCurrent()
            assertEquals(expected, queue.current)
            assertEquals(expected, revealed.last())
            queue.dismiss(added)
            assertEquals(expected, queue.current)
            Snapshot.withMutableSnapshot { queue.dismiss(expected) }
        }
        runCurrent()
        assertEquals(1, claims)
        assertEquals(listOf(top, left, right, offscreen), revealed)
        assertNull(queue.current)
        job.join()
    }

    @Test
    fun skipClosesTheCurrentTipAndNeverRevealsTheRemainingAnchors() = runTest {
        val queue = TooltipOnboarding()
        val left = listOf("left")
        val right = listOf("right")
        queue.anchors[left] = TooltipOnboarding.Anchor({ Offset(0f, 0f) }) { error("not reached") }
        queue.anchors[right] = TooltipOnboarding.Anchor({ Offset(50f, 0f) }) { }
        var claims = 0
        val job = launch { queue.show(LayoutDirection.Rtl) { ++claims == 1 } }
        runCurrent()
        assertEquals(right, queue.current)
        Snapshot.withMutableSnapshot { queue.skip() }
        job.join()
        assertNull(queue.current)
        queue.show(LayoutDirection.Rtl) { ++claims == 1 }
        assertEquals(1, claims)
    }

    @Test
    fun waitsForPlacementAndClearsTheVisibleAnchorWhenInterrupted() = runTest {
        val queue = TooltipOnboarding()
        val key = listOf("block")
        queue.anchors[key] = TooltipOnboarding.Anchor({ null }) { }
        var claims = 0
        val job = launch { queue.show(LayoutDirection.Ltr) { ++claims == 1 } }
        runCurrent()
        assertEquals(0, claims)
        Snapshot.withMutableSnapshot { queue.anchors[key] = TooltipOnboarding.Anchor({ Offset.Zero }) { } }
        runCurrent()
        assertEquals(key, queue.current)
        job.cancelAndJoin()
        assertNull(queue.current)

        queue.show(LayoutDirection.Ltr) { ++claims == 1 }
        assertEquals(2, claims)
    }

    @Test
    fun serverEligibilityRequiresTheTagAndAPositiveOrder() {
        assertTrue(canOnboard(listOf("onboarding", "future_tag"), 1))
        assertFalse(canOnboard(listOf("onboarding"), null))
        assertFalse(canOnboard(listOf("onboarding"), 0))
        assertFalse(canOnboard(listOf("future_tag"), 2))
    }
}
