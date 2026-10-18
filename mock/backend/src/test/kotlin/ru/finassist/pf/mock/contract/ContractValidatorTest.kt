package ru.finassist.pf.mock.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The validator itself (FIN-123): what it must catch, so a library update or a change of its options cannot make the
 * contract tests pass by checking less. `getAssistantLimit`: `{remaining, daily_max, resets_at}`, all required.
 */
class ContractValidatorTest {
    private val op = Contract.operation("getAssistantLimit")
    private fun check(body: String, status: Int = 200) = Contract.validateResponse(op, op.path, status, body, emptyMap())

    private val valid = """{"remaining":5,"daily_max":5,"resets_at":"2026-10-07T00:00:00+03:00"}"""

    @Test fun `a valid body passes`() = assertEquals(emptyList<String>(), check(valid))

    @Test fun `a field the schema does not declare fails`() =
        assertTrue(check(valid.replace("}", ""","used":0}""")).any { "additionalProperties" in it })

    @Test fun `a missing required field fails`() =
        assertTrue(check(valid.replace(""","daily_max":5""", "")).any { "required" in it })

    @Test fun `a wrong type fails`() = assertTrue(check(valid.replace("\"remaining\":5", "\"remaining\":\"5\"")).any { "type" in it })

    @Test fun `null in a field that is not nullable fails`() = assertTrue(check(valid.replace("\"remaining\":5", "\"remaining\":null")).isNotEmpty())

    @Test fun `null in a required field fails`() =
        assertTrue(check(valid.replace("\"resets_at\":\"2026-10-07T00:00:00+03:00\"", "\"resets_at\":null")).isNotEmpty())

    @Test fun `null for an optional field that is not nullable is the field left out`() {
        // `total_count` of a feed page: optional, not nullable — a server leaves it out, the DTO has it as null.
        val list = Contract.operation("listOperations")
        assertEquals(emptyList<String>(), Contract.validateResponse(list, "/v1/operations", 200, """{"items":[],"total_count":null}""", emptyMap()))
        assertTrue(Contract.validateResponse(list, "/v1/operations", 200, """{"items":[],"total_count":"1"}""", emptyMap()).isNotEmpty())
    }

    @Test fun `null in a nullable allOf wrapper passes`() {
        // `account` is `{nullable, allOf: [AccountDetails]}`: the merge keeps `nullable` of the wrapper.
        val op = Contract.operation("getOperation")
        val body = """{"id":"o1","kind":"own_transfer","occurred_at":"2026-10-01T10:00:00+03:00","amount":100,"currency":"RUB","title":"Перевод",
            |"category_id":"cat_transfer","category_name":"Переводы","category_icon":"transfer","is_category_manual":false,"status":"posted",
            |"source_name":"Выписка Т-Банка","account":null}""".trimMargin()
        assertEquals(emptyList<String>(), Contract.validateResponse(op, "/v1/operations/o1", 200, body, emptyMap()))
    }

    @Test fun `a date-time in another format fails`() = assertTrue(check(valid.replace("2026-10-07T00:00:00+03:00", "07.10.2026")).isNotEmpty())

    @Test fun `extra fields fail inside allOf schemas too`() {
        val feed = Contract.operation("getOperation")
        val body = """{"id":"o1","kind":"expense","occurred_at":"2026-10-01T10:00:00+03:00","amount":100,"currency":"RUB","title":"Кофе",
            |"category_id":"cat_cafe","category_name":"Кафе","category_icon":"cafe","is_category_manual":false,"status":"posted",
            |"source_name":"Выписка Т-Банка","secret":"x"}""".trimMargin()
        val messages = Contract.validateResponse(feed, "/v1/operations/o1", 200, body, emptyMap())
        assertTrue(messages.toString(), messages.any { "additionalProperties" in it && "secret" in it })
    }

    @Test fun `an unknown value of an open code passes`() {
        val feed = Contract.operation("getOperation")
        val body = """{"id":"o1","kind":"cashback","occurred_at":"2026-10-01T10:00:00+03:00","amount":100,"currency":"RUB","title":"Кофе",
            |"category_id":"cat_cafe","category_name":"Кафе","category_icon":"cafe","is_category_manual":false,"status":"posted",
            |"source_name":"Выписка Т-Банка"}""".trimMargin()
        assertEquals(emptyList<String>(), Contract.validateResponse(feed, "/v1/operations/o1", 200, body, emptyMap()))
    }

    @Test fun `an error body is checked against the error schema`() {
        val messages = check("""{"error":{"code":"NOT_FOUND"}}""", status = 401)
        assertTrue(messages.toString(), messages.any { "required" in it && "message" in it })
    }

    @Test fun `a status the operation does not declare fails`() =
        assertTrue(check("""{"error":{"code":"NOT_FOUND","message":"x"}}""", status = 404).isNotEmpty())

    @Test fun `a block's data is checked against its primitive`() {
        val ask = Contract.operation("streamAnswer")
        val event = """{"index":0,"block":{"type":"heading","v":1,"data":{"title":1}}}"""
        assertTrue(Contract.validateEvent(ask, "block", event).any { it.startsWith("block") })
    }

    @Test fun `a field an event does not declare fails`() {
        val event = """{"index":0,"block":{"type":"text","v":1,"data":{"text":"Привет"}},"extra":1}"""
        val messages = Contract.validateEvent(Contract.operation("streamAnswer"), "block", event)
        assertTrue(messages.toString(), messages.any { "additionalProperties" in it && "extra" in it })
    }

    @Test fun `a field a nested object of an event does not declare fails, behind its allOf too`() {
        // `snapshot.source` is `{nullable, allOf: [AnswerSource]}`: the nested cause is reported, not «allOf failed».
        val event = """{"status":"complete","blocks":[],"chips":[],"currency":"RUB","source":{"calculated_at":"2026-10-01T10:00:00+03:00",
            |"transfer_mode":"with","changed_since":[],"changed_since_text":null,"extra":1}}""".trimMargin()
        val messages = Contract.validateEvent(Contract.operation("streamAnswer"), "snapshot", event)
        assertTrue(messages.toString(), messages.isNotEmpty() && messages.all { "additionalProperties" in it && "extra" in it })
    }

    @Test fun `a missing field of a nested object of an event is reported by its name`() {
        val event = """{"status":"complete","blocks":[],"chips":[],"currency":"RUB","source":{"calculated_at":"2026-10-01T10:00:00+03:00",
            |"changed_since":[],"changed_since_text":null}}""".trimMargin()
        val messages = Contract.validateEvent(Contract.operation("streamAnswer"), "snapshot", event)
        assertTrue(messages.toString(), messages.any { "required" in it && "transfer_mode" in it })
    }

    @Test fun `an undeclared field fails when it is null too`() {
        val list = Contract.operation("listOperations")
        val messages = Contract.validateResponse(list, "/v1/operations", 200, """{"items":[],"secret":null}""", emptyMap())
        assertTrue(messages.toString(), messages.any { "additionalProperties" in it && "secret" in it })
    }

    @Test fun `an undeclared null field of an event fails`() {
        val messages = Contract.validateEvent(Contract.operation("streamAnswer"), "chunk", """{"index":0,"text":"Вы","extra":null}""")
        assertTrue(messages.toString(), messages.any { "additionalProperties" in it && "extra" in it })
    }

    @Test fun `an undeclared null field of a nested object of an event fails`() {
        val event = """{"status":"complete","blocks":[],"chips":[],"currency":"RUB","source":{"calculated_at":"2026-10-01T10:00:00+03:00",
            |"transfer_mode":"with","changed_since":[],"changed_since_text":null,"extra":null}}""".trimMargin()
        val messages = Contract.validateEvent(Contract.operation("streamAnswer"), "snapshot", event)
        assertTrue(messages.toString(), messages.any { "additionalProperties" in it && "extra" in it })
    }

    @Test fun `a terminal event before the last one is found`() {
        val cancelledThenComplete = listOf("progress" to "{}", "error" to """{"code":"CANCELLED","message":"x"}""", "complete" to "{}")
        assertEquals(listOf(cancelledThenComplete[1]), ContractCheck.earlyTerminals(cancelledThenComplete))
        val verifiedThenExpired = listOf("status" to """{"status":"pending"}""", "status" to """{"status":"verified"}""", "status" to """{"status":"expired"}""")
        assertEquals(listOf(verifiedThenExpired[1]), ContractCheck.earlyTerminals(verifiedThenExpired))
        val doneTwice = listOf("snapshot" to "{}", "done" to "{}", "done" to "{}")
        assertEquals(listOf(doneTwice[1]), ContractCheck.earlyTerminals(doneTwice))
        assertEquals(emptyList<Pair<String, String>>(), ContractCheck.earlyTerminals(listOf("status" to """{"status":"pending"}""", "status" to """{"status":"verified"}""")))
    }

    @Test fun `an event the stream does not declare fails`() =
        assertTrue(Contract.validateEvent(Contract.operation("streamAnswer"), "progress", "{}").isNotEmpty())

    @Test fun `the registry is read from api md`() {
        assertEquals(400, Contract.registry["CLIENT_VERSION_UNSUPPORTED"])
        assertEquals(404, Contract.registry["NOT_FOUND"])
        assertEquals(null, Contract.registry["MODEL_ERROR"])
        assertTrue(Contract.registry.containsKey("MODEL_ERROR"))
    }
}
