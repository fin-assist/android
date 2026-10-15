package ru.finassist.pf.mock

import org.junit.Test
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.mock.domain.AnalyticsComposer
import java.io.File
import kotlin.test.assertEquals

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
