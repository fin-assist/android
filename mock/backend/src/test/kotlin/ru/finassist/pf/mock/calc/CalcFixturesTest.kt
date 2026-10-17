package ru.finassist.pf.mock.calc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The fake backend against the calculation fixtures of fin-assist/contracts (FIN-108): the same statements and
 * actions as the Go backend, the same expected responses (`src/test/resources/calc`, copied by
 * `scripts/sync-calc-fixtures.sh`). A difference fails the test unless `calc-known-divergences.json` lists it under
 * its cause and the issue that fixes it; a listed difference that is gone fails too, so the list only shrinks.
 *
 * `CALC_UPDATE_KNOWN=1` rewrites the list from the current differences: the remaining ones keep their group, new ones
 * go to a `TODO` group. `CALC_DUMP=<file>` writes every difference with both values there and the responses of the
 * fake backend to `<file>.responses/`.
 */
class CalcFixturesTest {
    @Test
    fun `fake backend matches the calculation fixtures`() {
        val actual = CalcScenario(ROOT).run()
        val diffs = actual.flatMap { (file, response) ->
            CalcCompare.compare(readJson(File(ROOT, file)), response).map { "$file $it" }
        }
        val current = diffs.map(::entry).toSet()
        val groups = KNOWN.takeIf { it.exists() }?.let { readJson(it).jsonObject.getValue("groups").jsonArray.map(::Group) }.orEmpty()
        val listed = groups.flatMap { it.paths }.toSet()

        System.getenv("CALC_DUMP")?.let { out -> dump(File(out), diffs, actual) }
        if (System.getenv("CALC_UPDATE_KNOWN") == "1") {
            KNOWN.writeText(render(groups, current))
            return
        }

        val problems = diffs.filter { entry(it) !in listed }.map { "new: $it" } +
            (listed - current).sorted().map { "fixed, remove from calc-known-divergences.json: $it" }
        if (problems.isNotEmpty()) {
            fail("The fake backend and the calculation fixtures (${version()}) differ:\n" + problems.joinToString("\n"))
        }
    }

    private class Group(json: JsonElement) {
        val reason = json.jsonObject.getValue("reason").jsonPrimitive.content
        val issue = json.jsonObject["issue"]?.jsonPrimitive?.content
        val paths = json.jsonObject.getValue("paths").jsonArray.map { it.jsonPrimitive.content }
    }

    /** `expected/x.json $.a.b: expected != actual` → `expected/x.json $.a.b`. */
    private fun entry(diff: String) = diff.substringBefore(": ")

    private fun render(groups: List<Group>, current: Set<String>): String {
        val kept = groups.map { g -> g to g.paths.filter { it in current } }
        val fresh = (current - groups.flatMap { it.paths }.toSet()).sorted()
        val doc = buildJsonObject {
            put("_comment", COMMENT)
            put("fixtures", version())
            put("groups", buildJsonArray {
                for ((g, paths) in kept) if (paths.isNotEmpty()) add(group(g.reason, g.issue, paths))
                if (fresh.isNotEmpty()) add(group("TODO: разобрать", null, fresh))
            })
        }
        return PRETTY.encodeToString(JsonObject.serializer(), doc) + "\n"
    }

    private fun group(reason: String, issue: String?, paths: List<String>) = buildJsonObject {
        put("reason", reason)
        issue?.let { put("issue", it) }
        put("paths", strings(paths))
    }

    private fun dump(out: File, diffs: List<String>, actual: Map<String, JsonElement?>) {
        out.writeText(diffs.joinToString("\n"))
        val dir = File("${out.path}.responses").apply { mkdirs() }
        actual.forEach { (f, r) -> File(dir, File(f).name).writeText(PRETTY.encodeToString(JsonElement.serializer(), r ?: JsonNull)) }
    }

    private fun version() = "fin-assist/contracts@" + File(ROOT, "VERSION").readText().trim().take(12)

    private companion object {
        val ROOT = File("src/test/resources/calc")
        val KNOWN = File("src/test/resources/calc-known-divergences.json")
        val PRETTY = Json { prettyPrint = true }
        const val COMMENT = "Known differences of the fake backend from the calculation fixtures (FIN-108), by cause: " +
            "the issue that fixes them and «expect file + JSON path». A fix removes its paths (CALC_UPDATE_KNOWN=1); " +
            "a cause without an issue needs one."
    }
}
