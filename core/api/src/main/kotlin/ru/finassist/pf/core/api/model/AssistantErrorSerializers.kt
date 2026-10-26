@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ru.finassist.pf.core.api.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import ru.finassist.pf.core.common.error.OptionalBooleanSerializer
import ru.finassist.pf.core.common.time.ApiDateTime

@Serializable
private data class MessageWire(
    val id: String,
    val role: MessageRole,
    val text: String? = null,
    val status: AnswerStatus? = null,
    /** Only for `failed`: why (see [AnswerErrorCode]). */
    val errorCode: AnswerErrorCode? = null,
    /** Only for `failed`: the server's text shown instead of the answer; absent — the client's generic text (FIN-159). */
    val errorText: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val errorUserMessage: UserMessage = UserMessage.Absent,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val errorSeverity: ErrorSeverity = ErrorSeverity.ERROR,
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
)

object MessageSerializer : KSerializer<Message> {
    override val descriptor = MessageWire.serializer().descriptor
    override fun deserialize(decoder: Decoder): Message {
        val input = decoder as JsonDecoder
        val element = input.decodeJsonElement().jsonObject
        val wire = input.json.decodeFromJsonElement(MessageWire.serializer(), element)
        return Message(
            id = wire.id,
            role = wire.role,
            text = wire.text,
            status = wire.status,
            errorCode = wire.errorCode,
            errorText = wire.errorText,
            errorUserMessage = wire.errorUserMessage,
            errorSeverity = wire.errorSeverity,
            retryable = wire.retryable,
            charged = wire.charged,
            blocks = wire.blocks,
            chips = wire.chips,
            source = wire.source,
            currency = wire.currency,
            createdAt = wire.createdAt,
            errorRawCode = (element["error_code"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
    }
    override fun serialize(encoder: Encoder, value: Message) {
        val output = encoder as JsonEncoder
        val wire = MessageWire(
            id = value.id,
            role = value.role,
            text = value.text,
            status = value.status,
            errorCode = value.errorCode,
            errorText = value.errorText,
            errorUserMessage = value.errorUserMessage,
            errorSeverity = value.errorSeverity,
            retryable = value.retryable,
            charged = value.charged,
            blocks = value.blocks,
            chips = value.chips,
            source = value.source,
            currency = value.currency,
            createdAt = value.createdAt,
        )
        val element = output.json.encodeToJsonElement(MessageWire.serializer(), wire).jsonObject.toMutableMap()
        value.errorRawCode?.let { element["error_code"] = JsonPrimitive(it) }
        output.encodeJsonElement(JsonObject(element))
    }
}

@Serializable
private data class AnswerErrorEventWire(
    val code: AnswerErrorCode,
    val message: String,
    val remainingLimit: Int,
    val text: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val userMessage: UserMessage = UserMessage.Absent,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val severity: ErrorSeverity = ErrorSeverity.ERROR,
    @Serializable(with = OptionalBooleanSerializer::class)
    val retryable: Boolean? = null,
)

object AnswerErrorEventSerializer : KSerializer<AnswerErrorEvent> {
    override val descriptor = AnswerErrorEventWire.serializer().descriptor
    override fun deserialize(decoder: Decoder): AnswerErrorEvent {
        val input = decoder as JsonDecoder
        val element = input.decodeJsonElement().jsonObject
        val wire = input.json.decodeFromJsonElement(AnswerErrorEventWire.serializer(), element)
        return AnswerErrorEvent(
            code = wire.code,
            message = wire.message,
            remainingLimit = wire.remainingLimit,
            text = wire.text,
            userMessage = wire.userMessage,
            severity = wire.severity,
            retryable = wire.retryable,
            rawCode = (element["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: wire.code.code,
        )
    }
    override fun serialize(encoder: Encoder, value: AnswerErrorEvent) {
        val output = encoder as JsonEncoder
        val wire = AnswerErrorEventWire(
            code = value.code,
            message = value.message,
            remainingLimit = value.remainingLimit,
            text = value.text,
            userMessage = value.userMessage,
            severity = value.severity,
            retryable = value.retryable,
        )
        val element = output.json.encodeToJsonElement(AnswerErrorEventWire.serializer(), wire).jsonObject.toMutableMap()
        value.rawCode?.let { element["code"] = JsonPrimitive(it) }
        output.encodeJsonElement(JsonObject(element))
    }
}
