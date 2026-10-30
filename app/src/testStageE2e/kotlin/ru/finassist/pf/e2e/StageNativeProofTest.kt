package ru.finassist.pf.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.*

class StageNativeProofTest {
    private val run = "11111111-2222-4333-8444-555555555555"
    private val evidence = """{"run_id":"$run","has_client_injections":false,"counts":{"real_requests":8,"financial_secret_as_key":5},"phone":"+70000001234","user_id":"private-user","access_token":"private-token","successful_registrations":[{"phone":"+70000001234","user_id":"private-user"}]}"""

    @Test
    fun `native artifact exports only matching run and real request count`() {
        val proof = StageNativeProof.project(evidence, run)
        val value = Json.parseToJsonElement(proof).jsonObject
        assertEquals(setOf("schema_version", "run_id", "has_client_injections", "counts"), value.keys)
        assertEquals(setOf("real_requests"), value.getValue("counts").jsonObject.keys)
        assertFalse(proof.contains("+7000000"))
        assertFalse(proof.contains("private"))
        assertFalse(proof.contains("financial_secret"))
        assertFalse(proof.contains("registrations"))
    }

    @Test
    fun `foreign injected empty invalid or absent real evidence never produces a native proof`() {
        for (raw in listOf(
            evidence.replace(run, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
            evidence.replace("false", "true"),
            evidence.replace("false", "\"false\""),
            evidence.replace("\"real_requests\":8", "\"real_requests\":0"),
            evidence.replace("\"real_requests\":8", "\"real_requests\":-1"),
            evidence.replace("\"real_requests\":8", "\"real_requests\":\"not-a-counter\""),
            evidence.replace("\"real_requests\":8", "\"real_requests\":\"8\""),
            "{}",
        )) {
            assertFailsWith<IllegalArgumentException> { StageNativeProof.project(raw, run) }
        }
    }

    @Test
    fun `malformed private document is rejected without echoing its contents`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            StageNativeProof.project("{\"access_token\":\"private-token\",", run)
        }
        assertEquals("Invalid native evidence document", failure.message)
    }
}
