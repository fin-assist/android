package ru.finassist.pf.core.common.error

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Presence is retained: a malformed canonical value must never resurrect legacy text. */
@Serializable(with = UserMessage.Serializer::class)
sealed interface UserMessage {
    data object Absent : UserMessage
    data object Invalid : UserMessage
    @ConsistentCopyVisibility
    data class Safe internal constructor(val text: String) : UserMessage

    companion object {
        fun from(value: String?): UserMessage = if (value != null && isSafeUserMessage(value)) Safe(value) else Invalid
    }

    object Serializer : KSerializer<UserMessage> {
        override val descriptor = PrimitiveSerialDescriptor("UserMessage", PrimitiveKind.STRING).nullable
        override fun deserialize(decoder: Decoder): UserMessage {
            val value = (decoder as JsonDecoder).decodeJsonElement()
            return from((value as? JsonPrimitive)?.takeIf { it.isString }?.content)
        }
        override fun serialize(encoder: Encoder, value: UserMessage) {
            (encoder as JsonEncoder).encodeJsonElement(if (value is Safe) JsonPrimitive(value.text) else JsonNull)
        }
    }
}

/** Exact contract Unicode White_Space, not a platform trim/isBlank predicate. */
fun isSafeUserMessage(value: String): Boolean {
    var count = 0
    var nonWhitespace = false
    var index = 0
    while (index < value.length) {
        val char = value[index++].code
        val point = when (char) {
            in 0xD800..0xDBFF -> {
                if (index >= value.length) return false
                val low = value[index++].code
                if (low !in 0xDC00..0xDFFF) return false
                0x10000 + ((char - 0xD800) shl 10) + low - 0xDC00
            }
            in 0xDC00..0xDFFF -> return false
            else -> char
        }
        if (++count > 1000) return false
        if (point !in 0x0009..0x000D && point != 0x0020 && point != 0x0085 && point != 0x00A0 &&
            point != 0x1680 && point !in 0x2000..0x200A && point != 0x2028 && point != 0x2029 &&
            point != 0x202F && point != 0x205F && point != 0x3000) nonWhitespace = true
    }
    return count > 0 && nonWhitespace
}

@Serializable(with = ErrorSeverity.Serializer::class)
enum class ErrorSeverity {
    INFO, WARNING, ERROR;
    object Serializer : KSerializer<ErrorSeverity> {
        override val descriptor = PrimitiveSerialDescriptor("ErrorSeverity", PrimitiveKind.STRING)
        override fun deserialize(decoder: Decoder): ErrorSeverity {
            val value = (decoder as JsonDecoder).decodeJsonElement()
            return when ((value as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                "info" -> INFO
                "warning" -> WARNING
                else -> ERROR
            }
        }
        override fun serialize(encoder: Encoder, value: ErrorSeverity) = encoder.encodeString(value.name.lowercase())
    }
}

data class ErrorPresentation(
    val userMessage: UserMessage = UserMessage.Absent,
    val severity: ErrorSeverity = ErrorSeverity.ERROR,
) {
    /** Fallbacks are controlled feature strings; diagnostic exception/API messages never belong here. */
    fun textOrFallback(fallback: String, legacySafeText: String? = null): String = when (val message = userMessage) {
        is UserMessage.Safe -> message.text
        UserMessage.Absent -> legacySafeText?.takeIf(::isSafeUserMessage) ?: fallback
        UserMessage.Invalid -> fallback
    }
}

/** A malformed optional boolean affects only itself; it never discards code or corrective metadata. */
object OptionalBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor = PrimitiveSerialDescriptor("OptionalBoolean", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Boolean? {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return (element as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    }
    override fun serialize(encoder: Encoder, value: Boolean?) {
        (encoder as JsonEncoder).encodeJsonElement(value?.let(::JsonPrimitive) ?: JsonNull)
    }
}
