package ru.finassist.pf.feature.analytics.impl.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import ru.finassist.pf.core.api.ApiJson
import org.junit.Test
import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.InsightCardData
import ru.finassist.pf.core.api.model.InsightItem
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.PeriodKey
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegularPaymentDismissalsTest {
    private val month = AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH)
    private fun response(ids: List<String>, params: AnalyticsParams = month, status: BlockStatus = BlockStatus.READY): Analytics {
        val card = Block.of(BlockType.INSIGHT_CARD, InsightCardData.serializer(), InsightCardData("repeat", "Подписки",
            items = ids.map { InsightItem(title = it, button = BlockAction(ActionType.DISMISS_REGULAR_PAYMENT, paymentId = it, label = "Не подписка")) }))
        val payload = if (ids.isEmpty()) JsonObject(card.data!! + ("items" to JsonArray(emptyList()))) else card.data
        return Analytics(hasData = true, params = params, blocks = listOf(card.copy(id = "regular_payments", status = status, data = payload)))
    }

    @Test fun `a confirmed write polls stale projection and completes only when payment disappears`() {
        val tracker = RegularPaymentDismissals()
        assertTrue(tracker.begin("one", month))
        assertFalse(tracker.begin("one", month))
        assertEquals(RegularPaymentDismissals.Observation(false, false), tracker.observe(response(listOf("one"))))
        tracker.confirm("one")
        assertEquals(RegularPaymentDismissals.Observation(true, true), tracker.observe(response(listOf("one"))))
        assertEquals(setOf("one"), tracker.ids)
        assertEquals(RegularPaymentDismissals.Observation(false, false), tracker.observe(response(emptyList(), status = BlockStatus.NONE)))
        assertTrue(tracker.ids.isEmpty())
    }

    @Test fun `absence in another period is not a publication receipt`() {
        val tracker = RegularPaymentDismissals()
        tracker.begin("one", month)
        tracker.confirm("one")
        val year = AnalyticsParams(PeriodTypeCode.YEAR, PeriodKey("2026"), TransferMode.WITH)
        assertEquals(RegularPaymentDismissals.Observation(false, false), tracker.observe(response(emptyList(), year)))
        assertEquals(setOf("one"), tracker.ids)
        assertTrue(tracker.observe(response(listOf("one"), year)).poll)
        assertTrue(tracker.observe(response(listOf("one"))).poll)
        tracker.observe(response(emptyList()))
        assertTrue(tracker.ids.isEmpty())
    }

    @Test fun `reads stop after ten responses and explicit read retry replenishes only that budget`() {
        val tracker = RegularPaymentDismissals()
        tracker.begin("one", month)
        tracker.confirm("one")
        repeat(9) { assertTrue(tracker.observe(response(listOf("one"))).poll) }
        assertEquals(RegularPaymentDismissals.Observation(true, false), tracker.observe(response(listOf("one"))))
        assertFalse(tracker.begin("one", month), "timeout must never replay the successful PUT")
        tracker.retryRead()
        assertTrue(tracker.observe(response(listOf("one"))).poll)
    }

    @Test fun `unavailable missing or malformed cards cannot acknowledge publication`() {
        val tracker = RegularPaymentDismissals()
        tracker.begin("one", month)
        tracker.confirm("one")
        assertTrue(tracker.observe(response(emptyList(), status = BlockStatus.UNAVAILABLE)).awaiting)
        assertTrue(tracker.observe(response(emptyList()).copy(blocks = emptyList())).awaiting)
        assertTrue(tracker.observe(response(emptyList()).copy(blocks = listOf(Block(id = "regular_payments", type = BlockType.INSIGHT_CARD)))).awaiting)
        assertEquals(setOf("one"), tracker.ids)
    }

    @Test fun `failed mutation allows the same id again and leaves other pending writes alone`() {
        val tracker = RegularPaymentDismissals()
        tracker.begin("one", month)
        tracker.begin("two", month)
        tracker.fail("one")
        assertEquals(setOf("two"), tracker.ids)
        assertTrue(tracker.begin("one", month))
        tracker.confirm("unknown")
    }

    @Test fun `tolerant rendering cannot acknowledge malformed or incomplete payment lists`() {
        for (items in listOf("{}", "null", "[null]", "[{}]", "[{\"button\":{\"type\":\"dismiss_regular_payment\",\"payment_id\":\"one\"}}]",
            "[{\"title\":\"One\",\"button\":{\"type\":\"dismiss_regular_payment\"}}]",
            "[{\"title\":\"One\",\"button\":{\"type\":\"dismiss_regular_payment\",\"payment_id\":123}}]",
            "[{\"title\":123,\"button\":{\"type\":\"dismiss_regular_payment\",\"payment_id\":\"one\"}}]",
            "[{\"title\":\"Other\",\"button\":{\"type\":\"dismiss_regular_payment\",\"payment_id\":\"two\"}},{}]")) {
            val tracker = RegularPaymentDismissals()
            tracker.begin("one", month)
            tracker.confirm("one")
            val valid = response(listOf("one"))
            val card = valid.blocks.single()
            val malformed = valid.copy(blocks = listOf(card.copy(data = JsonObject(card.data!! + ("items" to ApiJson.parseToJsonElement(items))))))
            assertTrue(tracker.observe(malformed).poll, items)
            assertEquals(setOf("one"), tracker.ids)
            assertFalse(tracker.begin("one", month), "malformed data must not unlock another PUT")
            assertTrue(tracker.observe(valid).poll)
            assertFalse(tracker.observe(response(listOf("two"))).awaiting)
            assertTrue(tracker.ids.isEmpty())
        }
    }

    @Test fun `omitted items only acknowledges a valid none card`() {
        val tracker = RegularPaymentDismissals()
        tracker.begin("one", month)
        tracker.confirm("one")
        val valid = response(emptyList())
        val card = valid.blocks.single().let { it.copy(data = JsonObject(it.data!! - "items")) }
        assertTrue(tracker.observe(valid.copy(blocks = listOf(card))).poll)
        assertFalse(tracker.observe(valid.copy(blocks = listOf(card.copy(status = BlockStatus.NONE)))).awaiting)
        assertTrue(tracker.ids.isEmpty())
    }
}
