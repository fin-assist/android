package ru.finassist.pf.mock.calc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The fake backend against the calculation fixtures of fin-assist/contracts (FIN-108): the same statements and
 * actions as the Go backend, the same expected responses (`src/test/resources/calc`, copied by
 * `scripts/sync-contracts.sh`). Any difference fails the test.
 *
 * `CALC_DUMP=<file>` writes every difference with both values there and the responses of the fake backend to
 * `<file>.responses/`.
 */
class CalcFixturesTest {
    @Test
    fun `fake backend matches the calculation fixtures`() {
        val actual = CalcScenario(ROOT).run()
        val diffs = actual.flatMap { (file, response) ->
            CalcCompare.compare(readJson(File(ROOT, file)), response).map { "$file $it" }
        }
        System.getenv("CALC_DUMP")?.let { out -> dump(File(out), diffs, actual) }
        if (diffs.isNotEmpty()) {
            fail("The fake backend and the calculation fixtures (${version()}) differ in ${diffs.size} places:\n" + diffs.joinToString("\n"))
        }
    }

    private fun dump(out: File, diffs: List<String>, actual: Map<String, JsonElement?>) {
        out.writeText(diffs.joinToString("\n"))
        val dir = File("${out.path}.responses").apply { mkdirs() }
        actual.forEach { (f, r) -> File(dir, File(f).name).writeText(PRETTY.encodeToString(JsonElement.serializer(), r ?: JsonNull)) }
    }

    private fun version() = "fin-assist/contracts@" + File(ROOT, "VERSION").readText().trim().take(12)

    private companion object {
        val ROOT = File("src/test/resources/calc")
        val PRETTY = Json { prettyPrint = true }
    }
}
