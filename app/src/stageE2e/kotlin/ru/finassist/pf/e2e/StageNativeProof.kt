package ru.finassist.pf.e2e

import kotlinx.serialization.json.*
import java.util.UUID

/** The only projection of private native-test evidence allowed into test-run output. */
object StageNativeProof {
    fun project(raw: String, expectedRunId: String): String {
        require(runCatching { UUID.fromString(expectedRunId).toString() }.getOrNull() == expectedRunId) { "Invalid native run ID" }
        require(raw.length in 1..8192) { "Invalid native evidence size" }
        val source = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("Invalid native evidence document")
        require((source["run_id"] as? JsonPrimitive)?.contentOrNull == expectedRunId) { "Native evidence belongs to another run" }
        val injected = source["has_client_injections"] as? JsonPrimitive
        require(injected != null && !injected.isString && injected.booleanOrNull == false) { "Native picker requires real stage traffic" }
        val counter = (source["counts"] as? JsonObject)?.get("real_requests") as? JsonPrimitive
        val requests = counter?.takeUnless { it.isString }?.intOrNull
        require(requests != null && requests > 0) { "Native test has no real transport evidence" }
        // Build a new allowlisted object; never copy arbitrary keys, identities, receipts or payloads.
        return buildJsonObject {
            put("schema_version", 1)
            put("run_id", expectedRunId)
            put("has_client_injections", false)
            put("counts", buildJsonObject { put("real_requests", requests) })
        }.toString()
    }
}
