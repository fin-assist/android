package ru.finassist.pf.core.api.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder

/**
 * A list whose broken element is skipped instead of failing the whole response (AGENTS.md, Code: «a broken element
 * of a list is skipped»). For lists added since FIN-83; the older ones are FIN-84.
 */
abstract class TolerantListSerializer<T>(private val element: KSerializer<T>) : KSerializer<List<T>> {
    private val list = ListSerializer(element)

    override val descriptor: SerialDescriptor = list.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) = list.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as? JsonDecoder ?: return list.deserialize(decoder)
        val array = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            try {
                json.json.decodeFromJsonElement(element, item)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
