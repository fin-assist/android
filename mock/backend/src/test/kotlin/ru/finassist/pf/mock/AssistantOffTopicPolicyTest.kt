package ru.finassist.pf.mock

import org.junit.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantOffTopicPolicyTest {
    private val at = OffsetDateTime.parse("2026-10-01T12:00:00+03:00")

    @Test
    fun `window expires at the exact terminal timestamp plus 168 hours`() {
        val policy = AssistantOffTopicPolicy()
        repeat(4) { policy.terminal("$it", at.plusSeconds(it.toLong()), isOffTopic = true) }
        assertNull(policy.retryAt(at.plusDays(1)))
        policy.terminal("4", at.plusSeconds(4), isOffTopic = true)
        assertEquals(at.plusDays(7), policy.retryAt(at.plusDays(7).minusNanos(1)))
        assertNull(policy.retryAt(at.plusDays(7)))
    }

    @Test
    fun `seven in-flight failures wait for the third expiration rather than the first`() {
        val policy = AssistantOffTopicPolicy()
        for (i in listOf(6, 0, 4, 1, 5, 2, 3)) policy.terminal("$i", at.plusSeconds(i.toLong()), isOffTopic = true)
        val third = at.plusDays(7).plusSeconds(2)
        assertEquals(third, policy.retryAt(at.plusDays(1)))
        assertEquals(third, policy.retryAt(third.minusNanos(1)))
        assertNull(policy.retryAt(third))
    }

    @Test
    fun `only a charged success resets and terminal ids cannot be counted or reset twice`() {
        val policy = AssistantOffTopicPolicy()
        repeat(5) { policy.terminal("off-$it", at, isOffTopic = true) }
        assertFalse(policy.terminal("off-0", at.plusDays(1), isOffTopic = true))
        assertTrue(policy.terminal("model-error", at.plusDays(1)))
        assertTrue(policy.terminal("cannot-answer", at.plusDays(1)))
        assertTrue(policy.terminal("uncharged-complete", at.plusDays(1), charged = false))
        assertEquals(at.plusDays(7), policy.retryAt(at.plusDays(1)))
        assertTrue(policy.terminal("charged-complete", at.plusDays(1), charged = true))
        assertNull(policy.retryAt(at.plusDays(1)))
        repeat(5) { policy.terminal("new-$it", at.plusDays(1), isOffTopic = true) }
        assertFalse(policy.terminal("charged-complete", at.plusDays(2), charged = true))
        assertFalse(policy.terminal("off-0", at.plusDays(2), isOffTopic = true))
        assertEquals(at.plusDays(8), policy.retryAt(at.plusDays(2)))
        policy.clear()
        assertNull(policy.retryAt(at.plusDays(2)))
        assertTrue(policy.terminal("off-0", at.plusDays(2), isOffTopic = true))
    }
}
