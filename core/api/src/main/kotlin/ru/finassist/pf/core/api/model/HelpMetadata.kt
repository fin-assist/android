package ru.finassist.pf.core.api.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

object HelpTagsSerializer : KSerializer<List<String>> by ListSerializer(String.serializer()) {
    override fun deserialize(decoder: Decoder): List<String> {
        val input = decoder as? JsonDecoder ?: return ListSerializer(String.serializer()).deserialize(decoder)
        val tags = input.decodeJsonElement() as? JsonArray ?: return emptyList()
        return tags.mapNotNull { tag -> (tag as? JsonPrimitive)?.takeIf { it.isString }?.content }
    }
}

/** Invalid optional metadata leaves the containing block or tile and its manual help intact. */
@OptIn(ExperimentalSerializationApi::class)
object HelpOrderSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = Int.serializer().descriptor.nullable

    override fun deserialize(decoder: Decoder): Int? {
        val value = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
        return value?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 }
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeInt(value)
        }
    }
}
