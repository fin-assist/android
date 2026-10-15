package ru.finassist.pf.core.api.model

import kotlinx.serialization.Serializable
import ru.finassist.pf.core.common.time.ApiDateTime
import ru.finassist.pf.core.common.time.DateRange

@Serializable
data class AskRequest(
    val text: String,
    val transferMode: TransferMode? = null,
)

@Serializable
data class AskResponse(
    val question: Message,
    val answerId: String,
    val remainingLimit: Int,
)

@Serializable
data class RetryResponse(
    val answerId: String,
    val remainingLimit: Int,
)

@Serializable
data class AssistantLimit(
    val remaining: Int,
    val dailyMax: Int,
    val resetsAt: ApiDateTime,
)

@Serializable(with = MessageRole.Serializer::class)
enum class MessageRole(override val code: String) : ApiCode {
    USER("user"), ASSISTANT("assistant"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<MessageRole>("MessageRole", entries, UNKNOWN)
}

@Serializable(with = AnswerStatus.Serializer::class)
enum class AnswerStatus(override val code: String) : ApiCode {
    GENERATING("generating"), COMPLETE("complete"), FAILED("failed"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<AnswerStatus>("AnswerStatus", entries, UNKNOWN)

    /** Unknown status is treated as failed (api.md). */
    val effective: AnswerStatus get() = if (this == UNKNOWN) FAILED else this
}

@Serializable(with = AnswerErrorCode.Serializer::class)
enum class AnswerErrorCode(override val code: String) : ApiCode {
    MODEL_ERROR("MODEL_ERROR"), CANNOT_ANSWER("CANNOT_ANSWER"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<AnswerErrorCode>("AnswerErrorCode", entries, UNKNOWN)

    /** Unknown code behaves like MODEL_ERROR: "Повторить" is offered. */
    val effective: AnswerErrorCode get() = if (this == UNKNOWN) MODEL_ERROR else this
}

@Serializable(with = ChipScreen.Serializer::class)
enum class ChipScreen(override val code: String) : ApiCode {
    OPERATIONS("operations"), ANALYTICS("analytics"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<ChipScreen>("ChipScreen", entries, UNKNOWN)
}

@Serializable
data class Chip(
    val label: String,
    val screen: ChipScreen,
    val filters: OperationsFilter? = null,
    val params: AnalyticsParams? = null,
)

@Serializable(with = ChangedSince.Serializer::class)
enum class ChangedSince(override val code: String) : ApiCode {
    CATEGORIES("categories"), UPLOADS("uploads"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<ChangedSince>("ChangedSince", entries, UNKNOWN)
}

@Serializable
data class AnswerSource(
    val calculatedAt: ApiDateTime,
    val dataRange: DateRange? = null,
    val transferMode: TransferMode,
    val changedSince: List<ChangedSince>,
    /** Ready text about the same; shown when a code is unknown (api.md 7.2, FIN-82). */
    val changedSinceText: String? = null,
)

@Serializable
data class Message(
    val id: String,
    val role: MessageRole,
    val text: String? = null,
    val status: AnswerStatus? = null,
    val errorCode: AnswerErrorCode? = null,
    val charged: Boolean? = null,
    val blocks: List<Block>? = null,
    val chips: List<Chip>? = null,
    val source: AnswerSource? = null,
    /** Currency of the amounts in [blocks] (FIN-82). */
    val currency: String = "RUB",
    val createdAt: ApiDateTime,
)

@Serializable
data class MessagesList(
    val messages: List<Message>,
    val hasOlder: Boolean,
)

// ---- stream events (`streamAnswer`) ----

@Serializable
data class AnswerSnapshotEvent(
    val status: AnswerStatus,
    val blocks: List<Block>,
    val chips: List<Chip>,
    val source: AnswerSource? = null,
    /** Currency of the amounts in [blocks] (FIN-82). */
    val currency: String = "RUB",
)

@Serializable
data class AnswerChunkEvent(val index: Int, val text: String)

@Serializable
data class AnswerBlockEvent(val index: Int, val block: Block)

@Serializable
data class AnswerDoneEvent(val charged: Boolean, val remainingLimit: Int)

@Serializable
data class AnswerErrorEvent(val code: AnswerErrorCode, val message: String, val remainingLimit: Int)

/** Typed view of the answer stream. [Unknown] carries event types this client does not know (ignored by UI). */
sealed interface AnswerEvent {
    data class Snapshot(val event: AnswerSnapshotEvent) : AnswerEvent
    data class Chunk(val event: AnswerChunkEvent) : AnswerEvent
    data class BlockAdded(val event: AnswerBlockEvent) : AnswerEvent
    data class Source(val event: AnswerSource) : AnswerEvent
    data class ChipAdded(val event: Chip) : AnswerEvent
    data class Done(val event: AnswerDoneEvent) : AnswerEvent
    data class Error(val event: AnswerErrorEvent) : AnswerEvent
    data class Unknown(val type: String) : AnswerEvent
}
