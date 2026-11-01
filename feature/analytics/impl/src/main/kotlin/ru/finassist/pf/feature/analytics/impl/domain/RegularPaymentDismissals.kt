package ru.finassist.pf.feature.analytics.impl.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.InsightCardData

/** A successful dismissal precedes its asynchronously published analytics projection. */
internal class RegularPaymentDismissals {
    private data class Pending(val origin: AnalyticsParams?, var confirmed: Boolean = false, var reads: Int = 0)
    private val pending = mutableMapOf<String, Pending>()
    val ids: Set<String> get() = pending.keys.toSet()

    fun begin(id: String, origin: AnalyticsParams?): Boolean {
        if (id in pending) return false
        pending[id] = Pending(origin)
        return true
    }

    fun confirm(id: String) { pending[id]?.confirmed = true }
    fun fail(id: String) { pending.remove(id) }
    fun retryRead() { pending.values.forEach { it.reads = 0 } }

    data class Observation(val awaiting: Boolean, val poll: Boolean)

    /** Only the originating period can acknowledge absence; another period may never have had this payment. */
    fun observe(data: Analytics, requested: AnalyticsParams? = data.params): Observation {
        val card = data.blocks.firstOrNull { it.id == "regular_payments" && it.type == BlockType.INSIGHT_CARD && it.v == 1 }
        val details = card?.decode(InsightCardData.serializer())
        val visible = details?.items.orEmpty().mapNotNull { it.button?.takeIf { action -> action.type == ActionType.DISMISS_REGULAR_PAYMENT }?.paymentId }.toSet()
        val authoritative = data.hasData && data.state?.recalculating != true && completePayments(card, details) &&
            card?.status?.effective in setOf(BlockStatus.READY, BlockStatus.TENTATIVE, BlockStatus.NONE)
        val context = data.params ?: requested
        val relevant = pending.filter { (id, receipt) -> receipt.confirmed && (receipt.origin == context || id in visible) }
        var awaiting = false
        var poll = false
        relevant.forEach { (id, receipt) ->
            if (authoritative && receipt.origin == context && id !in visible) {
                pending.remove(id)
            } else {
                awaiting = true
                receipt.reads++
                poll = poll || receipt.reads < MAX_READS
            }
        }
        return Observation(awaiting, poll)
    }

    /** Rendering may drop malformed rows; a write receipt must never interpret that loss as absence. */
    private fun completePayments(card: Block?, details: InsightCardData?): Boolean {
        if (card == null || details == null) return false
        val raw = card.data?.get("items")
        // `none` carries only icon/title; ready/tentative must provide the actual list.
        if (raw == null) return card.status == BlockStatus.NONE && details.items.isEmpty()
        if (raw !is JsonArray || raw.size != details.items.size) return false
        return raw.all { element ->
            val item = element as? JsonObject
            val title = item?.get("title") as? JsonPrimitive
            val button = item?.get("button") as? JsonObject
            val type = button?.get("type") as? JsonPrimitive
            val id = button?.get("payment_id") as? JsonPrimitive
            // String coercion is useful for rendering, but must not create a different payment identity.
            title?.isString == true && type?.isString == true && type.content == ActionType.DISMISS_REGULAR_PAYMENT.code &&
                id?.isString == true && id.content.isNotBlank()
        }
    }

    private companion object { const val MAX_READS = 10 }
}
