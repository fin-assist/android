package ru.finassist.pf.core.api.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Open code set (`x-extensible-enum` in openapi.yaml): a value the client does not know must not break
 * parsing. Each such enum has an `UNKNOWN` entry; [OpenEnumSerializer] maps unrecognised strings to it and
 * keeps the raw value in [ApiCode.code] semantics out of the way of the UI.
 */
interface ApiCode {
    val code: String
}

abstract class OpenEnumSerializer<E>(
    name: String,
    private val values: List<E>,
    private val unknown: E,
) : KSerializer<E> where E : Enum<E>, E : ApiCode {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(value.code)

    override fun deserialize(decoder: Decoder): E {
        val code = decoder.decodeString()
        return values.firstOrNull { it.code == code } ?: unknown
    }
}
