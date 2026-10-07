package ru.finassist.pf.core.navigation

import android.net.Uri
import android.os.Bundle
import androidx.navigation.NavType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.OperationsFilter
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Typed-route support for complex arguments: Navigation Compose serializes route classes itself, but a nested
 * object property needs a custom [NavType]. Objects are carried as URL-encoded JSON.
 *
 * Only non-null route properties are supported: for a `null` value Navigation calls [serializeAsValue] with
 * `null`, which breaks the non-null contract (FIN-31). Give such a property a non-null default instead.
 */
class JsonNavType<T : Any>(private val serializer: KSerializer<T>, private val json: Json = ApiJson) :
    NavType<T>(isNullableAllowed = false) {

    override fun get(bundle: Bundle, key: String): T? = bundle.getString(key)?.let { parseValue(it) }

    override fun parseValue(value: String): T = json.decodeFromString(serializer, Uri.decode(value))

    override fun put(bundle: Bundle, key: String, value: T) {
        bundle.putString(key, serializeAsValue(value))
    }

    override fun serializeAsValue(value: T): String = Uri.encode(json.encodeToString(serializer, value))
}

/** Type map to pass to `composable<Route>(typeMap = PfNavTypes.MAP)` for routes that carry these arguments. */
object PfNavTypes {
    val operationsFilter = JsonNavType(OperationsFilter.serializer())
    val analyticsParams = JsonNavType(AnalyticsParams.serializer())

    val MAP: Map<KType, NavType<*>> = mapOf(
        // Nullable types are deliberately not registered: a nullable route property then fails when the
        // graph is built instead of crashing on navigation.
        typeOf<OperationsFilter>() to operationsFilter,
        typeOf<AnalyticsParams>() to analyticsParams,
    )
}
