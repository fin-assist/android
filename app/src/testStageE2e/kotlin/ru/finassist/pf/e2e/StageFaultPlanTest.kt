package ru.finassist.pf.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StageFaultPlanTest {
    @Test
    fun `instrumented Android runtime never writes coverage into its working directory`() {
        val settings = java.util.Properties()
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("jacoco-agent.properties")).use(settings::load)
        assertEquals("none", settings.getProperty("output"))
    }

    @Test
    fun `controls reject arbitrary data and unbounded faults`() {
        listOf("call_ttl_ms" to "10", "assistant_model_errors" to "1", "url" to "https://other.example", "offline" to "yes", "network_delay_ms" to "60001", "hold_ask_response_ms" to "60001", "hold_ask_response_ms" to "-1", "stream_drops" to "101", "error_operation" to "profile_delete").forEach { (name, value) ->
            assertFailsWith<IllegalArgumentException> { StageFaultPlan.validate(name, value) }
        }
        StageFaultPlan.validate("offline", "false")
        StageFaultPlan.validate("stream_drops", "100")
        StageFaultPlan.validate("call_delay_ms", "60000")
        StageFaultPlan.validate("hold_ask_response_ms", "60000")
        StageFaultPlan.validate("hold_ask_response_ms", "0")
    }

    @Test
    fun `operation matching does not include other endpoints or verbs`() {
        assertEquals("assistant_ask", StageFaultPlan.operation("POST", "/v1/assistant/messages"))
        assertEquals("phone_start", StageFaultPlan.operation("POST", "/v1/auth/phone"))
        assertNull(StageFaultPlan.operation("GET", "/v1/assistant/messages"))
        assertNull(StageFaultPlan.operation("DELETE", "/v1/profile"))
        assertNull(StageFaultPlan.operation("PATCH", "/v1/profile/other"))
    }

    @Test
    fun `closed error fixtures preserve wire status and retry restriction`() {
        val refused = StageFaultPlan.error("retry_forbidden")
        assertEquals(429, refused.first)
        val error = Json.parseToJsonElement(refused.second).jsonObject.getValue("error").jsonObject
        assertEquals("RATE_LIMITED", error.getValue("code").jsonPrimitive.content)
        assertEquals("false", error.getValue("retryable").jsonPrimitive.content)
        StageFaultPlan.scenarios.forEach {
            val parsed = ru.finassist.pf.core.api.ApiJson.decodeFromString(
                ru.finassist.pf.core.api.model.ErrorResponse.serializer(), StageFaultPlan.error(it).second,
            )
            kotlin.test.assertTrue(parsed.error.message.isNotBlank())
        }
        assertFailsWith<IllegalStateException> { StageFaultPlan.error("arbitrary") }
    }

    @Test
    fun `run receipt rejects ordinary numbers and noncanonical IDs`() {
        StageRun("11111111-2222-4333-8444-555555555555", "+70000001234").validated()
        assertFailsWith<IllegalArgumentException> { StageRun("11111111-2222-4333-8444-555555555555", "+79161234567").validated() }
        assertFailsWith<IllegalArgumentException> { StageRun("1-1-1-1-1", "+70000001234").validated() }
    }
}
