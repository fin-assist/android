package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.FieldErrorCodes
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.Tile
import ru.finassist.pf.core.api.model.TileGroupData
import ru.finassist.pf.core.api.model.TileGroupDataV2
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.api.MockAnalyticsApi
import ru.finassist.pf.mock.domain.AnalyticsComposer
import ru.finassist.pf.mock.domain.AnalyticsSnapshot
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsGroupedTilesTest {
    private val supports = mapOf("heading" to 1, "text" to 1, "rows" to 1, "chart" to 1, "tile_group" to 2,
        "ranked_list" to 1, "insight_card" to 1)
    private fun block(response: Analytics) = response.blocks.single { it.id == "tiles" }
    private fun grouped(response: Analytics) = assertNotNull(block(response).decode(TileGroupDataV2.serializer()))
    private fun flat(response: Analytics) = assertNotNull(block(response).decode(TileGroupData.serializer())).tiles

    @Test fun `groups only change presentation of every period and transfer mode`() {
        val backend = testBackend(TestClock("2026-10-31T12:00:00+03:00")).apply { load(fixtureDoc()) }
        val snapshots = TransferMode.entries.filter { it != TransferMode.UNKNOWN }.flatMap { mode ->
            listOf(PeriodTypeCode.MONTH to PeriodKey("2026-09"), PeriodTypeCode.QUARTER to PeriodKey("2026-Q3"),
                PeriodTypeCode.YEAR to PeriodKey("2026"), PeriodTypeCode.ALL_TIME to PeriodKey.ALL_TIME).map { (type, key) ->
                backend.analytics.analytics(type, key, mode)
            }
        } + listOf(AnalyticsDesignFixtures.september(), AnalyticsDesignFixtures.early,
            AnalyticsDesignFixtures.stale, AnalyticsDesignFixtures.year2026)
        snapshots.forEach { snapshot ->
            val legacy = AnalyticsComposer.compose(snapshot)
            val response = AnalyticsComposer.compose(snapshot, supports)
            assertEquals(legacy.copy(blocks = emptyList()), response.copy(blocks = emptyList()))
            assertEquals(legacy.blocks.filterNot { it.id == "tiles" }, response.blocks.filterNot { it.id == "tiles" })
            val groups = grouped(response).groups
            assertEquals(listOf("expense", "income", "balance"), groups[0].tiles.map { it.id })
            assertNull(groups[0].title)
            assertEquals("Дополнительные показатели", groups[1].title)
            assertEquals("Средние расходы в день", groups[1].tiles[0].title)
            val before = flat(legacy).associateBy { it.id }
            val after = groups.flatMap { it.tiles }.associateBy { it.id }
            assertEquals(before.keys, after.keys)
            before.forEach { (id, tile) ->
                assertEquals(tile, after.getValue(id).copy(title = tile.title, caption = tile.caption),
                    "${snapshot.params}: numeric values, states, locks and actions must stay unchanged for $id")
            }
            assertEquals("Расходы за период", after.getValue("expense").title)
            assertEquals("Доходы за период", after.getValue("income").title)
            assertTrue(after.getValue("daily_expense").caption!!.startsWith("По дням с данными не позже сегодня; пропуски не учитываются"))
        }
    }

    @Test fun `legacy negotiation preserves the complete flat payload and higher maxima choose version two`() {
        val snapshot = AnalyticsDesignFixtures.september()
        val legacy = AnalyticsComposer.compose(snapshot)
        assertEquals(legacy, AnalyticsComposer.compose(snapshot, supports.mapValues { 1 }))
        assertEquals(listOf("balance", "expense", "daily_expense", "income", "forecast"), flat(legacy).map { it.id })
        assertEquals(listOf("Доходы минус расходы", "Расходы", "Расходы в день", "Доходы", "Прогноз расходов"), flat(legacy).map { it.title })
        assertEquals(2, block(AnalyticsComposer.compose(snapshot, supports + ("tile_group" to Int.MAX_VALUE))).v)
        assertEquals(1, block(legacy).v)
        assertFalse(AnalyticsComposer.compose(snapshot, mapOf("chart" to 1)).blocks.any { it.id == "tiles" })
    }

    @Test fun `ready locked past and stale forecasts retain their states only in the additional group`() {
        for (snapshot in listOf(AnalyticsDesignFixtures.september(), AnalyticsDesignFixtures.early,
            AnalyticsDesignFixtures.stale, AnalyticsDesignFixtures.year2026, AnalyticsDesignFixtures.allTime)) {
            val response = AnalyticsComposer.compose(snapshot, supports)
            val groups = grouped(response).groups
            val expected = flat(AnalyticsComposer.compose(snapshot)).singleOrNull { it.id == "forecast" }
            assertEquals(expected, groups[1].tiles.singleOrNull { it.id == "forecast" })
            assertFalse(groups[0].tiles.any { it.id == "forecast" })
        }
        assertEquals(BlockStatus.LOCKED, grouped(AnalyticsComposer.compose(AnalyticsDesignFixtures.year2026, supports)).groups[1].tiles.last().status)
    }

    @Test fun `no data and failed composition keep negotiated empty data without fabricated facts`() {
        for (version in listOf(1, 2)) {
            val capability = supports + ("tile_group" to version)
            val empty = block(AnalyticsComposer.compose(AnalyticsDesignFixtures.noData, capability))
            assertEquals(version, empty.v)
            assertEquals(BlockStatus.NONE, empty.status)
            assertEquals("За этот период данных нет", empty.emptyText)
            val failed = block(AnalyticsComposer.compose(AnalyticsDesignFixtures.september(), capability, failing = setOf("tiles")))
            assertEquals(version, failed.v)
            assertEquals(BlockStatus.UNAVAILABLE, failed.status)
            assertEquals(AnalyticsComposer.UNAVAILABLE_TEXT, failed.emptyText)
            for (block in listOf(empty, failed)) {
                if (version == 2) assertEquals(emptyList(), block.decode(TileGroupDataV2.serializer())!!.groups)
                else assertEquals(emptyList(), block.decode(TileGroupData.serializer())!!.tiles)
            }
        }
        assertEquals(Analytics(hasData = false), AnalyticsComposer.compose(AnalyticsSnapshot(hasData = false), supports))
    }

    @Test fun `groups keep experiment ordering and their readable fallback follows display order`() {
        val response = AnalyticsComposer.compose(AnalyticsDesignFixtures.september(), supports,
            experiments = mapOf(AnalyticsComposer.ORDER_EXPERIMENT to "b"))
        assertEquals(listOf("expense_categories", "income_categories", "tiles"), response.blocks.take(3).map { it.id })
        val titles = grouped(response).groups.flatMap { it.tiles }.map(Tile::title)
        val fallback = block(response).altText!!
        assertTrue(titles.zipWithNext().all { (left, right) -> fallback.indexOf(left) < fallback.indexOf(right) })
        assertTrue("в августе" in grouped(response).groups[1].tiles.first().caption!!)
    }

    @Test fun `supports parser accepts defaults maxima and unknown types but rejects malformed versions`() {
        assertNull(AnalyticsComposer.parseSupports(null))
        assertNull(AnalyticsComposer.parseSupports(""))
        assertEquals(mapOf("tile_group" to 2, "future" to 3), AnalyticsComposer.parseSupports("tile_group@2,future@3,tile_group@1"))
        assertEquals(mapOf("tile_group" to Int.MAX_VALUE), AnalyticsComposer.parseSupports("tile_group@2147483647"))
        assertEquals(mapOf("tile_group" to 2), AnalyticsComposer.parseSupports("tile_group@+2,tile_group@1"))
        assertEquals(mapOf("tile_group" to Int.MAX_VALUE), AnalyticsComposer.parseSupports("tile_group@+2147483647"))
        for (raw in listOf("tile_group", "@2", "tile_group@", "tile_group@0", "tile_group@-1", "tile_group@+0",
            "tile_group@+", "tile_group@++2", "tile_group@+-2", "tile_group@ 2", "tile_group@2 ",
            "tile_group@２", "tile_group@+２", "tile_group@2147483648", "tile_group@+2147483648", "tile_group@2,", "tile_group@1@2")) {
            assertFailsWith<IllegalArgumentException>(raw) { AnalyticsComposer.parseSupports(raw) }
        }
    }

    @Test fun `invalid supports fail before latency or publication reads are consumed`() = runBlocking<Unit> {
        val backend = testBackend(config = FAST.copy(networkDelayMs = 60_000, analyticsPendingResponses = 2))
        val api = MockAnalyticsApi(backend)
        withTimeout(1_000) {
            repeat(2) {
                for (raw in listOf("tile_group@abc", "tile_group@0", "tile_group@2147483648", "@2")) {
                    val error = assertFailsWith<AppError.Api> { api.getAnalytics(null, null, null, supports = raw) }
                    assertEquals(ErrorCodes.VALIDATION_ERROR, error.code)
                    assertEquals(400, error.httpStatus)
                    assertEquals("supports", error.details.single().field)
                    assertEquals(FieldErrorCodes.INVALID_FORMAT, error.details.single().code)
                }
            }
        }
        assertEquals(2, backend.config.analyticsPendingResponses)
        backend.config = backend.config.copy(networkDelayMs = 0)
        assertFalse(api.getAnalytics(null, null, null).hasData)
        assertEquals(1, backend.config.analyticsPendingResponses)
    }

    @Test fun `API passes negotiated versions and ignores unknown primitive names`() = runBlocking<Unit> {
        val api = MockAnalyticsApi(testBackend().apply { load(fixtureDoc()) })
        for ((raw, version) in listOf(null to 1, "" to 1, "tile_group@1" to 1,
            "tile_group@2" to 2, "tile_group@2,tile_group@1" to 2, "tile_group@+2" to 2)) {
            val response = api.getAnalytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITHOUT, supports = raw)
            assertEquals(version, block(response).v)
            assertEquals(TransferMode.WITHOUT, response.params!!.transferMode)
            if (version == 2) assertEquals(listOf("expense", "income", "balance"), grouped(response).groups[0].tiles.map { it.id })
            else assertEquals(listOf("balance", "expense", "daily_expense", "income"), flat(response).map { it.id })
        }
        val future = api.getAnalytics(null, null, null, supports = "future_primitive@3")
        assertTrue(future.hasData)
        assertTrue(future.blocks.isEmpty())
    }
}
