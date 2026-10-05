package ru.finassist.pf.core.network.client

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * Shared JSON configuration for the contract:
 *  - snake_case on the wire, camelCase in DTOs;
 *  - unknown fields ignored (forward compatibility);
 *  - absent == null for optional fields in both directions (`explicitNulls = false` also omits nulls in requests,
 *    as api.md requires: "null не отправляется").
 */
@OptIn(ExperimentalSerializationApi::class)
val PfJson: Json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = false
    isLenient = false
}
