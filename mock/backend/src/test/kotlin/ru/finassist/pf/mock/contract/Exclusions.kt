package ru.finassist.pf.mock.contract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Known mismatches between the fake backend and the pinned contract (FIN-123). Every entry names why it is not fixed
 * here and the issue that fixes it (proposed in the PR of FIN-123 until it gets a key); the tests fail when an entry
 * stops matching, so the list only shrinks. Adding an entry is a review finding unless the PR explains it.
 */
internal object Exclusions {

    /**
     * A validation message expected in the check of [case]: its text contains [match], its JSON pointer matches
     * [pointer], and the body's value at that pointer passes [allows]. Anything else at that place still fails.
     */
    data class Schema(
        val case: String,
        val match: String,
        val pointer: Regex,
        val allows: (JsonElement?) -> Boolean,
        val reason: String,
        val issue: String,
    ) {
        fun covers(problem: String, body: JsonElement?): Boolean {
            if (match !in problem) return false
            val where = problem.split(" | ").getOrNull(1) ?: return false
            return pointer.matches(where) && allows(body?.let { at(it, where) })
        }

        private fun at(json: JsonElement, pointer: String): JsonElement? =
            pointer.split('/').drop(1).fold(json as JsonElement?) { node, key ->
                when (node) {
                    is JsonObject -> node[key]
                    is JsonArray -> key.toIntOrNull()?.let { node.getOrNull(it) }
                    else -> null
                }
            }
    }

    /** A code of section 8 the fake backend cannot return, so no case reproduces it. */
    data class Code(val code: String, val reason: String, val issue: String)

    /** A behaviour of the contract the fake backend does not follow; its test runs through [ContractCheck.knownGap]. */
    data class Behaviour(val id: String, val reason: String, val issue: String)

    private const val SPOKEN =
        "core/api has one Account DTO for both schemas: `Account` of a list item has no `spoken`, only `AccountDetails` " +
            "(4.2) has it; the list item carries `\"spoken\": null` on the wire. Splitting the DTO changes the operations " +
            "feature, not the fake backend"
    private const val ISSUE_SPOKEN = "proposed: [Android] core/api: AccountDetails with `spoken` for details, Account without it for lists"
    private const val SPOKEN_MATCH = "not allowed by the schema: [\"spoken\"]"

    /** Only `spoken: null`, only in the account objects of a list item. */
    private val SPOKEN_AT = Regex("""/items/\d+/(account|from_account|to_account)""")
    private val SPOKEN_NULL: (JsonElement?) -> Boolean = { it is JsonObject && it.containsKey("spoken") && it["spoken"] is JsonNull }

    val SPOKEN_CASES = listOf("listOperations: feed", "listOperations: feed, older page", "listOperations: search by text", "listOperations: search, all time")

    val schema: List<Schema> = SPOKEN_CASES.map { Schema(it, SPOKEN_MATCH, SPOKEN_AT, SPOKEN_NULL, SPOKEN, ISSUE_SPOKEN) }

    private const val ISSUE_FAILURES =
        "proposed: [Android] fake backend: switches for server-side failures (INTERNAL_ERROR, PROCESSING_FAILED) and request rate " +
            "limits (RATE_LIMITED of 1.1 «номер занят» and of plain calls; only the 429 of a stream re-open exists, FIN-170)"

    val codes: List<Code> = listOf(
        Code(
            "TOKEN_EXPIRED",
            "a registration token of the fake backend never expires (api.md 1.3: 15 minutes after the call); the consent screen " +
                "already turns any 401 into «start again», the new path needs its UI flow",
            "proposed: [Android] fake backend: registration token expires after 15 minutes (TOKEN_EXPIRED)",
        ),
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
            "uploadStatement does not compare the body of a repeat: the same key with another file " +
                "gets the stored response, not 409 IDEMPOTENCY_CONFLICT (auth and assistant endpoints compare bodies); $FIN_170_FILES",
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
