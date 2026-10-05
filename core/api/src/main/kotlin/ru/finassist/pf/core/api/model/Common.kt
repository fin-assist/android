package ru.finassist.pf.core.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.ApiDateTime
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import java.util.UUID

/** `Idempotency-Key`: one UUID per logical user action, kept until the final response. */
@JvmInline
@Serializable
value class IdempotencyKey(val value: String) {
    companion object {
        fun random() = IdempotencyKey(UUID.randomUUID().toString())
    }
}

/** Period type for Analytics (`period` query). Open set; the client only requests the values it knows. */
@Serializable(with = PeriodTypeCode.Serializer::class)
enum class PeriodTypeCode(override val code: String) : ApiCode {
    MONTH("month"), QUARTER("quarter"), YEAR("year"), UNKNOWN("?");

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

/** Closed set: `expense` / `income` filter in Search. */
@Serializable
enum class OperationKindFilter {
    @SerialName("expense") EXPENSE,
    @SerialName("income") INCOME,
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
)

typealias ApiRange = DateRange
