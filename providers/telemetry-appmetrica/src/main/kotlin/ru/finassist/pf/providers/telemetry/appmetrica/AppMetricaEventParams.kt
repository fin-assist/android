package ru.finassist.pf.providers.telemetry.appmetrica

import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.toggles.Experiment
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.Events

/** The SDK receives only the technical dimensions declared for the event. */
internal fun appMetricaEventParams(event: Event, params: Map<String, String>): Map<String, String> = when (event) {
    Events.APPLOCK_UNLOCK_FAILED -> params.count("attempt")
    Events.STATEMENTS_UPLOAD_COMPLETED -> params.count("new_count")
    Events.STATEMENTS_UPLOAD_FAILED -> params.code("code", uploadErrorCodes)
    Events.STATEMENTS_UPLOAD_INTERRUPTED -> params.code("reason", interruptionReasons)
    Events.OPERATIONS_SEARCH_SUBMITTED -> params.count("count")
    Events.OPERATIONS_CATEGORY_CHANGED -> params.code("split", booleanValues)
    Events.ANALYTICS_PERIOD_CHANGED -> params.code("period", periodCodes)
    Events.ANALYTICS_TRANSFERS_CHANGED -> params.code("mode", transferModes)
    Events.ANALYTICS_TILE_OPENED -> params.code("block", tileIds)
    Events.ANALYTICS_BLOCK_EXPOSED -> buildMap {
        // Block IDs are supplied by the server. Do not transmit an arbitrary ID as a dimension.
        params["block"]?.let { put("block", it.takeIf(analyticsBlocks::contains) ?: "unknown") }
        params["exp"]?.takeIf(::validExperimentVariants)?.let { put("exp", it) }
    }
    Events.ASSISTANT_ANSWER_DONE -> params.code("charged", booleanValues)
    Events.ASSISTANT_ANSWER_FAILED -> params.code("code", answerErrorCodes)
    Events.PROFILE_THEME_CHANGED -> params.code("theme", themeCodes)
    else -> emptyMap()
}

private fun Map<String, String>.count(key: String): Map<String, String> =
    this[key]?.toIntOrNull()?.takeIf { it >= 0 }?.let { mapOf(key to it.toString()) }.orEmpty()

private fun Map<String, String>.code(key: String, allowed: Set<String>): Map<String, String> =
    this[key]?.takeIf(allowed::contains)?.let { mapOf(key to it) }.orEmpty()

private fun validExperimentVariants(value: String): Boolean {
    if (value.length > maxExperimentVariantsLength) return false
    val variants = value.split(',', limit = Experiment.entries.size + 1)
    return variants.size == Experiment.entries.size && variants.zip(Experiment.entries).all { (entry, experiment) ->
        entry.substringBefore(':') == experiment.key && entry.substringAfter(':', "") in experiment.variants
    }
}

private val maxExperimentVariantsLength =
    Experiment.entries.sumOf { it.key.length + 1 + it.variants.maxOf(String::length) } +
        (Experiment.entries.size - 1).coerceAtLeast(0)

private val uploadErrorCodes = setOf(
    ErrorCodes.FILE_TOO_LARGE,
    ErrorCodes.CSV_NOT_ACCEPTED,
    ErrorCodes.WRONG_FORMAT,
    ErrorCodes.WRONG_BANK,
    ErrorCodes.VALIDATION_ERROR,
    ErrorCodes.NOT_FOUND,
    ErrorCodes.PROCESSING_FAILED,
    ErrorCodes.IDEMPOTENCY_CONFLICT,
    ErrorCodes.REQUEST_IN_PROGRESS,
    ErrorCodes.RATE_LIMITED,
    ErrorCodes.INTERNAL_ERROR,
    "OFFLINE",
)
private val interruptionReasons = setOf("maintenance", "update_required")
private val booleanValues = setOf("false", "true")
private val periodCodes = PeriodTypeCode.entries.map { it.code }.toSet()
private val transferModes = TransferMode.entries.map { it.code }.toSet()
private val tileIds = setOf("tiles")
private const val ANALYTICS_BLOCK_PREFIX = "analytics.block."
private val analyticsBlocks = Flag.entries.filter { it.key.startsWith(ANALYTICS_BLOCK_PREFIX) }
    .mapTo(mutableSetOf("balance")) { it.key.removePrefix(ANALYTICS_BLOCK_PREFIX) }
private val answerErrorCodes = AnswerErrorCode.entries.map { it.code }.toSet()
private val themeCodes = Theme.entries.map { it.code }.toSet()
