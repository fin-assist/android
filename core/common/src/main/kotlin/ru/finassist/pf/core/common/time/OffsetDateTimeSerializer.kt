package ru.finassist.pf.core.common.time

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Wire format of moments: ISO 8601 with offset and always with seconds, `2026-09-15T14:30:00+03:00`. */
val API_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

fun OffsetDateTime.toApiString(): String = format(API_DATE_TIME)

/** Inverse of [toApiString]; accepts any ISO-8601 offset date-time. */
fun parseApiDateTime(value: String): OffsetDateTime = OffsetDateTime.parse(value)

fun String.toApiDateTime(): OffsetDateTime = OffsetDateTime.parse(this, DateTimeFormatter.ISO_OFFSET_DATE_TIME)

/** ISO 8601 with offset, as the API sends moments: `2026-09-15T14:30:00+03:00`. */
object OffsetDateTimeSerializer : KSerializer<OffsetDateTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("OffsetDateTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OffsetDateTime) {
        encoder.encodeString(value.toApiString())
    }

    override fun deserialize(decoder: Decoder): OffsetDateTime =
        OffsetDateTime.parse(decoder.decodeString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

typealias ApiDateTime = @kotlinx.serialization.Serializable(with = OffsetDateTimeSerializer::class) OffsetDateTime
