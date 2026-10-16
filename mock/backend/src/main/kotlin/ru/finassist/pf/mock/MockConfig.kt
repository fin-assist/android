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
    /** Send codes the client does not know ([MockCodes]) where the contract allows new ones (FIN-83). */
    val unknownCodes: Boolean = false,
    /** Ids of Analytics blocks that fail to build: they come as `unavailable` (FIN-5), the rest of the screen stays. */
    val failingBlocks: Set<String> = emptySet(),
    /** Minimum supported client version, `MAJOR.MINOR.PATCH`; below it every request gets `CLIENT_VERSION_UNSUPPORTED` (FIN-20). */
    val minVersion: String? = null,
    /** Maintenance: every request gets 503 `MAINTENANCE` (FIN-20). [maintenanceUntil] — its expected end, null — unknown. */
    val maintenance: Boolean = false,
    val maintenanceUntil: java.time.OffsetDateTime? = null,
    /** `Retry-After` of the maintenance answer, seconds. */
    val maintenanceRetryAfterSeconds: Int = 15,
) {
    companion object {
        /** Git-ignored: `app/src/mock/assets/private/statement.ofx`. */
        const val PRIVATE_STATEMENT = "private/statement.ofx"
        const val FIXTURE_STATEMENT = "statements/fixture.ofx"
    }
}
