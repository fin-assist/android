package ru.finassist.pf.buildlogic

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

/**
 * The generator re-checks the pin expiration when it runs: a configuration cache entry or an up-to-date output from
 * an earlier day must not put an expired pin set into a new build (FIN-121).
 */
class GenerateNetworkSecurityConfigTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val today = LocalDate.of(2026, 10, 18)

    private val written get() = dir.root.resolve("out/xml/network_security_config.xml")

    private fun task(expiration: String?): GenerateNetworkSecurityConfig {
        val project = ProjectBuilder.builder().build()
        return project.tasks.register("generate", GenerateNetworkSecurityConfig::class.java) {
            config.set("<network-security-config />")
            outputDir.set(dir.root.resolve("out"))
            if (expiration != null) pinsExpiration.set(expiration)
        }.get()
    }

    @Test
    fun `pins that expired since configuration fail the task and write nothing`() {
        val error = assertThrows(IllegalArgumentException::class.java) { task("2026-10-18").generate(today) }
        assertTrue(error.message, error.message!!.contains("not after the build date"))
        assertFalse(written.exists())
    }

    @Test
    fun `stage task expiration recheck keeps its own property name`() {
        val stage = task("2026-10-18")
        stage.pinsExpirationProperty.set("pf.stageApiPinsExpiration")
        val error = assertThrows(IllegalArgumentException::class.java) { stage.generate(today) }
        assertTrue(error.message, error.message!!.contains("pf.stageApiPinsExpiration"))
        assertFalse(error.message, error.message!!.contains("pf.apiPinsExpiration"))
        assertFalse(written.exists())
    }

    @Test
    fun `active pins are written`() {
        task("2027-04-01").generate(today)
        assertEquals("<network-security-config />", written.readText())
    }

    @Test
    fun `a pinned config is never up to date, a config without pins may be`() {
        val pinned = task("2027-04-01")
        assertFalse(pinned.outputs.upToDateSpec.isSatisfiedBy(pinned))
        val plain = task(null)
        assertTrue(plain.outputs.upToDateSpec.isSatisfiedBy(plain))
    }

    @Test
    fun `a config without pins skips the date check`() {
        task(null).generate(LocalDate.of(2099, 1, 1))
        assertTrue(written.exists())
    }
}
