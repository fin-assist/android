package ru.finassist.pf.core.api.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.ApiDateTime
import ru.finassist.pf.core.common.time.DateRange

/**
 * Primitive of the block catalog (api.md «Блоки», FIN-4) — the Analytics screen and the assistant's answers. Open code:
 * a type this client cannot draw is [UNKNOWN] and is shown by its `alt_text` or skipped.
 */
@Serializable(with = BlockType.Serializer::class)
enum class BlockType(override val code: String) : ApiCode {
    HEADING("heading"),
    TEXT("text"),
    ROWS("rows"),
    CHART("chart"),
    TILE_GROUP("tile_group"),
    RANKED_LIST("ranked_list"),
    INSIGHT_CARD("insight_card"),
    UNKNOWN("?");

    object Serializer : OpenEnumSerializer<BlockType>("BlockType", entries, UNKNOWN)
}

@Serializable(with = BlockStatus.Serializer::class)
enum class BlockStatus(override val code: String) : ApiCode {
    READY("ready"), TENTATIVE("tentative"), LOCKED("locked"), NONE("none"), UNAVAILABLE("unavailable"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<BlockStatus>("BlockStatus", entries, UNKNOWN)

    /** An unknown status is shown as locked (api.md «Блоки»). */
    val effective: BlockStatus get() = if (this == UNKNOWN) LOCKED else this

    /** Has data to draw. */
    val hasData: Boolean get() = effective == READY || effective == TENTATIVE
}

@Serializable(with = LockReason.Serializer::class)
enum class LockReason(override val code: String) : ApiCode {
    NEED_FULL_MONTHS("need_full_months"),
    NEED_MONTHS_WITH_DATA("need_months_with_data"),
    TOO_EARLY_IN_MONTH("too_early_in_month"),
    STALE_DATA("stale_data"),
    UNKNOWN("?");

    object Serializer : OpenEnumSerializer<LockReason>("LockReason", entries, UNKNOWN)
}

/** Why a block or a tile is locked; [text] is shown as is, whatever the reason. */
@Serializable
data class BlockLock(
    val reason: LockReason,
    /** Empty or missing in a nested lock (a tile) — the client shows `BlockFormat.LOCKED_FALLBACK` (api.md «Блоки»). */
    val text: String = "",
    val required: Int? = null,
    val available: Int? = null,
    val availableFrom: ApiDateTime? = null,
)

@Serializable(with = ActionType.Serializer::class)
enum class ActionType(override val code: String) : ApiCode {
    OPEN_OPERATIONS("open_operations"),
    OPEN_ASSISTANT("open_assistant"),
    DISMISS_REGULAR_PAYMENT("dismiss_regular_payment"),
    UNKNOWN("?");

    object Serializer : OpenEnumSerializer<ActionType>("ActionType", entries, UNKNOWN)
}

/** An action from the fixed dictionary; an unknown one makes its element not clickable. */
@Serializable
data class BlockAction(
    val type: ActionType,
    val filter: OperationsFilter? = null,
    val paymentId: String? = null,
    val label: String? = null,
)

/** What a `value` is (api.md «Значения»). An unknown kind is not shown. */
@Serializable(with = ValueKind.Serializer::class)
enum class ValueKind(override val code: String) : ApiCode {
    AMOUNT("amount"), PERCENT("percent"), COUNT("count"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<ValueKind>("ValueKind", entries, UNKNOWN)
}

/**
 * Envelope of a block (api.md «Блоки»): [data] keeps the primitive's payload as JSON and is decoded by the renderer
 * of [type] — a payload that does not decode hides that block only.
 */
@Serializable
data class Block(
    /** Analytics only: the kill switch `analytics.block.<id>` and exposure events (FIN-9). */
    val id: String? = null,
    val type: BlockType,
    val v: Int = 1,
    val status: BlockStatus = BlockStatus.READY,
    val lock: BlockLock? = null,
    val title: String? = null,
    val altText: String? = null,
    val emptyText: String? = null,
    val data: JsonObject? = null,
    val action: BlockAction? = null,
) {
    /** [data] as [serializer], or null when absent or it does not decode. */
    fun <T> decode(serializer: KSerializer<T>): T? = data?.let {
        try {
            ApiJson.decodeFromJsonElement(serializer, it)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** The text of a `text` block (the assistant streams it chunk by chunk). */
    val text: String? get() = decode(TextData.serializer())?.text

    companion object {
        /** A block of [type] with [data] encoded the way the server sends it. */
        fun <T> of(type: BlockType, serializer: KSerializer<T>, data: T, v: Int = 1): Block =
            Block(type = type, v = v, data = ApiJson.encodeToJsonElement(serializer, data).jsonObject)

        fun text(text: String): Block = of(BlockType.TEXT, TextData.serializer(), TextData(text))
    }
}

/** A value with its kind; amounts are minor units of the response currency. */
@Serializable
data class BlockValue(
    val label: String? = null,
    val value: Double,
    val valueKind: ValueKind,
    val action: BlockAction? = null,
)

/** Amount of a value of kind `amount`; null for other kinds. */
fun amountOf(value: Double?, kind: ValueKind?): Money? = if (kind == ValueKind.AMOUNT && value != null) Money(value.toLong()) else null

@Serializable data class HeadingData(val text: String)

@Serializable data class TextData(val text: String)

@Serializable
data class RowsData(@Serializable(with = BlockRowList::class) val rows: List<BlockRow>)

/** Row «label — value»: [value] with [valueKind], or [text]; a row with neither is skipped. */
@Serializable
data class BlockRow(
    val label: String,
    val sublabel: String? = null,
    val value: Double? = null,
    val valueKind: ValueKind? = null,
    val text: String? = null,
    val action: BlockAction? = null,
)

object BlockRowList : TolerantListSerializer<BlockRow>(BlockRow.serializer())

@Serializable(with = ChartKind.Serializer::class)
enum class ChartKind(override val code: String) : ApiCode {
    BAR("bar"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<ChartKind>("ChartKind", entries, UNKNOWN)
}

@Serializable
data class ChartData(
    val kind: ChartKind,
    val valueKind: ValueKind,
    @Serializable(with = ChartPointList::class) val points: List<ChartPoint>,
    val highlightIndex: Int? = null,
    val showValues: Boolean? = null,
    val summary: BlockValue? = null,
    val caption: String? = null,
    val notes: List<ChartNote> = emptyList(),
)

@Serializable
data class ChartPoint(
    val range: DateRange,
    val value: Double? = null,
    val coverage: Coverage,
    val dataFrom: ApiDateTime? = null,
    val dataTo: ApiDateTime? = null,
    /** Caption of a partial bar («по 25-е»). */
    val note: String? = null,
)

object ChartPointList : TolerantListSerializer<ChartPoint>(ChartPoint.serializer())

@Serializable(with = NoteTone.Serializer::class)
enum class NoteTone(override val code: String) : ApiCode {
    PRIMARY("primary"), MUTED("muted"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<NoteTone>("NoteTone", entries, UNKNOWN)
}

/** A line under a chart. `tone` is always on the wire: absent it reads as `muted`, but the server sends it (api.md 6.1). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChartNote(val text: String, @EncodeDefault val tone: NoteTone = NoteTone.MUTED)

@Serializable
data class TileGroupData(@Serializable(with = TileList::class) val tiles: List<Tile>)

object TileList : TolerantListSerializer<Tile>(Tile.serializer())

@Serializable
data class Tile(
    val id: String,
    val title: String,
    val status: BlockStatus = BlockStatus.READY,
    val lock: BlockLock? = null,
    val value: Double? = null,
    val valueKind: ValueKind? = null,
    val approx: Boolean = false,
    val showPlus: Boolean = false,
    val caption: String? = null,
    val action: BlockAction? = null,
)

@Serializable
data class RankedListData(
    val total: BlockValue? = null,
    @Serializable(with = RankedItemList::class) val items: List<RankedItem>,
    val visibleCount: Int? = null,
    val moreLabel: String? = null,
    val emptyText: String? = null,
    val style: ListStyle = ListStyle.BARS,
)

/** `bars` — bars and shares; `plain` — rows without bars (incomes). Unknown — bars. */
@Serializable(with = ListStyle.Serializer::class)
enum class ListStyle(override val code: String) : ApiCode {
    BARS("bars"), PLAIN("plain"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<ListStyle>("ListStyle", entries, UNKNOWN)
}

@Serializable
data class RankedItem(
    val id: String? = null,
    val icon: String,
    val title: String,
    val note: String? = null,
    val value: Double,
    val valueKind: ValueKind,
    val share: Double? = null,
    val showPlus: Boolean = false,
    val action: BlockAction? = null,
)

object RankedItemList : TolerantListSerializer<RankedItem>(RankedItem.serializer())

@Serializable
data class InsightCardData(
    val icon: String,
    val title: String,
    val badge: String? = null,
    val value: Double? = null,
    val valueKind: ValueKind? = null,
    val approx: Boolean = false,
    val description: String? = null,
    @Serializable(with = InsightItemList::class) val items: List<InsightItem> = emptyList(),
    val itemsMore: BlockValue? = null,
)

@Serializable
data class InsightItem(
    val title: String,
    val sublabel: String? = null,
    val value: Double? = null,
    val valueKind: ValueKind? = null,
    /** «в месяц» after the value. */
    val valueSuffix: String? = null,
    val muted: Boolean = false,
    /** Tap on the row: only `open_*` — anything else on a row is ignored (`BlockLayout.tapAction`). */
    val action: BlockAction? = null,
    /** A button under the row with its `label` («Не подписка»); a row may have both. */
    val button: BlockAction? = null,
)

object InsightItemList : TolerantListSerializer<InsightItem>(InsightItem.serializer())

/** Blocks of a response: a block that does not even parse as an envelope is dropped, not the screen (FIN-7). */
object BlockList : TolerantListSerializer<Block>(Block.serializer())
