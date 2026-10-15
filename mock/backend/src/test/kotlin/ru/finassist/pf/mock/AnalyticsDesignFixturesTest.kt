package ru.finassist.pf.mock

import org.junit.Test
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.mock.domain.AnalyticsComposer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden responses of 6.1 for the client's design check (`feature/analytics/impl/src/test/resources/analytics/`):
 * the fixtures laid out by the same composer as the fake server, so the snapshots show the server's texts. The test
 * fails when the composer changes the layout; `UPDATE_GOLDEN=1 ./gradlew :mock:backend:testDebugUnitTest` rewrites
 * the files.
 */
class AnalyticsDesignFixturesTest {
    private val dir = File("../../feature/analytics/impl/src/test/resources/analytics")
    private val json = kotlinx.serialization.json.Json(ApiJson) { prettyPrint = true }

    private val f = AnalyticsDesignFixtures
    private val golden = mapOf(
        "september" to f.september(),
        "september_no_transfers" to f.september(noTransfers = true),
        "year_2026" to f.year2026,
        "early" to f.early,
        "stale" to f.stale,
    )

    /**
     * Every `open_operations` in the golden responses carries a filter with a condition: with an empty one the client
     * drops the action (`BlockLayout.usable`), and the design check renders the rows without chevrons (FIN-100).
     */
    @Test
    fun `open_operations actions of the golden responses have a filter`() {
        golden.forEach { (name, snapshot) ->
            val actions = mutableListOf<JsonObject>()
            fun walk(e: JsonElement) {
                when (e) {
                    is JsonObject -> {
                        if ((e["type"] as? JsonPrimitive)?.contentOrNull == "open_operations") actions += e
                        e.values.forEach(::walk)
                    }
                    is JsonArray -> e.forEach(::walk)
                    else -> Unit
                }
            }
            walk(json.encodeToJsonElement(Analytics.serializer(), AnalyticsComposer.compose(snapshot)))
            assertTrue(actions.isNotEmpty(), "$name.json has no open_operations")
            actions.forEach { action ->
                val conditions = (action["filter"] as? JsonObject).orEmpty().keys - "transfer_mode"
                assertTrue(conditions.isNotEmpty(), "$name.json: open_operations without a filter condition: $action")
            }
        }
    }

    @Test
    fun `golden responses of the design check are up to date`() {
        val update = System.getenv("UPDATE_GOLDEN") == "1"
        golden.forEach { (name, snapshot) ->
            val text = json.encodeToString(Analytics.serializer(), AnalyticsComposer.compose(snapshot)) + "\n"
            val file = File(dir, "$name.json")
            if (update) {
                dir.mkdirs()
                file.writeText(text)
            } else {
                assertEquals(text, file.readText(), "$name.json is stale: UPDATE_GOLDEN=1 rewrites it")
            }
        }
    }
}
