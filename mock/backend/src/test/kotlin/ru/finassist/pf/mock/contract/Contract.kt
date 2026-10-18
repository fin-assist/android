package ru.finassist.pf.mock.contract

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleResponse
import com.atlassian.oai.validator.report.MessageResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.schema.SchemaValidator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * The pinned contract of fin-assist/contracts (FIN-123): `openapi.yaml` and `api.md` copied by
 * `scripts/sync-contracts.sh` into `src/test/resources/contracts`, with the commit in `VERSION`. Validation is the
 * library's default: every message is an error, and objects without `additionalProperties` get `false` injected, so a
 * field the schema does not declare fails as well as a missing or mistyped one.
 *
 * Two preparations, both pinned by `ContractValidatorTest`:
 * - every `allOf` of the copy is merged into one object schema before parsing ([flattenAllOf]), keeping `nullable`
 *   of the wrapper: the injected `additionalProperties: false` would otherwise sit on each part alone
 *   (`OperationItem` = `OperationBase` + its own fields) and no object could match them all;
 * - a `null` the body carries for a declared, optional field the schema does not declare `nullable` is dropped
 *   before the check ([pruneNulls]): that is how a server writes an optional field it has no value for (left out;
 *   `null` only where the schema allows it), and the Kotlin DTO cannot tell the two apart. A `null` in a required
 *   field still fails, and so does a field the schema does not declare, `null` or not.
 */
object Contract {
    val ROOT = File("src/test/resources/contracts")
    val version: String by lazy { File(ROOT, "VERSION").readText().trim() }

    val api: OpenAPI by lazy {
        @Suppress("UNCHECKED_CAST")
        val spec = ObjectMapper(YAMLFactory()).readValue(File(ROOT, "openapi.yaml"), Map::class.java) as Map<String, Any?>
        // Written back as JSON: the merged parts are shared objects, which YAML would turn into anchors and aliases.
        val flat = ObjectMapper().writeValueAsString(flattenAllOf(spec))
        val result = OpenAPIV3Parser().readContents(flat, null, ParseOptions().apply { isResolve = true; isResolveFully = true })
        check(result.messages.isNullOrEmpty()) { "openapi.yaml does not parse: ${result.messages}" }
        result.openAPI
    }

    /** Every `allOf` replaced by one object schema: properties and `required` of all parts, the wrapper's own keys first. */
    @Suppress("UNCHECKED_CAST")
    private fun flattenAllOf(spec: Map<String, Any?>): Any? {
        val components = (spec["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>
        val done = HashMap<String, Map<String, Any?>>()
        val busy = HashSet<String>()
        lateinit var flat: (Any?) -> Any?
        fun ref(name: String): Map<String, Any?> = done[name] ?: run {
            check(busy.add(name)) { "allOf cycle through $name" }
            (flat(components[name]) as Map<String, Any?>).also { done[name] = it; busy.remove(name) }
        }
        fun merge(own: Map<String, Any?>, parts: List<Map<String, Any?>>): Map<String, Any?> {
            val properties = LinkedHashMap<String, Any?>()
            val required = LinkedHashSet<Any?>()
            val result = LinkedHashMap(own)
            (parts + own).forEach { part ->
                (part["properties"] as? Map<String, Any?>)?.let(properties::putAll)
                (part["required"] as? List<Any?>)?.let(required::addAll)
                part.forEach { (k, v) -> if (k != "properties" && k != "required") result.putIfAbsent(k, v) }
            }
            if (properties.isNotEmpty()) result["properties"] = properties
            if (required.isNotEmpty()) result["required"] = required.toList()
            return result
        }
        flat = { node ->
            when (node) {
                is Map<*, *> -> {
                    val m = LinkedHashMap<String, Any?>()
                    node.forEach { (k, v) -> m[k as String] = flat(v) }
                    val parts = m.remove("allOf") as List<Map<String, Any?>>?
                    if (parts == null) m else merge(m, parts.map { p -> (p["\$ref"] as? String)?.let { ref(it.substringAfterLast('/')) } ?: p })
                }
                is List<*> -> node.map(flat)
                else -> node
            }
        }
        return flat(spec)
    }

    private val validator: OpenApiInteractionValidator by lazy { OpenApiInteractionValidator.createFor(api).build() }
    private val schemas: SchemaValidator by lazy { SchemaValidator(api, MessageResolver()) }

    class Operation(
        val id: String,
        val method: Request.Method,
        val path: String,
        /** The one 2xx status of the operation. */
        val success: Int,
        /** Statuses the operation declares, 2xx included. */
        val statuses: Set<Int>,
        /** `x-sse-events` of a stream: event name → schema name. Empty for a plain JSON response. */
        val events: Map<String, String>,
        /** JSON schema of the body per status. */
        val bodies: Map<Int, Schema<*>?>,
    )

    val operations: Map<String, Operation> by lazy {
        api.paths.flatMap { (path, item) ->
            item.readOperationsMap().map { (method, op) ->
                val success = op.responses.keys.mapNotNull { it.toIntOrNull() }.single { it in 200..299 }
                @Suppress("UNCHECKED_CAST")
                val events = (op.responses[success.toString()]?.extensions?.get("x-sse-events") as? Map<String, Map<String, String>>)
                    .orEmpty().mapValues { (_, ref) -> ref.getValue("\$ref").substringAfterLast('/') }
                val bodies = op.responses.mapNotNull { (code, r) ->
                    val response = r.`$ref`?.let { api.components.responses[it.substringAfterLast('/')] } ?: r
                    code.toIntOrNull()?.let { it to response.content?.get("application/json")?.schema }
                }.toMap()
                Operation(op.operationId, method.toValidator(), path, success, op.responses.keys.mapNotNull { it.toIntOrNull() }.toSet(), events, bodies)
            }
        }.associateBy { it.id }
    }

    fun operation(id: String): Operation = operations[id] ?: error("no operation $id in openapi.yaml at $version")

    /** Section 8 of `api.md`: code → HTTP status; `null` for codes that are only events of a stream (HTTP «—»). */
    val registry: Map<String, Int?> by lazy {
        val text = File(ROOT, "api.md").readText()
        val section = text.substringAfter("## 8. Коды ошибок").substringBefore("\n## ")
        ROW.findAll(section).associate { m -> m.groupValues[1] to m.groupValues[2].toIntOrNull() }
            .also { check(it.isNotEmpty()) { "no error registry in api.md at $version" } }
    }

    /** Messages of the response against the operation's schema for [status]; empty means valid. */
    fun validateResponse(op: Operation, path: String, status: Int, body: String?, headers: Map<String, String>): List<String> {
        val json = body?.let { pruneNulls(Json.parseToJsonElement(it), op.bodies[status]) }
        val response = SimpleResponse.Builder.status(status).apply {
            if (json != null) {
                withContentType("application/json")
                withBody(json.toString())
            }
            headers.forEach { (name, value) -> withHeader(name, value) }
        }.build()
        val messages = validator.validateResponse(path, op.method, response).errors()
        val blocks = json?.let { blockMessages(it, "") }.orEmpty()
        return messages + blocks
    }

    /** Messages of one stream event's `data` against its schema in `x-sse-events`. */
    fun validateEvent(op: Operation, event: String, data: String): List<String> {
        val schema = op.events[event] ?: return listOf("event `$event` is not in x-sse-events of ${op.id}")
        val json = pruneNulls(Json.parseToJsonElement(data), component(schema))
        val messages = schemas.validate(json.toString(), component(schema), "event.$event").errors()
        return messages + blockMessages(json, "")
    }

    /**
     * `data` of every block in [json] against the schema of its primitive (`heading` → `HeadingData`, …), as the
     * fixtures check of contracts does: the `Block` schema leaves `data` open. A type the contract does not know is skipped.
     */
    private fun blockMessages(json: JsonElement, at: String): List<String> = when (json) {
        is JsonArray -> json.flatMapIndexed { i, e -> blockMessages(e, "$at/$i") }
        is JsonObject -> {
            val type = (json["type"] as? JsonPrimitive)?.contentOrNull
            val data = json["data"]
            // `data` is `nullable`: a block that is not ready has none.
            val own = if (type != null && json["v"] != null && data != null && data !is JsonNull) {
                val name = type.split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Data"
                if (api.components.schemas.containsKey(name)) {
                    schemas.validate(pruneNulls(data, component(name)).toString(), component(name), "block").errors()
                        .map { "block $at ($type): $it" }
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
            own + json.entries.flatMap { (k, v) -> blockMessages(v, "$at/$k") }
        }
        else -> emptyList()
    }

    /**
     * A schema of `components` by reference. Its allOf are merged like every other ([flattenAllOf]), so a nested cause
     * of an event is reported by its own key and pointer, not as «allOf failed» (`ContractValidatorTest`).
     */
    private fun component(name: String): Schema<*> {
        check(api.components.schemas.containsKey(name)) { "no schema $name in openapi.yaml at $version" }
        return Schema<Any>().`$ref`("#/components/schemas/$name")
    }

    private fun resolve(schema: Schema<*>?): Schema<*>? =
        schema?.`$ref`?.let { resolve(api.components.schemas[it.substringAfterLast('/')]) } ?: schema

    /** Drops `null` members of declared, optional, non-nullable fields (see the class KDoc); undeclared ones stay. */
    private fun pruneNulls(json: JsonElement, schema: Schema<*>?): JsonElement {
        val s = resolve(schema) ?: return json
        return when {
            json is JsonObject && s.properties != null -> JsonObject(
                json.entries.mapNotNull { (k, v) ->
                    val property = s.properties[k]
                    val keep = property == null || k in s.required.orEmpty() || property.nullable == true || resolve(property)?.nullable == true
                    if (v is JsonNull && !keep) null else k to pruneNulls(v, property)
                }.toMap(),
            )
            json is JsonArray && s.items != null -> JsonArray(json.map { pruneNulls(it, s.items) })
            else -> json
        }
    }

    private fun ValidationReport.errors(): List<String> =
        messages.filter { it.level == ValidationReport.Level.ERROR }.map { m ->
            val where = m.context.flatMap { it.pointers }.map { it.instance }.orElse(null)
            listOfNotNull(m.key, where, m.message).joinToString(" | ")
        }

    private fun PathItem.HttpMethod.toValidator() = Request.Method.valueOf(name)

    private val ROW = Regex("""^\|\s*`([A-Z_]+)`\s*\|\s*(\d{3}|—)\s*\|""", RegexOption.MULTILINE)
}
