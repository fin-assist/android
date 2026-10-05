package ru.finassist.pf.core.navigation

import android.net.Uri
import android.os.Bundle
import androidx.navigation.NavType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.reflect.KType
import kotlin.reflect.typeOf

private val navJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * Type-safe routes can carry nested `@Serializable` objects only with a custom [NavType]; this one serialises
 * the object to URL-encoded JSON. Routes expose a `typeMap` that both `composable<T>(typeMap)` and
 * `SavedStateHandle.toRoute<T>(typeMap)` must receive — otherwise navigation crashes at runtime.
 * Nullable arguments arrive as the literal string "null" (Navigation's convention).
 */
class JsonNavType<T : Any>(private val serializer: KSerializer<T>, nullable: Boolean) : NavType<T?>(isNullableAllowed = nullable) {
    override fun get(bundle: Bundle, key: String): T? = bundle.getString(key)?.let(::parseValue)
    override fun parseValue(value: String): T? = if (value == "null") null else navJson.decodeFromString(serializer, Uri.decode(value))
    override fun put(bundle: Bundle, key: String, value: T?) { bundle.putString(key, value?.let { navJson.encodeToString(serializer, it) } ?: "null") }
    override fun serializeAsValue(value: T?): String = value?.let { Uri.encode(navJson.encodeToString(serializer, it)) } ?: "null"
}

/** `typeMap` entries: `jsonTypeMap(typeOf<OperationsFilter>() to OperationsFilter.serializer())`. */
inline fun <reified T : Any> jsonNavTypeMap(serializer: KSerializer<T>, nullable: Boolean = false): Map<KType, NavType<*>> =
    mapOf((if (nullable) typeOf<T?>() else typeOf<T>()) to JsonNavType(serializer, nullable))
