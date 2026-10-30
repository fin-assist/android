package ru.finassist.pf.e2e

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StageAuthEvidenceTest {
    @Test
    fun `registration is bound only after a real verified new-user event is consumed`() {
        val evidence = StageAuthEvidence()
        evidence.started("+70000001234", """{"verification_token":"verification-secret"}""")
        assertFailsWith<IllegalArgumentException> { evidence.registrationPhone("""{"registration_token":"registration-secret"}""") }
        val stream = "event: status\ndata: {\"status\":\"pending\"}\n\nevent: status\ndata: {\"status\":\"verified\",\"is_new_user\":true,\"registration_token\":\"registration-secret\"}\n\n"
        evidence.observe(stream.toResponseBody(), "verification-secret").use { assertEquals(stream, it.string()) }
        assertEquals("+70000001234", evidence.registrationPhone("""{"registration_token":"registration-secret"}"""))
        assertFailsWith<IllegalArgumentException> { evidence.registrationPhone("""{"registration_token":"foreign-token"}""") }
    }

    @Test
    fun `existing-user or unrelated event never grants registration ownership`() {
        val evidence = StageAuthEvidence()
        evidence.started("+70000001234", """{"verification_token":"v"}""")
        listOf("event: other\ndata: {\"status\":\"verified\",\"is_new_user\":true,\"registration_token\":\"r\"}\n\n", "event: status\ndata: {\"status\":\"verified\",\"is_new_user\":false,\"registration_token\":\"r\"}\n\n").forEach { stream ->
            evidence.observe(stream.toResponseBody(), "v").string()
            assertFailsWith<IllegalArgumentException> { evidence.registrationPhone("""{"registration_token":"r"}""") }
        }
        assertFailsWith<IllegalArgumentException> { evidence.observe("".toResponseBody(), "unknown") }
    }

    @Test
    fun `unbounded authentication frame is refused`() {
        val evidence = StageAuthEvidence()
        evidence.started("+70000001234", """{"verification_token":"v"}""")
        assertFailsWith<IOException> { evidence.observe(("data: " + "a".repeat(65537)).toResponseBody(), "v").string() }
    }
    @Test
    fun `malformed receipts do not echo server secrets in failures`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            StageAuthEvidence().registeredUserId("{\"access_token\":\"private-secret\",")
        }
        assertEquals("Malformed authentication receipt", failure.message)
    }

    @Test
    fun `new candidate refuses existing or unknown user events before application consumes session tokens`() {
        for (newField in listOf("false", "null")) {
            val evidence = StageAuthEvidence()
            evidence.started("+70000001234", """{"verification_token":"v"}""")
            val stream = "event: status\ndata: {\"status\":\"verified\",\"is_new_user\":$newField,\"access_token\":\"foreign-access\",\"refresh_token\":\"foreign-refresh\"}\n\n"
            val failure = assertFailsWith<IOException> {
                evidence.observe(stream.toResponseBody(), "v", setOf("+70000001234")).string()
            }
            assertEquals("Stage candidate is not a confirmed new account", failure.message)
        }
    }

}
