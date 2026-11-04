package ru.finassist.pf.core.api.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.SourceTimeZone

/** Whole-response invariants whose failure must preserve the last financial snapshot. */
object AnalyticsIntegrity {
    fun requireValid(response: Analytics) {
        val calculation = response.calculation ?: malformed()
        if (calculation.calcVersion < 0 || response.state?.recalculating?.let { it != calculation.recalculating } == true) malformed()
        if (response.hasData) {
            SourceTimeZone.require(response.timeZone)
            if (response.params?.isValidSelection != true || response.period == null || response.state == null) malformed()
            response.blocks.firstOrNull { it.id == "monthly_chart" && it.type == BlockType.CHART && it.v == 1 && it.status.hasData }?.let(::chart)
        }
    }

    fun requireValid(update: AnalyticsBlockUpdate) {
        SourceTimeZone.require(update.timeZone)
        if (update.requiredCalcVersion < 1 || update.params.period != PeriodTypeCode.MONTH || !update.params.isValidSelection ||
            !Regex("[A-Z]{3}").matches(update.currency) || update.blocks.size !in 1..16) malformed()
        val ids = update.blocks.map { it.id?.takeIf(String::isNotBlank) ?: malformed() }
        if (ids.distinct().size != ids.size || "regular_payments" !in ids) malformed()
        update.blocks.forEach { block ->
            // Unknown catalog versions/types retain their normal fallback. Known replacements are complete,
            // not the tolerant GET list where dropping an item is an intentional display degradation.
            if (block.id == "regular_payments" && block.type == BlockType.INSIGHT_CARD && block.v == 1) {
                val data = block.decode(InsightCardData.serializer()) ?: malformed()
                if (data.icon.isBlank() || data.title.isBlank()) malformed()
                val rawItems = block.data?.get("items")
                if (rawItems != null || block.status.hasData) {
                    val items = rawItems as? JsonArray ?: malformed()
                    try {
                        ApiJson.decodeFromJsonElement(ListSerializer(InsightItem.serializer()), items)
                    } catch (_: IllegalArgumentException) { malformed() }
                }
                if (block.status.hasData && (data.value == null || data.valueKind == null || data.description == null)) malformed()
            }
        }
    }

    fun chart(block: Block): ChartData {
        if (block.id != "monthly_chart" || block.type != BlockType.CHART || block.v != 1 || block.status != BlockStatus.READY) malformed()
        val data = block.decode(ChartData.serializer()) ?: malformed()
        val page = data.page ?: malformed()
        if (data.kind != ChartKind.BAR || data.valueKind != ValueKind.AMOUNT || data.points.size !in 1..60 ||
            page.revision.length !in 1..128 || listOfNotNull(page.previous, page.next).any { it.length !in 1..2048 } ||
            (block.data?.get("points") as? JsonArray)?.size != data.points.size) malformed()
        return data
    }

    private fun malformed(): Nothing = throw AppError.Server(500)
}
