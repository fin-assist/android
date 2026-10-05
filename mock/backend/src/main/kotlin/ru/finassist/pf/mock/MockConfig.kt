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
    /** Supported file size limit, as the server config reports it. */
    val maxFileSizeBytes: Long = 10L * 1024 * 1024,
    /** When true every request fails with Offline — to check "нет сети" states. */
    val offline: Boolean = false,
)
