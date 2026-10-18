package ru.finassist.pf.mock.contract

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
            if (exchange.events.isEmpty()) listOf("the stream ended without an event") else exchange.events.flatMap { (name, data) -> Contract.validateEvent(op, name, data) }
        } else {
            Contract.validateResponse(op, exchange.path, exchange.status, exchange.body, exchange.headers)
        }

        val excluded = Exclusions.schema.filter { it.case == case }
        val unused = excluded.filter { ex -> problems.none { ex.match in it } }
        val left = problems.filter { p -> excluded.none { it.match in p } }
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

    /** [check] reproduces a known behavioural gap ([Exclusions.behaviours], [id]); once it passes, the gap is closed. */
    suspend fun knownGap(id: String, check: suspend () -> Unit) {
        val gap = Exclusions.behaviours.singleOrNull { it.id == id } ?: error("no behavioural exclusion $id")
        val stillThere = try {
            check()
            false
        } catch (e: AssertionError) {
            true
        }
        if (!stillThere) fail("$id now follows the contract: remove it from Exclusions.behaviours (${gap.issue})")
    }

    private const val BODY_PREVIEW = 2_000
    private const val EVENTS_PREVIEW = 20
}
