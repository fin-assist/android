package ru.finassist.pf.core.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * The one JSON configuration for the API contract: snake_case on the wire, camelCase in Kotlin; unknown
 * fields ignored (forward compatibility); absent optional fields decode as `null`; `null` is never written
 * in requests (api.md: "в запросах необязательное поле просто не передаётся").
 */
@OptIn(ExperimentalSerializationApi::class)
val ApiJson: Json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = false
}
