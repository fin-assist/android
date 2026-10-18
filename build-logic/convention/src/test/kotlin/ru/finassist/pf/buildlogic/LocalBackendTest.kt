package ru.finassist.pf.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBackendTest {

    @Test
    fun `no property means bank-bff on the emulator host`() {
        val expected = LocalBackend.BaseUrl("http://10.0.2.2:${LocalBackend.DEFAULT_PORT}/", "10.0.2.2", cleartext = true)
        assertEquals(expected, LocalBackend.parse(null))
        assertEquals(expected, LocalBackend.parse(""))
        assertEquals(expected, LocalBackend.parse("   "))
    }

    @Test
    fun `a LAN address keeps its port and gets a trailing slash`() {
        assertEquals(
            LocalBackend.BaseUrl("http://192.168.1.20:9000/", "192.168.1.20", cleartext = true),
            LocalBackend.parse(" http://192.168.1.20:9000 "),
        )
    }

    @Test
    fun `a path is kept and closed with a slash`() {
        assertEquals("http://localhost:8080/bff/", LocalBackend.parse("http://localhost:8080/bff").url)
        assertEquals("http://localhost:8080/bff/", LocalBackend.parse("http://localhost:8080/bff/").url)
    }

    @Test
    fun `https is not cleartext and host is lowercased`() {
        val url = LocalBackend.parse("HTTPS://Dev.Example.RU/")
        assertEquals("https://dev.example.ru/", url.url)
        assertEquals("dev.example.ru", url.host)
        assertFalse(url.cleartext)
    }

    @Test
    fun `an IPv6 host is a bare address in the security config`() {
        val url = LocalBackend.parse("http://[::1]:8080/")
        assertEquals("http://[::1]:8080/", url.url)
        assertEquals("::1", url.host)
    }

    @Test
    fun `invalid values fail with the property name`() {
        listOf(
            "ftp://10.0.2.2/",
            "10.0.2.2:8080",
            "http:///v1",
            "http://user:secret@10.0.2.2:8080/",
            "http://10.0.2.2:8080/?debug=1",
            "http://10.0.2.2:8080/#x",
            "http://10.0.2.2:80 80/",
        ).forEach { value ->
            val error = assertThrows(IllegalArgumentException::class.java) { LocalBackend.parse(value) }
            assertTrue(value, error.message!!.startsWith("pf.apiBaseUrl"))
        }
    }

    @Test
    fun `security config allows cleartext only to the local host`() {
        val xml = LocalBackend.networkSecurityConfig(LocalBackend.parse(null))
        assertTrue(xml.contains("""<base-config cleartextTrafficPermitted="false" />"""))
        assertTrue(xml.contains("""<domain-config cleartextTrafficPermitted="true">"""))
        assertTrue(xml.contains("""<domain includeSubdomains="false">10.0.2.2</domain>"""))
        assertEquals(1, Regex("<domain ").findAll(xml).count())
    }

    @Test
    fun `security config over https has no cleartext exception`() {
        val xml = LocalBackend.networkSecurityConfig(LocalBackend.parse("https://dev.example.ru/"))
        assertTrue(xml.contains("""<base-config cleartextTrafficPermitted="false" />"""))
        assertFalse(xml.contains("domain-config"))
        assertFalse(xml.contains("""cleartextTrafficPermitted="true""""))
    }
}
