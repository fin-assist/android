package ru.finassist.pf.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Base64

class ApiPinsTest {
    private val today = LocalDate.of(2026, 10, 18)

    // Test values only: digests of 32 fixed bytes, not keys of any certificate.
    private val current = pin(1)
    private val backup = pin(2)

    @Test
    fun `no properties means no pinning`() {
        assertNull(ApiPins.parse(null, null, today))
        assertNull(ApiPins.parse("", "  ", today))
    }

    @Test
    fun `two pins and an expiration are parsed`() {
        val pins = ApiPins.parse(" sha256/$current, sha256/$backup ", "2027-04-01", today)
        assertEquals(ApiPins.Pins(listOf(current, backup), LocalDate.of(2027, 4, 1)), pins)
    }

    @Test
    fun `pins may be separated by spaces, newlines or semicolons`() {
        val third = pin(3)
        val pins = ApiPins.parse("sha256/$current sha256/$backup;\nsha256/$third", "2027-04-01", today)
        assertEquals(listOf(current, backup, third), pins!!.digests)
    }

    @Test
    fun `one pin is not enough`() {
        assertFails("at least 2") { ApiPins.parse("sha256/$current", "2027-04-01", today) }
    }

    @Test
    fun `the same pin twice is not a backup`() {
        assertFails("repeats a pin") { ApiPins.parse("sha256/$current,sha256/$current", "2027-04-01", today) }
    }

    @Test
    fun `two spellings of one digest are the same pin`() {
        // 32 zero bytes: the canonical form ends with `A=`, the other spelling sets unused bits before `=`.
        val canonical = "A".repeat(43) + "="
        val sameBytes = "A".repeat(42) + "B="
        assertEquals(Base64.getDecoder().decode(canonical).toList(), Base64.getDecoder().decode(sameBytes).toList())
        assertFails("repeats a pin") { ApiPins.parse("sha256/$canonical,sha256/$sameBytes", "2027-04-01", today) }
    }

    @Test
    fun `pins are written in canonical form`() {
        val pins = ApiPins.parse("sha256/${"A".repeat(42)}B=,sha256/$backup", "2027-04-01", today)!!
        assertEquals(listOf("A".repeat(43) + "=", backup), pins.digests)
    }

    @Test
    fun `pins without expiration fail`() {
        assertFails("without pf.apiPinsExpiration") { ApiPins.parse("sha256/$current,sha256/$backup", "", today) }
    }

    @Test
    fun `expiration without pins fails`() {
        assertFails("without pf.apiPins") { ApiPins.parse(null, "2027-04-01", today) }
    }

    @Test
    fun `a pin that is not a sha256 digest fails`() {
        listOf(
            current,
            "sha1/$current",
            "SHA256/$current",
            "sha256/${current.dropLast(2)}",
            "sha256/${Base64.getEncoder().encodeToString(ByteArray(33))}",
            "sha256/${current.replace('A', '!')}",
        ).forEach { bad ->
            assertFails("pin \"") { ApiPins.parse("$bad,sha256/$backup", "2027-04-01", today) }
        }
    }

    @Test
    fun `expiration must be a date`() {
        assertFails("is not a date") { ApiPins.parse("sha256/$current,sha256/$backup", "01.04.2027", today) }
    }

    @Test
    fun `expiration must be after the build date and within a year`() {
        val both = "sha256/$current,sha256/$backup"
        assertFails("not after the build date") { ApiPins.parse(both, "2026-10-18", today) }
        assertFails("not after the build date") { ApiPins.parse(both, "2025-01-01", today) }
        assertEquals(LocalDate.of(2026, 10, 19), ApiPins.parse(both, "2026-10-19", today)!!.expiration)
        val latest = today.plusDays(ApiPins.MAX_EXPIRATION_DAYS)
        assertEquals(latest, ApiPins.parse(both, latest.toString(), today)!!.expiration)
        assertFails("more than") { ApiPins.parse(both, latest.plusDays(1).toString(), today) }
    }

    @Test
    fun `the date rules hold on their own`() {
        ApiPins.requireActive(LocalDate.of(2027, 4, 1), today)
        assertFails("not after the build date") { ApiPins.requireActive(today, today) }
        assertFails("more than") { ApiPins.requireActive(today.plusDays(ApiPins.MAX_EXPIRATION_DAYS + 1), today) }
    }

    @Test
    fun `the build date source is today`() {
        val source = object : BuildDate() {
            override fun getParameters() = throw UnsupportedOperationException()
        }
        assertEquals(LocalDate.now().toString(), source.obtain())
    }

    @Test
    fun `host is taken from an https base url`() {
        assertEquals("api.example.ru", ApiPins.host("https://API.Example.ru/"))
        assertEquals("api.example.ru", ApiPins.host("https://api.example.ru:8443/v1/"))
    }

    @Test
    fun `pinning over http fails`() {
        assertThrows(IllegalArgumentException::class.java) { ApiPins.host("http://api.example.ru/") }
    }

    @Test
    fun `security config pins the host without subdomains and forbids cleartext`() {
        val pins = ApiPins.parse("sha256/$current,sha256/$backup", "2027-04-01", today)!!
        val xml = ApiPins.networkSecurityConfig("api.example.ru", pins)
        assertTrue(xml.contains("""<base-config cleartextTrafficPermitted="false" />"""))
        assertTrue(xml.contains("""<domain-config cleartextTrafficPermitted="false">"""))
        assertTrue(xml.contains("""<domain includeSubdomains="false">api.example.ru</domain>"""))
        assertTrue(xml.contains("""<pin-set expiration="2027-04-01">"""))
        assertTrue(xml.contains("""<pin digest="SHA-256">$current</pin>"""))
        assertTrue(xml.contains("""<pin digest="SHA-256">$backup</pin>"""))
        assertFalse(xml.contains("trust-anchors"))
        assertFalse(xml.contains("debug-overrides"))
        assertFalse(xml.contains("""cleartextTrafficPermitted="true""""))
    }

    @Test
    fun `manifest overlay points at the generated config`() {
        assertTrue(ApiPins.MANIFEST_OVERLAY.contains("""android:networkSecurityConfig="@xml/network_security_config""""))
    }

    private fun assertFails(fragment: String, block: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java) { block() }
        assertTrue(error.message, error.message!!.startsWith("API pins: "))
        assertTrue(error.message, error.message!!.contains(fragment))
    }

    private fun pin(seed: Int): String = Base64.getEncoder().encodeToString(ByteArray(32) { seed.toByte() })
}
