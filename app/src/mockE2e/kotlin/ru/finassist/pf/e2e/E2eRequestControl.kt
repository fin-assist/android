package ru.finassist.pf.e2e

import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.MockUiEvidence

/** Closed read-only checks of actual API attempts/history; no user text, opaque keys or identifiers are returned. */
internal class E2eRequestControl(private val backend: MockBackend) {
    suspend fun apply(parameters: Map<String, List<String>>): String? {
        if (parameters.isEmpty()) return null
        return try {
            require(parameters.keys.all { it in COUNTS || it in PREDICATES || it == PHONE_WAIT }) { "Unknown request evidence" }
            require(parameters.values.all { it.size == 1 && it.single().length <= MAX_VALUE_LENGTH }) { "Invalid request evidence" }
            val values = parameters.mapValues { it.value.single() }
            values.forEach { (key, value) ->
                val metric = COUNTS[key]
                if (key == PHONE_WAIT) require(WAIT_TIME.matches(value)) { "Invalid phone wait label" }
                else if (metric != null) {
                    val max = if (metric == MockUiEvidence.Metric.PHONE_TTL) MAX_TTL_SECONDS else MAX_COUNT
                    require(value.toIntOrNull() in 0..max) { "Invalid request count" }
                } else require(value == "true") { "Invalid request predicate" }
            }
            val actual = backend.mutex.withLock {
                val current = backend.assistant.answers.values.filterNot { it.replaced }
                backend.uiEvidence.snapshot(backend.assistant.questions.size, current.size,
                    current.count { it.toMessage().errorCode == AnswerErrorCode.OFF_TOPIC })
            }
            require(!actual.overflow) { "Request evidence limit exceeded" }
            values.forEach { (key, value) ->
                if (key == PHONE_WAIT) require(backend.uiEvidence.phoneWaitMatches(value)) { "Phone wait label mismatch" }
                COUNTS[key]?.let { require(actual.counts[it] == value.toInt()) { "Request count mismatch: $key" } }
                PREDICATES[key]?.let { require(actual.predicates[it] == true) { "Request predicate unmet: $key" } }
            }
            null
        } catch (error: IllegalArgumentException) {
            error.message ?: "Invalid request evidence"
        }
    }

    companion object {
        private const val MAX_COUNT = 128
        private const val MAX_TTL_SECONDS = 3_600
        private const val MAX_VALUE_LENGTH = 8
        private const val PHONE_WAIT = "expect_phone_wait_hhmm"
        private val WAIT_TIME = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
        private val COUNTS = MockUiEvidence.Metric.entries.associateBy { it.control }
        private val PREDICATES = MockUiEvidence.Predicate.entries.associateBy { it.control }
        private val PREFIXES = listOf("expect_assistant_", "expect_statement_", "expect_operation_", "expect_category_", "expect_phone_", "expect_maintenance_", "expect_analytics_")
        fun isParameter(name: String) = name == MockUiEvidence.Predicate.STREAM_NOT_EARLY.control || PREFIXES.any(name::startsWith)
    }
}
