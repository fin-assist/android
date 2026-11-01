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
    private val firstId = "019c6e27-e55b-73d1-87d8-0c7f50590c9d"
    private val secondId = "019c6e27-e55b-73d1-87d8-0c7f50590c9e"
    private val month = AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH)
    private fun response(ids: List<String>, params: AnalyticsParams = month, status: BlockStatus = BlockStatus.READY): Analytics {
        val card = Block.of(BlockType.INSIGHT_CARD, InsightCardData.serializer(), InsightCardData("repeat", "Подписки",
            items = ids.map { InsightItem(title = it, button = BlockAction(ActionType.DISMISS_REGULAR_PAYMENT, paymentId = it, label = "Не подписка")) }))
        val payload = if (ids.isEmpty()) JsonObject(card.data!! + ("items" to JsonArray(emptyList()))) else card.data
        return Analytics(hasData = true, params = params, blocks = listOf(card.copy(id = "regular_payments", status = status, data = payload)))
    }

    @Test fun `a confirmed write polls stale projection and completes only when payment disappears`() {
        val tracker = RegularPaymentDismissals()
        assertTrue(tracker.begin(firstId, month))
        assertFalse(tracker.begin(firstId, month))
        assertEquals(RegularPaymentDismissals.Observation(false, false), tracker.observe(response(listOf(firstId))))
        tracker.confirm(firstId)
        assertEquals(RegularPaymentDismissals.Observation(true, true), tracker.observe(response(listOf(firstId))))
        assertEquals(setOf(firstId), tracker.ids)
        assertEquals(RegularPaymentDismissals.Observation(false, false), tracker.observe(response(emptyList(), status = BlockStatus.NONE)))
        assertTrue(tracker.ids.isEmpty())
    }

    @Test fun `absence in another period is not a publication receipt`() {
        val tracker = RegularPaymentDismissals()
        tracker.begin(firstId, month)
        tracker.confirm(firstId)
        val year = AnalyticsParams(PeriodTypeCode.YEAR, PeriodKey("2026"), TransferMode.WITH)
        assertEquals(RegularPaymentDismissals.Observation(false, false), tracker.observe(response(emptyList(), year)))
        assertEquals(setOf(firstId), tracker.ids)
        assertTrue(tracker.observe(response(listOf(firstId), year)).poll)
        assertTrue(tracker.observe(response(listOf(firstId))).poll)
        tracker.observe(response(emptyList()))
        assertTrue(tracker.ids.isEmpty())
    }

    @Test fun `reads stop after ten responses and explicit read retry replenishes only that budget`() {
        val tracker = RegularPaymentDismissals()
        tracker.begin(firstId, month)
        tracker.confirm(firstId)
        repeat(9) { assertTrue(tracker.observe(response(listOf(firstId))).poll) }
        assertEquals(RegularPaymentDismissals.Observation(true, false), tracker.observe(response(listOf(firstId))))
        assertFalse(tracker.begin(firstId, month), "timeout must never replay the successful PUT")
        tracker.retryRead()
        assertTrue(tracker.observe(response(listOf(firstId))).poll)
    }

    @Test fun `unavailable missing or malformed cards cannot acknowledge publication`() {
        val tracker = RegularPaymentDismissals()
        tracker.begin(firstId, month)
        tracker.confirm(firstId)
        assertTrue(tracker.observe(response(emptyList(), status = BlockStatus.UNAVAILABLE)).awaiting)
        assertTrue(tracker.observe(response(emptyList()).copy(blocks = emptyList())).awaiting)
        assertTrue(tracker.observe(response(emptyList()).copy(blocks = listOf(Block(id = "regular_payments", type = BlockType.INSIGHT_CARD)))).awaiting)
        assertEquals(setOf(firstId), tracker.ids)
    }

    @Test fun `failed mutation allows the same id again and leaves other pending writes alone`() {
        val tracker = RegularPaymentDismissals()
        tracker.begin(firstId, month)
        tracker.begin(secondId, month)
        tracker.fail(firstId)
        assertEquals(setOf(secondId), tracker.ids)
        assertTrue(tracker.begin(firstId, month))
        tracker.confirm("unknown")
    }

    @Test fun `tolerant rendering cannot acknowledge malformed or incomplete payment lists`() {
        for (items in listOf("{}", "null", "[null]", "[{}]", "[{\"button\":{\"type\":\"dismiss_regular_payment\",\"payment_id\":\"$firstId\"}}]",
            "[{\"title\":\"One\",\"button\":{\"type\":\"dismiss_regular_payment\"}}]",
            "[{\"title\":\"One\",\"button\":{\"type\":\"dismiss_regular_payment\",\"payment_id\":123}}]",
            "[{\"title\":123,\"button\":{\"type\":\"dismiss_regular_payment\",\"payment_id\":\"$firstId\"}}]",
            "[{\"title\":\"Other\",\"button\":{\"type\":\"dismiss_regular_payment\",\"payment_id\":\"$secondId\"}},{}]")) {
            val tracker = RegularPaymentDismissals()
            tracker.begin(firstId, month)
            tracker.confirm(firstId)
            val valid = response(listOf(firstId))
            val card = valid.blocks.single()
            val malformed = valid.copy(blocks = listOf(card.copy(data = JsonObject(card.data!! + ("items" to ApiJson.parseToJsonElement(items))))))
            assertTrue(tracker.observe(malformed).poll, items)
            assertEquals(setOf(firstId), tracker.ids)
            assertFalse(tracker.begin(firstId, month), "malformed data must not unlock another PUT")
            assertTrue(tracker.observe(valid).poll)
            assertFalse(tracker.observe(response(listOf(secondId))).awaiting)
            assertTrue(tracker.ids.isEmpty())
        }
    }

    @Test fun `omitted items only acknowledges a valid none card`() {
        val tracker = RegularPaymentDismissals()
        tracker.begin(firstId, month)
        tracker.confirm(firstId)
        val valid = response(emptyList())
        val card = valid.blocks.single().let { it.copy(data = JsonObject(it.data!! - "items")) }
        assertTrue(tracker.observe(valid.copy(blocks = listOf(card))).poll)
        assertFalse(tracker.observe(valid.copy(blocks = listOf(card.copy(status = BlockStatus.NONE)))).awaiting)
        assertTrue(tracker.ids.isEmpty())
    }

    @Test fun `noncanonical payment identities never acknowledge absence or unlock another PUT`() {
        for (invalid in listOf(firstId.dropLast(1), " $firstId", "$firstId ", "$firstId\n", firstId.uppercase(),
            firstId.replace("-", ""), firstId.replaceFirst('e', 'g'))) {
            val tracker = RegularPaymentDismissals()
            tracker.begin(firstId, month)
            tracker.confirm(firstId)
            assertTrue(tracker.observe(response(listOf(invalid, secondId))).poll, invalid)
            assertEquals(setOf(firstId), tracker.ids)
            assertFalse(tracker.begin(firstId, month))
            assertTrue(tracker.observe(response(listOf(firstId, secondId))).poll)
            assertFalse(tracker.observe(response(listOf(secondId))).awaiting)
            assertTrue(tracker.ids.isEmpty())
        }
    }

    @Test fun `optional buttons and future actions do not block a complete publication`() {
        for (items in listOf(
            """[{"title":"Other"}]""",
            """[{"title":"Other","button":null}]""",
            """[{"title":"Other","button":{"type":"future_action","label":"Future","new_field":true}}]""",
            """[{"title":"Other","button":{"type":"open_assistant"}}]""",
        )) {
            val tracker = RegularPaymentDismissals()
            tracker.begin(firstId, month)
            tracker.confirm(firstId)
            val valid = response(listOf(firstId))
            val card = valid.blocks.single()
            val published = valid.copy(blocks = listOf(card.copy(data = JsonObject(card.data!! + ("items" to ApiJson.parseToJsonElement(items))))))
            assertFalse(tracker.observe(published).awaiting, items)
            assertTrue(tracker.ids.isEmpty())
        }
    }
}
