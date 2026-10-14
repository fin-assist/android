package ru.finassist.pf.core.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * The one JSON configuration for the API contract: snake_case on the wire, camelCase in Kotlin; unknown
 * fields ignored (forward compatibility); absent optional fields decode as `null`; `null` is never written
 * in requests (api.md: "в запросах необязательное поле просто не передаётся").
 *
 * Extensibility (architecture.md, «Расширяемость API»; AGENTS.md, Code): the contract grows by addition, so a
 * response may carry fields and codes this client does not know. Unknown fields are dropped here; unknown codes
 * are handled by [ru.finassist.pf.core.api.model.OpenEnumSerializer], not by `coerceInputValues`, which would
 * silently turn them into a default or `null`.
 */
@OptIn(ExperimentalSerializationApi::class)
val ApiJson: Json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = false
}
