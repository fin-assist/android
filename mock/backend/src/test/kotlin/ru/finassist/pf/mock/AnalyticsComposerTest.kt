package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.InsightCardData
import ru.finassist.pf.core.api.model.ListStyle
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.RankedListData
import ru.finassist.pf.core.api.model.TileGroupData
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.api.MockAnalyticsApi
import ru.finassist.pf.mock.domain.AnalyticsComposer
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The screen as blocks (api.md 6.1 «Блоки экрана», FIN-4/FIN-5). */
class AnalyticsComposerTest {
    private val f = AnalyticsDesignFixtures
    private fun ids(snapshot: ru.finassist.pf.mock.domain.AnalyticsSnapshot, supports: Map<String, Int>? = null, exp: Map<String, String> = emptyMap(), failing: Set<String> = emptySet()) =
        AnalyticsComposer.compose(snapshot, supports, exp, failing).blocks.map { it.id ?: it.type.code }

    @Test fun `a month is laid out as on the mockup`() {
        assertEquals(
            listOf("tiles", "monthly_chart", "expense_categories", "income_categories", "heading", "regular_payments", "notable_spending", "bank_fees", "small_frequent", "balance"),
            ids(f.september()),
        )
    }

    @Test fun `a year has no insights and no balance chart, an early month no monthly chart`() {
        assertEquals(listOf("tiles", "monthly_chart", "expense_categories", "income_categories"), ids(f.year2026))
        assertTrue("monthly_chart" !in ids(f.early))
    }

    @Test fun `variant b of the order experiment puts the categories first, an unknown variant changes nothing`() {
        assertEquals(listOf("expense_categories", "income_categories", "tiles"), ids(f.september(), exp = mapOf("analytics_order" to "b")).take(3))
        assertEquals(ids(f.september()), ids(f.september(), exp = mapOf("analytics_order" to "z", "other" to "x")))
    }

    @Test fun `only blocks the client supports are sent`() {
        val supports = AnalyticsComposer.parseSupports("tile_group@1,chart@1")
        assertEquals(mapOf("tile_group" to 1, "chart" to 1), supports)
        assertEquals(listOf("tiles", "monthly_chart", "balance"), ids(f.september(), supports = supports))
        assertEquals(mapOf("analytics_order" to "b"), AnalyticsComposer.parseExperiments("analytics_order:b,broken"))
    }

    @Test fun `a block that fails comes as unavailable, the rest of the screen stays`() {
        val a = AnalyticsComposer.compose(f.september(), failing = setOf("bank_fees", "tiles"))
        val fees = a.blocks.single { it.id == "bank_fees" }
        assertEquals(BlockStatus.UNAVAILABLE, fees.status)
        assertEquals(AnalyticsComposer.UNAVAILABLE_TEXT, fees.emptyText)
        assertEquals("Комиссии и проценты банку", assertNotNull(fees.decode(InsightCardData.serializer())).title)
        assertEquals(BlockStatus.UNAVAILABLE, a.blocks.single { it.id == "tiles" }.status)
        assertEquals(ids(f.september()), a.blocks.map { it.id ?: it.type.code })
    }

    @Test fun `texts and actions come from the server`() {
        val a = AnalyticsComposer.compose(f.september())
        val tiles = assertNotNull(a.blocks.first { it.id == "tiles" }.decode(TileGroupData.serializer())).tiles
        assertEquals(listOf("balance", "expense", "daily_expense", "income", "forecast"), tiles.map { it.id })
        assertEquals("46% дохода · по выписке", tiles[0].caption)
        assertTrue(tiles[4].approx)
        assertTrue(tiles[3].showPlus)
        assertEquals(ActionType.OPEN_OPERATIONS, tiles[3].action?.type)
        val income = assertNotNull(a.blocks.first { it.id == "income_categories" }.decode(RankedListData.serializer()))
        assertEquals(ListStyle.PLAIN, income.style)
        val regular = a.blocks.first { it.id == "regular_payments" }
        val card = assertNotNull(regular.decode(InsightCardData.serializer()))
        assertEquals(3, card.items.size)
        assertEquals("Ещё 4", card.itemsMore?.label)
        assertEquals(ActionType.DISMISS_REGULAR_PAYMENT, card.items.first().button?.type)
        assertEquals("Не подписка", card.items.first().button?.label)
    }

    @Test fun `a locked block carries the server's lock text`() {
        // One full month of data: the chart is there, locked (the early state hides it only for an incomplete month).
        val oneFullMonth = f.september().let { s -> s.copy(period = s.period!!.copy(coverage = Coverage.COMPLETE), monthlyChart = f.early.monthlyChart) }
        val chart = AnalyticsComposer.compose(oneFullMonth).blocks.single { it.id == "monthly_chart" }
        assertEquals(BlockStatus.LOCKED, chart.status)
        assertEquals("Нужно 2 месяца с данными, есть 1", chart.lock?.text?.replace('\u00A0', ' '))
        assertEquals(BlockType.CHART, chart.type)
    }

    @Test fun `the fake API passes supports, the experiment and failing blocks to the composer`() = runBlocking<Unit> {
        val backend = testBackend(config = FAST.copy(failingBlocks = setOf("tiles"))).apply { load(fixtureDoc()) }
        val api = MockAnalyticsApi(backend)
        val a = api.getAnalytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH, supports = "tile_group@1,ranked_list@1", exp = "analytics_order:b")
        assertEquals(listOf("expense_categories", "income_categories", "tiles"), a.blocks.map { it.id })
        assertEquals(BlockStatus.UNAVAILABLE, a.blocks.last().status)
    }

    @Test fun `with unknown codes the fake API adds a block of an unknown type and a card locked for an unknown reason`() = runBlocking<Unit> {
        val backend = testBackend(config = FAST.copy(unknownCodes = true)).apply { load(fixtureDoc()) }
        val a = MockAnalyticsApi(backend).getAnalytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH)
        val ids = a.blocks.map { it.id }
        assertEquals(ids.indexOf("tiles") + 1, ids.indexOf("e2e_unknown"))
        assertEquals(MockCodes.BLOCK_UNKNOWN_TEXT, a.blocks.single { it.id == "e2e_unknown" }.altText)
        val locked = a.blocks.single { it.id == "e2e_locked" }
        assertEquals(ru.finassist.pf.core.api.model.LockReason.UNKNOWN, locked.lock?.reason)
        assertEquals(MockCodes.LOCK_UNKNOWN_TEXT, locked.lock?.text)
    }
}
