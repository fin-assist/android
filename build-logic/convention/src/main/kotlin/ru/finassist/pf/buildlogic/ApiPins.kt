package ru.finassist.pf.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * Certificate pinning of the API (FIN-121; decision — Confluence PF, «Архитектура»): SPKI pins of our own keys for
 * the host of the environment's `API_BASE_URL`, in the network security config of `prod` and `stage`.
 *
 * Pins come from Gradle properties (`pf.apiPins` — `sha256/<base64>` separated by commas or spaces,
 * `pf.apiPinsExpiration` — `yyyy-MM-dd`) or the matching environment variables in CI. No pins — no pinning, the
 * build trusts the system CAs as before. Pins are public data, but real ones are never committed: they live where
 * the release is built.
 */
object ApiPins {
    const val PINS_PROPERTY = "pf.apiPins"
    const val EXPIRATION_PROPERTY = "pf.apiPinsExpiration"

    /** The current key and at least one backup: rotating to a key the app does not know would lock every user out. */
    const val MIN_PINS = 2

    /**
     * Latest allowed expiration, counted from the build date. A build pins for at most a year: an app that is not
     * updated for longer falls back to plain CA validation instead of risking a permanent lock-out.
     */
    const val MAX_EXPIRATION_DAYS = 366L

    private const val PREFIX = "sha256/"
    private const val SHA256_BYTES = 32

    /**
     * A checked pin set: base64 SHA-256 digests of SubjectPublicKeyInfo in canonical form (what the standard encoder
     * writes for the decoded bytes), and the date pinning stops at.
     */
    data class Pins(val digests: List<String>, val expiration: LocalDate)

    /**
     * Checks the two properties. Both blank → `null` (no pinning). Otherwise both are required: at least [MIN_PINS]
     * `sha256/` pins of 32 bytes (44 base64 characters) that decode to different digests, and an expiration after
     * [today] and no later than [MAX_EXPIRATION_DAYS] from it. Any other value fails the configuration with the reason.
     */
    fun parse(
        pinsRaw: String?,
        expirationRaw: String?,
        today: LocalDate,
        pinsProperty: String = PINS_PROPERTY,
        expirationProperty: String = EXPIRATION_PROPERTY,
    ): Pins? {
        val pinsValue = pinsRaw?.trim().orEmpty()
        val expirationValue = expirationRaw?.trim().orEmpty()
        if (pinsValue.isEmpty() && expirationValue.isEmpty()) return null
        if (pinsValue.isEmpty()) invalid("$expirationProperty is set without $pinsProperty")
        if (expirationValue.isEmpty()) {
            invalid("$pinsProperty is set without $expirationProperty: a pin set without expiration can lock the app out for good")
        }
        val digests = pinsValue.split(',', ' ', '\n', '\t', ';').filter { it.isNotBlank() }.map(::digest)
        if (digests.size < MIN_PINS) {
            invalid("$pinsProperty has ${digests.size} pin(s), at least $MIN_PINS are required (the current key and a backup)")
        }
        // Compared as canonical encodings of the decoded bytes: two spellings of one digest (different unused bits
        // before `=`) are the same key, not a backup.
        if (digests.toSet().size != digests.size) invalid("$pinsProperty repeats a pin: a backup must be a different key")
        val expiration = try {
            LocalDate.parse(expirationValue)
        } catch (e: DateTimeParseException) {
            invalid("$expirationProperty \"$expirationValue\" is not a date like 2027-10-01")
        }
        requireActive(expiration, today, expirationProperty)
        return Pins(digests, expiration)
    }

    /**
     * The date rules of [parse] on their own: [expiration] after [today] and within [MAX_EXPIRATION_DAYS]. Checked
     * again when the config is written ([GenerateNetworkSecurityConfig]): a reused configuration cache entry or an
     * up-to-date task must not put an expired pin set into a new APK.
     */
    fun requireActive(expiration: LocalDate, today: LocalDate, expirationProperty: String = EXPIRATION_PROPERTY) {
        if (!expiration.isAfter(today)) {
            invalid("$expirationProperty $expiration is not after the build date $today: the pins would be ignored")
        }
        if (expiration.isAfter(today.plusDays(MAX_EXPIRATION_DAYS))) {
            invalid("$expirationProperty $expiration is more than $MAX_EXPIRATION_DAYS days after the build date $today")
        }
    }

    /** Host of an environment's base URL to pin; pinning makes sense only over https. */
    fun host(baseUrl: String): String {
        val uri = URI(baseUrl)
        require(uri.scheme.equals("https", ignoreCase = true) && uri.host != null) {
            "Pinning needs an https base URL with a host, got \"$baseUrl\""
        }
        return uri.host.lowercase().removeSurrounding("[", "]")
    }

    /**
     * Network security config with the pins: cleartext forbidden everywhere, the API host (without subdomains) pinned
     * on top of the system CAs. No trust anchors of its own and no debug overrides: user CAs stay untrusted.
     */
    fun networkSecurityConfig(host: String, pins: Pins): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("<!-- Generated API pins (build-logic ApiPins, FIN-121/385). -->")
        appendLine("<network-security-config>")
        appendLine("""    <base-config cleartextTrafficPermitted="false" />""")
        appendLine("""    <domain-config cleartextTrafficPermitted="false">""")
        appendLine("""        <domain includeSubdomains="false">${host.escapeXml()}</domain>""")
        appendLine("""        <pin-set expiration="${pins.expiration}">""")
        pins.digests.forEach { appendLine("""            <pin digest="SHA-256">$it</pin>""") }
        appendLine("        </pin-set>")
        appendLine("    </domain-config>")
        appendLine("</network-security-config>")
    }

    /** Manifest overlay that switches the generated config on (merged into the variant's manifest). */
    const val MANIFEST_OVERLAY = """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated (build-logic ApiPins, FIN-121): only in variants with API pins. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/network_security_config" />
</manifest>
"""

    private fun digest(pin: String): String {
        if (!pin.startsWith(PREFIX)) invalid("pin \"$pin\" must start with $PREFIX")
        val value = pin.removePrefix(PREFIX)
        val bytes = try {
            Base64.getDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (value.length != 44 || bytes == null || bytes.size != SHA256_BYTES) {
            invalid("pin \"$pin\" is not a base64 SHA-256 digest (44 characters)")
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun invalid(reason: String): Nothing = throw IllegalArgumentException("API pins: $reason")

    private fun String.escapeXml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/**
 * Today's date for the configuration: a value source, so Gradle checks it again before reusing a configuration cache
 * entry and configures anew on another day — [ApiPins.parse] then runs against the real build date.
 */
abstract class BuildDate : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String = LocalDate.now().toString()
}

/** Writes [ApiPins.MANIFEST_OVERLAY] for `variant.sources.manifests.addGeneratedManifestFile`. */
abstract class GeneratePinningManifest : DefaultTask() {
    @get:OutputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun generate() {
        manifest.get().asFile.apply { parentFile.mkdirs() }.writeText(ApiPins.MANIFEST_OVERLAY)
    }
}
