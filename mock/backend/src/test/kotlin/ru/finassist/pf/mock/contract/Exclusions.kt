package ru.finassist.pf.mock.contract

/**
 * Known mismatches between the fake backend and the pinned contract (FIN-123). Every entry names why it is not fixed
 * here and the issue that fixes it (proposed in the PR of FIN-123 until it gets a key); the tests fail when an entry
 * stops matching, so the list only shrinks. Adding an entry is a review finding unless the PR explains it.
 */
internal object Exclusions {

    /** A validation message [match] (a substring) expected in the check of [case]. */
    data class Schema(val case: String, val match: String, val reason: String, val issue: String)

    /** A code of section 8 the fake backend cannot return, so no case reproduces it. */
    data class Code(val code: String, val reason: String, val issue: String)

    /** A behaviour of the contract the fake backend does not follow; its test runs through [ContractCheck.knownGap]. */
    data class Behaviour(val id: String, val reason: String, val issue: String)

    val schema: List<Schema> = listOf()

    private const val FIN_22 =
        "the fake backend of release/0.2.8 has no version check and no maintenance mode: FIN-22 added both to the mock, but it is " +
            "in release/0.2.7, which is not merged into main and release/0.2.8 yet"
    private const val ISSUE_FIN_22 = "FIN-22 (after release/0.2.7 reaches this branch: a case per code, the exclusion removed)"
    private const val ISSUE_FAILURES = "proposed: [Android] fake backend: switches for server-side failures (RATE_LIMITED, INTERNAL_ERROR, PROCESSING_FAILED)"

    val codes: List<Code> = listOf(
        Code(
            "CLIENT_VERSION_UNSUPPORTED",
            "the in-process fake backend sees no headers, `X-Client-Version` included; " + FIN_22,
            ISSUE_FIN_22,
        ),
        Code("MAINTENANCE", FIN_22, ISSUE_FIN_22),
        Code(
            "TOKEN_EXPIRED",
            "a registration token of the fake backend never expires (api.md 1.3: 15 minutes after the call); the consent screen " +
                "already turns any 401 into «start again», the new path needs its UI flow",
            "proposed: [Android] fake backend: registration token expires after 15 minutes (TOKEN_EXPIRED)",
        ),
        Code("RATE_LIMITED", "the fake backend has no request rate limits (1.1 «номер занят», 1.2 reopening)", ISSUE_FAILURES),
        Code("INTERNAL_ERROR", "the fake backend never fails with 500; `offline` is its only failure switch", ISSUE_FAILURES),
        Code(
            "PROCESSING_FAILED",
            "no statement fails to import in the fake backend: a file that passes the checks of 3.2 always imports; the 3-minute " +
                "timeout of 3.3 is FIN-170",
            "FIN-170 (import timeout) or $ISSUE_FAILURES",
        ),
    )

    private const val FIN_170_FILES = "MockStatementsApi and MockAssistantApi are being changed in FIN-170; not touched here to avoid a conflict"

    val behaviours: List<Behaviour> = listOf(
        Behaviour(
            "retry-error-order",
            "retryAnswer checks RETRY_NOT_ALLOWED (422) before the consent and the limit (403), api.md «Ошибки» has 403 first; $FIN_170_FILES",
            "proposed: [Android] fake backend: idempotency and error order of statements and assistant",
        ),
        Behaviour(
            "idempotency-conflict-statements-assistant",
            "uploadStatement, askAssistant, retryAnswer do not compare the body of a repeat: the same key with another file or question " +
                "gets the stored response, not 409 IDEMPOTENCY_CONFLICT (auth endpoints do, FIN-123); $FIN_170_FILES",
            "proposed: [Android] fake backend: idempotency and error order of statements and assistant",
        ),
        Behaviour(
            "idempotency-stored-errors",
            "the fake backend stores only 2xx under a key; api.md «Идемпотентность» stores 400, 413 and 422 too. Safe only with the body " +
                "comparison of the entry above (a stored 422 must not answer another file)",
            "proposed: [Android] fake backend: idempotency and error order of statements and assistant",
        ),
        Behaviour(
            "delete-unknown-upload",
            "deleteStatement of an upload the user never had is 204, api.md 3.5 says 404 (204 only for an upload deleted before); $FIN_170_FILES",
            "proposed: [Android] fake backend: idempotency and error order of statements and assistant",
        ),
        Behaviour(
            "stream-session",
            "the progress (3.3) and answer (7.2) streams do not check the session: no 401 INVALID_TOKEN before the stream; $FIN_170_FILES",
            "proposed: [Android] fake backend: idempotency and error order of statements and assistant",
        ),
        Behaviour(
            "logout-delete-session",
            "logout and account deletion answer 204 without a session: the fake backend keeps no access tokens, and a repeat of the " +
                "deletion (2.5) must stay 204 after the profile is gone",
            "proposed: [Android] fake backend: access tokens, 401 for logout and account deletion",
        ),
    )
}
