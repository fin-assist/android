@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ru.finassist.pf.core.api.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import ru.finassist.pf.core.common.error.OptionalBooleanSerializer
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

/**
 * Why an answer failed (api.md 7.2, 7.3). The client neither builds a text from it nor decides on «Повторить» by it:
 * that is what `text` / `error_text` and `retryable` are for (FIN-159). The code is for analytics and logs and for the
 * fallback rule when `retryable` is absent ([answerRetryable]). An unknown code follows the text and `retryable`, like
 * a known one.
 */
@Serializable(with = AnswerErrorCode.Serializer::class)
enum class AnswerErrorCode(override val code: String) : ApiCode {
    MODEL_ERROR("MODEL_ERROR"),
    CANNOT_ANSWER("CANNOT_ANSWER"),
    /** The question is not about the user's data or personal finance (FIN-159); not retryable, refunded. */
    OFF_TOPIC("OFF_TOPIC"),
    /** The gateway does not know the assistant service's reason; text and `retryable` still come from the service. */
    INTERNAL_ERROR("INTERNAL_ERROR"),
    UNKNOWN("?");

    object Serializer : OpenEnumSerializer<AnswerErrorCode>("AnswerErrorCode", entries, UNKNOWN)
}

/**
 * Whether «Повторить» is offered under a failed answer (api.md 7.2, 7.3): the server's [retryable]; when a server older
 * than FIN-159 does not send it, only for [AnswerErrorCode.MODEL_ERROR]. No code (an unknown status shown as failed) —
 * no retry.
 */
fun answerRetryable(code: AnswerErrorCode?, retryable: Boolean?): Boolean = retryable ?: (code == AnswerErrorCode.MODEL_ERROR)

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
) {
    /** Analytics chips stay calendar-only until the assistant negotiates additional period capabilities. */
    val isSupported: Boolean get() = when (screen) {
        ChipScreen.OPERATIONS -> true
        ChipScreen.ANALYTICS -> params?.let {
            it.isValidSelection && when (it.period) {
                PeriodTypeCode.MONTH, PeriodTypeCode.QUARTER, PeriodTypeCode.YEAR -> true
                else -> false
            }
        } == true
        ChipScreen.UNKNOWN -> false
    }
}

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

@Serializable(with = MessageSerializer::class)
data class Message(
    val id: String,
    val role: MessageRole,
    val text: String? = null,
    val status: AnswerStatus? = null,
    /** Only for `failed`: why (see [AnswerErrorCode]). */
    val errorCode: AnswerErrorCode? = null,
    /** Only for `failed`: the server's text shown instead of the answer; absent — the client's generic text (FIN-159). */
    val errorText: String? = null,
    /** Only for `failed`: whether «Повторить» is offered; absent — [answerRetryable] falls back to the code (FIN-159). */
    @Serializable(with = OptionalBooleanSerializer::class)
    val retryable: Boolean? = null,
    val charged: Boolean? = null,
    val blocks: List<Block>? = null,
    val chips: List<Chip>? = null,
    val source: AnswerSource? = null,
    /** Currency of the amounts in [blocks] (FIN-82). */
    val currency: String = "RUB",
    val createdAt: ApiDateTime,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val errorUserMessage: UserMessage = UserMessage.Absent,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val errorSeverity: ErrorSeverity = ErrorSeverity.ERROR,
    @Transient val errorRawCode: String? = errorCode?.code,
) {
    val errorPresentation: ErrorPresentation get() = ErrorPresentation(errorUserMessage, errorSeverity)
}

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

/**
 * Terminal `error` event (api.md 7.2). [text] is shown instead of the answer (absent from a server older than FIN-159 —
 * the client's generic text); [retryable] decides on «Повторить» ([answerRetryable]); [message] is for logs only.
 */
@Serializable(with = AnswerErrorEventSerializer::class)
data class AnswerErrorEvent(
    val code: AnswerErrorCode,
    val message: String,
    val remainingLimit: Int,
    val text: String? = null,
    @Serializable(with = OptionalBooleanSerializer::class)
    val retryable: Boolean? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val userMessage: UserMessage = UserMessage.Absent,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val severity: ErrorSeverity = ErrorSeverity.ERROR,
    @Transient val rawCode: String = code.code,
) {
    val presentation: ErrorPresentation get() = ErrorPresentation(userMessage, severity)
}

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
