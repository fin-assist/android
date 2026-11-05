package ru.finassist.pf.e2e

import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.mock.MockConsentScenarios

/** Closed, bounded consent fixtures and read-only evidence checks; included only in mockE2e. */
internal class E2eConsentControl(private val consents: MockConsentScenarios) {
    suspend fun apply(parameters: Map<String, List<String>>): String? {
        if (parameters.isEmpty()) return null
        return try {
            require(parameters.keys.all { it in KEYS }) { "Unknown consent control" }
            require(parameters.values.all { it.size == 1 && it.single().length <= 8_192 }) { "Invalid consent parameter" }
            val values = parameters.mapValues { it.value.single() }
            val type = when (values["consent_type"]) {
                "personal_data" -> ConsentType.PERSONAL_DATA
                "assistant" -> ConsentType.ASSISTANT
                else -> throw IllegalArgumentException("Consent type is required")
            }
            // Validate the entire command before changing server state.
            values.forEach { (key, value) ->
                when (key) {
                    "consent_publish" -> require(validVersion(value)) { "Invalid consent version" }
                    "consent_document" -> require(value == "current" || validVersion(value)) { "Invalid document version" }
                    "consent_read" -> require(value in READ_MODES) { "Invalid consent read mode" }
                    "consent_retry_s" -> require(value.toIntOrNull() in 1..60) { "Invalid consent retry duration" }
                    "consent_release", "expect_consent_wait_elapsed" -> require(value == "true") { "Invalid consent predicate" }
                    "consent_register_error" -> require(type == ConsentType.PERSONAL_DATA && value in REGISTER_ERRORS) { "Invalid registration fixture" }
                    in COUNTS -> require(value.toIntOrNull() in 0..128) { "Invalid consent count" }
                    in VERSIONS -> require(versions(value).let { it.size <= 128 && it.all(::validVersion) }) { "Invalid consent evidence" }
                }
            }
            values["consent_publish"]?.let { consents.publish(type, it) }
            if (values.keys.any { it in DOCUMENT_SETTINGS }) {
                val before = consents.documentSettings(type)
                consents.setDocument(
                    type,
                    versionOverride = if ("consent_document" in values) values["consent_document"].takeUnless { it == "current" } else before.versionOverride,
                    mode = values["consent_read"]?.let { MockConsentScenarios.DocumentMode.valueOf(it.uppercase()) } ?: before.mode,
                    retrySeconds = values["consent_retry_s"]?.toInt() ?: before.retrySeconds,
                    retryable = before.retryable,
                )
            }
            values["consent_register_error"]?.let {
                consents.setRegisterRejection(when (it) {
                    "outdated" -> MockConsentScenarios.RegisterRejection.OUTDATED
                    "required" -> MockConsentScenarios.RegisterRejection.REQUIRED
                    else -> null
                })
            }
            if (values["consent_release"] == "true") consents.releaseDocuments(type)
            val evidence = consents.snapshot()
            require(!evidence.overflow) { "Consent evidence limit exceeded" }
            val document = if (type == ConsentType.PERSONAL_DATA) evidence.personalData else evidence.assistant
            val counts = mapOf(
                "expect_consent_reads" to document.reads,
                "expect_consent_active" to document.activeReads,
                "expect_consent_max_active" to document.maxConcurrentReads,
                "expect_consent_start_keys" to evidence.startKeyCount,
            )
            counts.forEach { (key, actual) -> values[key]?.let { require(actual == it.toInt()) { "Consent count mismatch: $key" } } }
            val requests = mapOf(
                "expect_consent_starts" to evidence.startVersions,
                "expect_consent_registers" to evidence.registerVersions,
                "expect_consent_grants" to evidence.grantVersions,
            )
            requests.forEach { (key, actual) -> values[key]?.let { require(actual == versions(it)) { "Consent request mismatch: $key" } } }
            if (values["expect_consent_wait_elapsed"] == "true") {
                require(document.waitRemainingMillis == 0L) { "Consent read wait has not elapsed" }
            }
            null
        } catch (e: IllegalArgumentException) {
            e.message ?: "Invalid consent control"
        }
    }

    private fun versions(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(',')
    private fun validVersion(value: String): Boolean = value.length in 1..64 && value.all { it.isLetterOrDigit() && it.code < 128 || it in "._-" }

    private companion object {
        val READ_MODES = setOf("ready", "offline", "rate_limited", "held")
        val REGISTER_ERRORS = setOf("outdated", "required", "off")
        val COUNTS = setOf("expect_consent_reads", "expect_consent_active", "expect_consent_max_active", "expect_consent_start_keys")
        val VERSIONS = setOf("expect_consent_starts", "expect_consent_registers", "expect_consent_grants")
        val DOCUMENT_SETTINGS = setOf("consent_document", "consent_read", "consent_retry_s")
        val KEYS = COUNTS + VERSIONS + DOCUMENT_SETTINGS + setOf("consent_type", "consent_publish", "consent_release", "consent_register_error", "expect_consent_wait_elapsed")
    }
}
