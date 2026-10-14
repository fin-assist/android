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
 *
 * What `UNKNOWN` means is the rule of each field (hide it, fall back to another value, show the server's ready
 * text) and is written in the field's KDoc, or in the enum's when every field using it follows the same rule. A
 * closed `enum` is only for a set that cannot grow; anything the server may extend is an open code
 * (architecture.md, «Расширяемость API»).
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
