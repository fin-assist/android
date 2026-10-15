package ru.finassist.pf.core.toggles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FlagsVersionTest {
    @Test fun `no provider values means code defaults`() {
        assertEquals(FLAGS_VERSION_DEFAULTS, flagsVersion(emptyMap()))
        assertEquals(FLAGS_VERSION_DEFAULTS, StaticFlags().version)
    }

    @Test fun `eight hex digits`() {
        assertTrue(Regex("[0-9a-f]{8}").matches(flagsVersion(mapOf("assistant" to false))))
    }

    @Test fun `the same values give the same version whatever the order`() {
        val one = linkedMapOf("assistant" to false, "statements.upload" to true)
        val other = linkedMapOf("statements.upload" to true, "assistant" to false)
        assertEquals(flagsVersion(one), flagsVersion(other))
    }

    @Test fun `a changed value or key changes the version`() {
        val base = flagsVersion(mapOf("assistant" to false))
        assertNotEquals(base, flagsVersion(mapOf("assistant" to true)))
        assertNotEquals(base, flagsVersion(mapOf("auth.registration" to false)))
        assertNotEquals(base, flagsVersion(mapOf("assistant" to false, "auth.registration" to false)))
    }

    @Test fun `static flags are versioned by flag key`() {
        assertEquals(flagsVersion(mapOf("assistant" to false)), StaticFlags(mapOf(Flag.ASSISTANT to false)).version)
    }

    @Test fun `an experiment variant changes the version, none keeps the version of the flags (FIN-9 review)`() {
        val flags = mapOf("assistant" to false)
        assertEquals(flagsVersion(flags), flagsVersion(flags, emptyMap()))
        val a = flagsVersion(flags, mapOf("analytics_order" to "a"))
        assertNotEquals(a, flagsVersion(flags, mapOf("analytics_order" to "b")))
        assertNotEquals(flagsVersion(flags), a)
        assertNotEquals(FLAGS_VERSION_DEFAULTS, flagsVersion(emptyMap(), mapOf("analytics_order" to "b")))
    }
}
