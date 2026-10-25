package ru.finassist.pf.core.common.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorPresentationTest {
    @Test fun `unicode limit counts code points and rejects broken surrogates`() {
        assertTrue(isSafeUserMessage("🪙".repeat(1000)))
        assertFalse(isSafeUserMessage("🪙".repeat(1001)))
        assertFalse(isSafeUserMessage("\uD800"))
        assertFalse(isSafeUserMessage("\uDC00"))
        val whitespace = (0x0009..0x000D).toList() + listOf(0x20,0x85,0xA0,0x1680) + (0x2000..0x200A).toList() + listOf(0x2028,0x2029,0x202F,0x205F,0x3000)
        whitespace.forEach { assertFalse(isSafeUserMessage(it.toChar().toString()), it.toString()) }
        assertTrue(isSafeUserMessage("\u200B"))
    }
    @Test fun `only absent canonical text permits safe legacy text`() {
        assertEquals("legacy", ErrorPresentation().textOrFallback("fallback", "legacy"))
        assertEquals("fallback", ErrorPresentation(UserMessage.Invalid).textOrFallback("fallback", "legacy"))
        assertEquals("fallback", ErrorPresentation().textOrFallback("fallback", "\u00A0"))
        assertEquals("<b>literal</b>", ErrorPresentation(UserMessage.from("<b>literal</b>")).textOrFallback("fallback"))
    }
}
