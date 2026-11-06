package ru.finassist.pf.mock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Bounded observations of actual mock API calls. Only counts/predicates leave this private server witness. */
class MockUiEvidence {
    enum class Metric(val control: String) {
        ASKS("expect_assistant_asks"), ASK_KEYS("expect_assistant_ask_keys"),
        RETRIES("expect_assistant_retries"), RETRY_KEYS("expect_assistant_retry_keys"),
        RETRY_TARGETS("expect_assistant_retry_targets"), QUESTIONS("expect_assistant_questions"),
        ANSWERS("expect_assistant_answers"), OFF_TOPICS("expect_assistant_off_topics"),
        UPLOADS("expect_statement_upload_requests"), UPLOAD_KEYS("expect_statement_upload_keys"),
        DELETES("expect_statement_delete_requests"), DETAILS("expect_operation_details_requests"),
        CATEGORIES("expect_category_requests"), PHONE_TTL("expect_phone_ttl_seconds"),
        MAINTENANCE_PROBES("expect_maintenance_probes"),
        DISMISSALS("expect_analytics_dismiss_requests"),
    }

    enum class Predicate(val control: String) {
        DETAILS_EXACT("expect_operation_details_timestamp_exact"), CATEGORY_EXACT("expect_category_timestamp_exact"),
        PHONE_FRESH("expect_phone_fresh_deadline"), MAINTENANCE_NOT_EARLY("expect_maintenance_probe_after_deadline"),
        STREAM_NOT_EARLY("expect_stream_reopen_after_deadline"),
        UPLOAD_KEY_FRESH("expect_statement_upload_key_fresh"),
        UPLOAD_KEY_RETAINED("expect_statement_pending_key_retained"),
    }

    data class Snapshot(val counts: Map<Metric, Int>, val predicates: Map<Predicate, Boolean>, val overflow: Boolean)

    private data class State(
        val counts: Map<Metric, Int> = emptyMap(),
        val askKeys: Set<String> = emptySet(), val retryKeys: Set<String> = emptySet(),
        val retryTargets: Set<String> = emptySet(), val uploadKeys: Set<String> = emptySet(),
        val details: Map<String, String> = emptyMap(),
        val detailsChecked: Int = 0, val categoriesChecked: Int = 0,
        val detailsExact: Boolean = true, val categoryExact: Boolean = true,
        val phoneResponses: Int = 0, val phoneExpiry: Long? = null, val phoneFresh: Boolean = false,
        val phoneWaitLabels: Set<String> = emptySet(),
        val maintenanceDeadline: Long? = null, val maintenanceNotEarly: Boolean = true,
        val streamDeadline: Long? = null, val streamReopens: Int = 0, val streamNotEarly: Boolean = true,
        val restoredUploadKey: String? = null, val uploadKeyFresh: Boolean = true, val uploadKeyRetained: Boolean = false,
        val overflow: Boolean = false,
    )

    private val state = MutableStateFlow(State())

    internal fun ask(key: String) = state.update { before ->
        increment(before, Metric.ASKS).let { it.copy(askKeys = boundedAdd(it.askKeys, key), overflow = it.overflow || !fits(it.askKeys, key)) }
    }

    internal fun retry(key: String, target: String) = state.update { before ->
        increment(before, Metric.RETRIES).let { it.copy(
            retryKeys = boundedAdd(it.retryKeys, key), retryTargets = boundedAdd(it.retryTargets, target),
            overflow = it.overflow || !fits(it.retryKeys, key) || !fits(it.retryTargets, target),
        ) }
    }

    internal fun upload(key: String) = state.update { before ->
        increment(before, Metric.UPLOADS).let { it.copy(
            uploadKeys = boundedAdd(it.uploadKeys, key),
            uploadKeyFresh = it.uploadKeyFresh && key != it.restoredUploadKey,
            overflow = it.overflow || !fits(it.uploadKeys, key),
        ) }
    }

    /** The closed E2E fixture records its restored key without exposing it through control links. */
    fun restoredUploadKey(key: String) = state.update { before -> before.copy(
        restoredUploadKey = key.takeIf { it.length <= MAX_TOKEN }, uploadKeyRetained = false,
        overflow = before.overflow || key.length > MAX_TOKEN || before.restoredUploadKey != null,
    ) }

    /** Observation of the persisted record via IdempotencyKeys.pendingKey, which never creates a key. */
    fun retainedUploadKey(key: String?) = state.update { before -> before.copy(
        uploadKeyRetained = before.restoredUploadKey != null && before.restoredUploadKey == key,
    ) }

    internal fun delete() = state.update { increment(it, Metric.DELETES) }
    internal fun dismiss() = state.update { increment(it, Metric.DISMISSALS) }
    internal fun detailRequest() = state.update { increment(it, Metric.DETAILS) }
    internal fun categoryRequest() = state.update { increment(it, Metric.CATEGORIES) }

    internal fun details(id: String, requested: String, returned: String) = state.update { before ->
        val fits = id.length <= MAX_TOKEN && returned.length <= MAX_TOKEN && (id in before.details || before.details.size < MAX_EVIDENCE)
        before.copy(
            details = if (fits) before.details + (id to returned) else before.details,
            detailsChecked = before.detailsChecked + 1,
            detailsExact = before.detailsExact && requested == returned,
            overflow = before.overflow || !fits || before.detailsChecked >= MAX_EVIDENCE,
        )
    }

    internal fun category(id: String, requested: String) = state.update { before -> before.copy(
        categoriesChecked = before.categoriesChecked + 1,
        categoryExact = before.categoryExact && before.details[id] != null && before.details[id] == requested,
        overflow = before.overflow || before.categoriesChecked >= MAX_EVIDENCE,
    ) }

    internal fun phoneResult(ttlSeconds: Int, expiryMillis: Long, waitLabels: Set<String> = emptySet()) = state.update { before -> before.copy(
        counts = before.counts + (Metric.PHONE_TTL to ttlSeconds), phoneResponses = before.phoneResponses + 1,
        phoneFresh = before.phoneExpiry?.let { expiryMillis > it } == true, phoneExpiry = expiryMillis,
        phoneWaitLabels = waitLabels.filter { WAIT_TIME.matches(it) }.take(2).toSet(),
        overflow = before.overflow || before.phoneResponses >= MAX_EVIDENCE,
    ) }

    /** Only a clock label copied from the actual visible Call screen is accepted; no timestamp is returned. */
    fun phoneWaitMatches(label: String) = WAIT_TIME.matches(label) && label in state.value.phoneWaitLabels

    internal fun maintenanceRefusal(nowMillis: Long?, retrySeconds: Long) = state.update { before -> before.copy(
        maintenanceDeadline = nowMillis?.plus(retrySeconds * MILLIS_PER_SECOND),
        maintenanceNotEarly = before.maintenanceNotEarly && nowMillis != null,
    ) }

    internal fun personalDataRead(nowMillis: Long?) = state.update { before ->
        before.maintenanceDeadline?.let { deadline -> increment(before, Metric.MAINTENANCE_PROBES).copy(
            maintenanceNotEarly = before.maintenanceNotEarly && nowMillis != null && nowMillis >= deadline,
        ) } ?: before
    }

    internal fun streamRefusal(nowMillis: Long?, retrySeconds: Long) = state.update { before -> before.copy(
        streamDeadline = nowMillis?.plus(retrySeconds * MILLIS_PER_SECOND),
        streamNotEarly = before.streamNotEarly && nowMillis != null,
    ) }

    /** Every actual collection/open is observed, including a successful open after refusals are removed. */
    internal fun streamOpening(nowMillis: Long?) = state.update { before ->
        before.streamDeadline?.let { deadline -> before.copy(
            streamReopens = (before.streamReopens + 1).coerceAtMost(MAX_EVIDENCE + 1),
            streamNotEarly = before.streamNotEarly && nowMillis != null && nowMillis >= deadline,
            overflow = before.overflow || before.streamReopens >= MAX_EVIDENCE,
        ) } ?: before
    }

    /** History counts are supplied by the caller while holding MockBackend.mutex, not inferred from requests. */
    fun snapshot(questions: Int, answers: Int, offTopics: Int): Snapshot {
        val given = state.value
        val counts = Metric.entries.associateWith { given.counts[it] ?: 0 } + mapOf(
            Metric.ASK_KEYS to given.askKeys.size, Metric.RETRY_KEYS to given.retryKeys.size,
            Metric.RETRY_TARGETS to given.retryTargets.size, Metric.UPLOAD_KEYS to given.uploadKeys.size,
            Metric.QUESTIONS to questions, Metric.ANSWERS to answers, Metric.OFF_TOPICS to offTopics,
        )
        return Snapshot(counts, mapOf(
            Predicate.DETAILS_EXACT to (given.detailsChecked > 0 && given.detailsChecked == counts[Metric.DETAILS] && given.detailsExact),
            Predicate.CATEGORY_EXACT to (given.categoriesChecked > 0 && given.categoriesChecked == counts[Metric.CATEGORIES] && given.categoryExact),
            Predicate.PHONE_FRESH to (given.phoneResponses > 1 && given.phoneFresh),
            Predicate.MAINTENANCE_NOT_EARLY to ((counts[Metric.MAINTENANCE_PROBES] ?: 0) > 0 && given.maintenanceNotEarly),
            Predicate.STREAM_NOT_EARLY to (given.streamReopens > 0 && given.streamNotEarly),
            Predicate.UPLOAD_KEY_FRESH to (given.restoredUploadKey != null && given.uploadKeys.isNotEmpty() && given.uploadKeyFresh),
            Predicate.UPLOAD_KEY_RETAINED to given.uploadKeyRetained,
        ), given.overflow || listOf(questions, answers, offTopics).any { it > MAX_EVIDENCE })
    }

    private fun increment(before: State, metric: Metric): State {
        val count = (before.counts[metric] ?: 0) + 1
        return before.copy(counts = before.counts + (metric to count.coerceAtMost(MAX_EVIDENCE + 1)), overflow = before.overflow || count > MAX_EVIDENCE)
    }

    private fun fits(values: Set<String>, value: String) = value.length <= MAX_TOKEN && (value in values || values.size < MAX_EVIDENCE)
    private fun boundedAdd(values: Set<String>, value: String) = if (fits(values, value)) values + value else values

    private companion object {
        const val MAX_EVIDENCE = 128
        const val MAX_TOKEN = 256
        const val MILLIS_PER_SECOND = 1_000L
        val WAIT_TIME = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
    }
}
