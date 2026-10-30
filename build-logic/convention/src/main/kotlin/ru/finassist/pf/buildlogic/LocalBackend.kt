package ru.finassist.pf.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.net.URI
import java.net.URISyntaxException
import java.time.LocalDate

/**
 * Backend of the `local` flavor (FIN-120): a bank-bff the developer runs on their machine. The address comes from the
 * Gradle property `pf.apiBaseUrl` (`~/.gradle/gradle.properties`, or `PF_API_BASE_URL`); without it, bank-bff on the
 * host machine as the Android emulator sees it.
 */
object LocalBackend {
    /**
     * Port bank-bff listens on locally. fin-assist/contracts does not fix one yet (its `servers` list only the
     * production placeholder); 8080 until the backend says otherwise — the one place to change it.
     */
    const val DEFAULT_PORT = 8080

    /** The host machine's loopback as the Android emulator sees it. */
    const val EMULATOR_HOST = "10.0.2.2"

    const val DEFAULT_BASE_URL = "http://$EMULATOR_HOST:$DEFAULT_PORT/"

    /** A checked base URL: [url] is what Retrofit gets (always ends with `/`), [cleartext] — plain HTTP. */
    data class BaseUrl(val url: String, val host: String, val cleartext: Boolean)

    /**
     * Checks and normalizes `pf.apiBaseUrl`; blank → [DEFAULT_BASE_URL]. Accepts `http` and `https` with a host and an
     * optional port (1..65535, as OkHttp) and path; the path gets a trailing `/` (Retrofit requires it). Credentials,
     * a query or a fragment are rejected: they would end up in `BuildConfig` or be dropped silently.
     */
    fun parse(raw: String?): BaseUrl {
        val value = raw?.trim().orEmpty().ifEmpty { DEFAULT_BASE_URL }
        val uri = try {
            URI(value)
        } catch (e: URISyntaxException) {
            invalid(value, e.reason)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") invalid(value, "the scheme must be http or https")
        val host = uri.host?.lowercase() ?: invalid(value, "no host")
        if (uri.rawUserInfo != null) invalid(value, "credentials are not allowed")
        if (uri.rawQuery != null || uri.rawFragment != null) invalid(value, "a query or a fragment is not allowed")
        // `URI` takes any number; OkHttp (`toHttpUrl()` at app start) only 1..65535.
        if (uri.port != -1 && uri.port !in 1..65535) invalid(value, "the port must be 1..65535")
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().let { if (it.endsWith("/")) it else "$it/" }
        return BaseUrl(url = "$scheme://$host$port$path", host = host.removeSurrounding("[", "]"), cleartext = scheme == "http")
    }

    /**
     * Network security config of `localDebug`: cleartext stays forbidden everywhere except the one host of the local
     * backend, and only when it is plain HTTP. No other variant gets a config (and so no exception).
     */
    fun networkSecurityConfig(baseUrl: BaseUrl): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("<!-- Generated from pf.apiBaseUrl (build-logic LocalBackend, FIN-120): localDebug only. -->")
        appendLine("<network-security-config>")
        appendLine("""    <base-config cleartextTrafficPermitted="false" />""")
        if (baseUrl.cleartext) {
            appendLine("""    <domain-config cleartextTrafficPermitted="true">""")
            appendLine("""        <domain includeSubdomains="false">${baseUrl.host.escapeXml()}</domain>""")
            appendLine("    </domain-config>")
        }
        appendLine("</network-security-config>")
    }

    private fun invalid(value: String, reason: String): Nothing =
        throw IllegalArgumentException("pf.apiBaseUrl \"$value\": $reason (expected like $DEFAULT_BASE_URL)")

    private fun String.escapeXml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/**
 * Writes [config] as `xml/network_security_config.xml` into a generated res directory: the config of `localDebug`
 * ([LocalBackend.networkSecurityConfig]) or of a variant with API pins ([ApiPins.networkSecurityConfig]).
 */
abstract class GenerateNetworkSecurityConfig : DefaultTask() {
    @get:Input
    abstract val config: Property<String>

    /**
     * Expiration of the pins in [config], when it has any. The task then never counts as up to date and checks the date
     * against the day it runs ([ApiPins.requireActive]), so no build writes an expired pin set.
     */
    @get:Input
    @get:Optional
    abstract val pinsExpiration: Property<String>

    /** Property name used in expiration diagnostics, including a re-check after configuration. */
    @get:Input
    abstract val pinsExpirationProperty: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        pinsExpirationProperty.convention(ApiPins.EXPIRATION_PROPERTY)
        outputs.upToDateWhen { !pinsExpiration.isPresent }
    }

    @TaskAction
    fun generate() = generate(LocalDate.now())

    internal fun generate(today: LocalDate) {
        pinsExpiration.orNull?.let { ApiPins.requireActive(LocalDate.parse(it), today, pinsExpirationProperty.get()) }
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        val file = dir.resolve("xml/network_security_config.xml")
        file.parentFile.mkdirs()
        file.writeText(config.get())
    }
}
