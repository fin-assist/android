package ru.finassist.pf.core.tracking

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScrubbedThrowableTest {
    @Test fun `the message is replaced by type and code`() {
        val original = IllegalArgumentException("cannot parse «Пятёрочка» 2 340 ₽ for +79161234567")
        val scrubbed = ScrubbedThrowable.of(original, "bad_amount")
        assertEquals("java.lang.IllegalArgumentException [bad_amount]", scrubbed.message)
        assertEquals("java.lang.IllegalArgumentException", scrubbed.type)
        assertTrue(scrubbed.stackTrace.contentEquals(original.stackTrace))
    }

    @Test fun `without a code only the type remains`() {
        assertEquals("java.lang.IllegalStateException", ScrubbedThrowable.of(IllegalStateException("secret")).message)
    }

    @Test fun `causes keep type and stack but lose their messages`() {
        val root = IOException("GET /v1/operations?q=Самокат failed")
        val scrubbed = ScrubbedThrowable.of(RuntimeException("wrapper for Самокат", root), "offline")
        val cause = scrubbed.cause as ScrubbedThrowable
        assertEquals("java.io.IOException", cause.message)
        assertTrue(cause.stackTrace.contentEquals(root.stackTrace))
        assertNull(cause.cause)
    }

    @Test fun `a long cause chain is cut`() {
        var error: Throwable = RuntimeException("0")
        repeat(20) { error = RuntimeException("level", error) }
        var depth = 0
        var current: Throwable? = ScrubbedThrowable.of(error)
        while (current?.cause != null) { current = current.cause; depth++ }
        assertEquals(ScrubbedThrowable.MAX_CAUSES, depth)
    }

    @Test fun `a self-referencing cause does not loop`() {
        val error = object : RuntimeException("x") { override val cause: Throwable get() = this }
        assertNull(ScrubbedThrowable.of(error).cause)
    }

    @Test fun `scrubbing twice is a no-op`() {
        val once = ScrubbedThrowable.of(RuntimeException("x"), "code")
        assertSame(once, ScrubbedThrowable.of(once, "other"))
    }
}
