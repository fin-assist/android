package ru.finassist.pf.core.api.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.intOrNull

/** A mutation is atomic: a malformed envelope cannot be dropped or acquire a default schema version. */
object CompleteBlockList : JsonTransformingSerializer<List<Block>>(ListSerializer(Block.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val blocks = element as? JsonArray ?: throw SerializationException("Expected complete blocks")
        for (value in blocks) {
            val block = value as? JsonObject ?: throw SerializationException("Expected block")
            val version = block["v"] as? JsonPrimitive
            val type = block["type"] as? JsonPrimitive
            if (version == null || version.isString || version.intOrNull?.let { it > 0 } != true || type?.isString != true) {
                throw SerializationException("Invalid block envelope")
            }
        }
        return element
    }
}
