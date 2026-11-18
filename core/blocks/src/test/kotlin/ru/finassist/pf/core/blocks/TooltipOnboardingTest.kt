package ru.finassist.pf.core.blocks

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class TooltipOnboardingTest {
    @Test
    fun sortsByOrderUsesResponseOrderForTiesAndRevealsBeforeShowing() = runTest {
        val queue = TooltipOnboarding()
        val first = listOf("first")
        val block = listOf("group")
        val tile = listOf("group", "tile")
        val last = listOf("last")
        val missing = listOf("missing")
        val revealed = mutableListOf<List<String>>()
        mapOf(last to 7, tile to 4, first to 2, block to 4).forEach { (key, order) ->
            queue.anchors[key] = TooltipOnboarding.Anchor(order) {
                assertNull(queue.current)
                revealed.add(key)
            }
        }

        var claims = 0
        val job = launch { queue.show(listOf(block, tile, tile, missing, last, first)) { ++claims == 1 } }
        for (expected in listOf(first, block, tile, last)) {
            runCurrent()
            assertEquals(expected, queue.current)
            assertEquals(expected, revealed.last())
            queue.dismiss(missing)
            assertEquals(expected, queue.current)
            Snapshot.withMutableSnapshot { queue.dismiss(expected) }
        }
        runCurrent()
        assertEquals(1, claims)
        assertEquals(listOf(first, block, tile, last), revealed)
        assertNull(queue.current)
        job.cancelAndJoin()
    }

    @Test
    fun skipsEmptyListsAndClearsTheVisibleAnchorWhenInterrupted() = runTest {
        val queue = TooltipOnboarding()
        val key = listOf("block")
        var claims = 0
        val job = launch { queue.show(listOf(key)) { ++claims == 1 } }
        runCurrent()
        assertEquals(0, claims)
        Snapshot.withMutableSnapshot { queue.anchors[key] = TooltipOnboarding.Anchor(1) { } }
        runCurrent()
        assertEquals(key, queue.current)
        job.cancelAndJoin()
        assertNull(queue.current)

        val reopened = launch { queue.show(listOf(key)) { ++claims == 1 } }
        runCurrent()
        assertNull(queue.current)
        assertEquals(2, claims)
        reopened.cancelAndJoin()
    }
}
