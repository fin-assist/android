package ru.finassist.pf.core.api.model

import kotlinx.serialization.Serializable
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.ApiDateTime
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import java.util.UUID

/** `Idempotency-Key`: one UUID per logical user action, kept until the final response. */
@Serializable(with = IdempotencyKey.Serializer::class)
class IdempotencyKey private constructor(
    val value: String,
    val replayWindow: ru.finassist.pf.core.common.time.RequestReplayWindow?,
) {
    /** Restored/external keys have unknown age. Never stamp them as newly created. */
    constructor(value: String) : this(value, null)
    override fun equals(other: Any?): Boolean = other is IdempotencyKey && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "IdempotencyKey"

    companion object {
        fun random() = IdempotencyKey(UUID.randomUUID().toString())
        fun random(clock: ru.finassist.pf.core.common.time.ElapsedClock) = IdempotencyKey(
            UUID.randomUUID().toString(),
            ru.finassist.pf.core.common.time.RequestReplayWindow(clock.nowMillis(), 24 * 60 * 60 * 1000L),
        )
    }
    object Serializer : kotlinx.serialization.KSerializer<IdempotencyKey> {
        override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("IdempotencyKey", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
        override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: IdempotencyKey) = encoder.encodeString(value.value)
        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder) = IdempotencyKey(decoder.decodeString())
    }
}

/** Period type for Analytics (`period` query). Open set; the client only requests the values it knows. */
@Serializable(with = PeriodTypeCode.Serializer::class)
enum class PeriodTypeCode(override val code: String) : ApiCode {
    MONTH("month"), QUARTER("quarter"), YEAR("year"), ALL_TIME("all_time"), ROLLING_YEAR("rolling_year"), ROLLING_QUARTER("rolling_quarter"), UNKNOWN("?");

    val isRolling: Boolean get() = this == ROLLING_YEAR || this == ROLLING_QUARTER

    object Serializer : OpenEnumSerializer<PeriodTypeCode>("PeriodType", entries, UNKNOWN)
}

/**
 * Transfer accounting mode. In responses an unknown value is treated as [WITH]; a missing field in an
 * [OperationsFilter] means "no analytics rules at all", which is not the same as [WITH].
 */
@Serializable(with = TransferMode.Serializer::class)
enum class TransferMode(override val code: String) : ApiCode {
    WITH("with"), WITHOUT("without"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<TransferMode>("TransferMode", entries, UNKNOWN)

    /** Rule from api.md: unknown value in a response is handled as `with`. */
    val effective: TransferMode get() = if (this == UNKNOWN) WITH else this
}

/**
 * `kind` of an [OperationsFilter]: `expense` / `income`. Open code in responses (api.md «operations_filter», FIN-82): a
 * kind this client does not know is kept as is — shown with the «Как на „Аналитике“» chip and sent back unchanged,
 * never dropped (dropping it would silently widen the search).
 */
@JvmInline
@Serializable
value class OperationKindFilter(val code: String) {
    val isKnown: Boolean get() = this == EXPENSE || this == INCOME

    companion object {
        val EXPENSE = OperationKindFilter("expense")
        val INCOME = OperationKindFilter("income")
    }
}

@Serializable(with = Coverage.Serializer::class)
enum class Coverage(override val code: String) : ApiCode {
    COMPLETE("complete"), PARTIAL("partial"), NO_DATA("no_data"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<Coverage>("Coverage", entries, UNKNOWN)

    /** Unknown coverage is shown as partial (api.md). */
    val effective: Coverage get() = if (this == UNKNOWN) PARTIAL else this
}

/**
 * Search parameters (`listOperations`). Arrives from Analytics tiles, assistant chips and the import result.
 * [selectionName] is display-only and never sent.
 */
@Serializable
data class OperationsFilter(
    val from: ApiDateTime? = null,
    val to: ApiDateTime? = null,
    val q: String? = null,
    val categoryId: String? = null,
    val kind: OperationKindFilter? = null,
    val amountFrom: Money? = null,
    val amountTo: Money? = null,
    val transferMode: TransferMode? = null,
    val selection: String? = null,
    val selectionName: String? = null,
) {
    val isEmpty: Boolean
        get() = from == null && to == null && q.isNullOrBlank() && categoryId == null && kind == null &&
            amountFrom == null && amountTo == null && transferMode == null && selection == null
}

/** Parameters of the Analytics screen; all three are always present in responses. */
@Serializable
data class AnalyticsParams(
    val period: PeriodTypeCode,
    val date: PeriodKey,
    val transferMode: TransferMode,
) {
    /** Unknown types and mismatched keys cannot be submitted as a known analytics selection. */
    val isValidSelection: Boolean get() = PeriodKey.isValid(date.value) && when (period) {
        PeriodTypeCode.MONTH -> date.type == ru.finassist.pf.core.common.time.PeriodType.MONTH
        PeriodTypeCode.QUARTER -> date.type == ru.finassist.pf.core.common.time.PeriodType.QUARTER
        PeriodTypeCode.YEAR -> date.type == ru.finassist.pf.core.common.time.PeriodType.YEAR
        PeriodTypeCode.ALL_TIME -> date == PeriodKey.ALL_TIME
        PeriodTypeCode.UNKNOWN -> false
    }
}

typealias ApiRange = DateRange
