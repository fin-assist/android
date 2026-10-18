package ru.finassist.pf.mock.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.fail

/**
 * Checks of one [Wire.Exchange] against the pinned contract. A message an [Exclusions.Schema] entry names for this
 * case is expected (a known mismatch with its issue); an entry that matches nothing fails the test, so a fixed
 * mismatch cannot stay excluded.
 */
internal object ContractCheck {

    fun assertContract(case: String, exchange: Wire.Exchange) {
        val op = exchange.operation
        val problems = ArrayList<String>()
        if (exchange.status !in op.statuses) problems += "status ${exchange.status} is not declared for ${op.id} (${op.statuses.sorted()})"
        exchange.code?.let { code ->
            when {
                code !in Contract.registry -> problems += "error code $code is not in section 8 of api.md"
                Contract.registry[code] != exchange.status ->
                    problems += "error code $code is ${Contract.registry[code]} in section 8 of api.md, the mock gives ${exchange.status}"
            }
        }
        problems += if (op.events.isNotEmpty() && exchange.status == op.success) {
            // A stream without a terminal event is a drop (api.md, «SSE»): the shape cases check which one ends it.
            if (exchange.events.isEmpty()) listOf("the stream ended without an event") else exchange.events.flatMap { (name, data) -> Contract.validateEvent(op, name, data) }
        } else {
            Contract.validateResponse(op, exchange.path, exchange.status, exchange.body, exchange.headers)
        }

        val excluded = Exclusions.schema.filter { it.case == case }
        val body = exchange.body?.let { Json.parseToJsonElement(it) }
        val unused = excluded.filter { ex -> problems.none { ex.covers(it, body) } }
        val left = problems.filter { p -> excluded.none { it.covers(p, body) } }
        if (left.isNotEmpty() || unused.isNotEmpty()) {
            fail(
                buildString {
                    append("$case: $exchange against fin-assist/contracts@${Contract.version.take(12)}\n")
                    left.forEach { append("  - $it\n") }
                    unused.forEach { append("  - exclusion «${it.match}» matches nothing: the mismatch is gone, remove it from Exclusions\n") }
                    exchange.body?.let { append("  body: ${it.take(BODY_PREVIEW)}\n") }
                    exchange.events.take(EVENTS_PREVIEW).forEach { (n, d) -> append("  event $n: ${d.take(BODY_PREVIEW)}\n") }
                },
            )
        }
    }

    /**
     * [exchange] shows a known behavioural gap ([Exclusions.behaviours], [id]): the contract wants [contract]
     * (`error.code`), the fake backend gives [now] today (`error.code`, or null for a success). Fails when the gap is
     * closed (remove the entry) and when the outcome is anything else (a change the gap does not explain); the outcome
     * itself is still checked against the schema of its status.
     */
    fun knownGap(id: String, exchange: Wire.Exchange, contract: String, now: String?) {
        val gap = Exclusions.behaviours.singleOrNull { it.id == id } ?: error("no behavioural exclusion $id")
        if (exchange.code == contract) fail("$id: $exchange now follows the contract: remove it from Exclusions.behaviours (${gap.issue})")
        if (exchange.code != now) {
            fail("$id: $exchange — the contract wants $contract, the known gap is ${now ?: "a success"}, the fake backend gives ${exchange.code ?: "a success"}")
        }
        if (now == null && exchange.status != exchange.operation.success) fail("$id: $exchange — the known gap is a success")
        assertContract("gap $id", exchange)
    }

    /**
     * Events before the last one that end a stream (api.md, «SSE»: the server closes the stream after its terminal
     * event): `error`, `complete`, `done`, or a `status` event with a final status (`verified`, `expired`).
     */
    fun earlyTerminals(events: List<Pair<String, String>>): List<Pair<String, String>> = events.dropLast(1).filter { (name, data) -> isTerminal(name, data) }

    private fun isTerminal(name: String, data: String): Boolean = when (name) {
        "error", "complete", "done" -> true
        "status" -> runCatching { Json.parseToJsonElement(data).jsonObject["status"]?.jsonPrimitive?.content }.getOrNull() in FINAL_STATUSES
        else -> false
    }

    private val FINAL_STATUSES = setOf("verified", "expired")

    private const val BODY_PREVIEW = 2_000
    private const val EVENTS_PREVIEW = 20
}
