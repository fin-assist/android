package ru.finassist.pf.mock

/**
 * Knobs of the in-process backend. Changed from the debug screen or tests; defaults make the app feel like a
 * real network without being annoying.
 */
data class MockConfig(
    /** The only phone number that "already has an account". Any other number is a new user. */
    val existingPhone: String = "+79161234567",
    /** How long after "Позвонить" the verification completes. */
    val callVerifyDelayMs: Long = 5_000,
    /** Lifetime of a phone verification request (`expires_at` − creation; 5 minutes, as the provider's, api.md 1.1). */
    val verificationTtlMs: Long = 300_000,
    /** Simulated latency of every request. */
    val networkDelayMs: Long = 250,
    /** Pause between import stages (parsing → dedup → rules). */
    val importStepDelayMs: Long = 600,
    /** Pause between streamed text chunks of an assistant answer. */
    val assistantChunkDelayMs: Long = 40,
    /** Load the bundled (or private) statement as the first upload on start. */
    val preloadStatement: Boolean = true,
    /** Assets tried for the preload, first found wins: your private statement, then the anonymized fixture. */
    val statementAssets: List<String> = listOf(PRIVATE_STATEMENT, FIXTURE_STATEMENT),
    /** Supported file size limit, as the server config reports it. */
    val maxFileSizeBytes: Long = 10L * 1024 * 1024,
    /** When true every request fails with Offline — to check "нет сети" states. */
    val offline: Boolean = false,
    /**
     * How many of the next assistant answers fail with MODEL_ERROR (refunded, «Повторить» offered); each failure
     * counts it down. The engine itself never fails like a model — this is how the retry path is reached.
     */
    val assistantModelErrors: Int = 0,
    /** Next n valid analytics reads return no projection; exercises publication lag after an actual UI import. */
    val analyticsPendingResponses: Int = 0,
    /** Next n valid non-empty analytics responses carry the existing recalculation marker (UI fixture only). */
    val analyticsRecalculatingResponses: Int = 0,
    /** Send codes the client does not know ([MockCodes]) where the contract allows new ones (FIN-83). */
    val unknownCodes: Boolean = false,
    /** Ids of Analytics blocks that fail to build: they come as `unavailable` (FIN-5), the rest of the screen stays. */
    val failingBlocks: Set<String> = emptySet(),
    /** A parse not finished this long after its start ends with `PROCESSING_FAILED` (api.md 3.3: 3 minutes). */
    val importTimeoutMs: Long = 180_000,
    /** Generation of an answer not finished this long after its start ends with `MODEL_ERROR` (api.md 7.2: 3 minutes). */
    val answerTimeoutMs: Long = 180_000,
    /**
     * How many of the next stream openings (1.2, 3.3, 7.2 alike) close right after their first event without a
     * terminal one — what the server does on its own failure (api.md «SSE»); the client re-opens them. An opening whose
     * first event is already terminal is not dropped and does not count.
     */
    val streamDrops: Int = 0,
    /**
     * How many stream openings that follow a dropped one ([streamDrops]) answer 429 `RATE_LIMITED` before the stream
     * starts, with `retry_at` [streamRetrySeconds] ahead — a re-open the server turns away (api.md «SSE»). An opening
     * that does not follow a drop is never turned away and does not count.
     */
    val streamRateLimits: Int = 0,
    /** `Retry-After` of a turned-away re-open ([streamRateLimits]); a long one keeps the client waiting for a UI test. */
    val streamRetrySeconds: Long = STREAM_RETRY_SECONDS,
    /** Minimum supported client version, `MAJOR.MINOR.PATCH`; below it every request and stream opening gets `CLIENT_VERSION_UNSUPPORTED` (FIN-20). */
    val minVersion: String? = null,
    /**
     * Maintenance: every request and every stream opening gets 503 `MAINTENANCE`, a stream already open is cut at its next
     * event (FIN-20, api.md «SSE»). [maintenanceUntil] — its expected end, null — unknown.
     */
    val maintenance: Boolean = false,
    val maintenanceUntil: java.time.OffsetDateTime? = null,
    /** `Retry-After` of the maintenance answer, seconds. */
    val maintenanceRetryAfterSeconds: Int = 15,
    /** A targeted error after authentication/availability checks; unrelated calls remain usable. */
    val errorOperation: MockErrorOperation? = null,
    val errorScenario: MockErrorScenario = MockErrorScenario.UNKNOWN_CODE,
) {
    companion object {
        /** Git-ignored: `app/src/mock/assets/private/statement.ofx`. */
        const val PRIVATE_STATEMENT = "private/statement.ofx"
        const val FIXTURE_STATEMENT = "statements/fixture.ofx"
        /** Default of [streamRetrySeconds]. */
        const val STREAM_RETRY_SECONDS = 3L
    }
}
