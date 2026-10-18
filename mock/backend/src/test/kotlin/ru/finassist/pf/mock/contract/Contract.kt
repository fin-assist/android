package ru.finassist.pf.mock.contract

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleResponse
import com.atlassian.oai.validator.report.MessageResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.schema.SchemaValidator
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * The pinned contract of fin-assist/contracts (FIN-123): `openapi.yaml` and `api.md` copied by
 * `scripts/sync-contracts.sh` into `src/test/resources/contracts`, with the commit in `VERSION`. Validation is the
 * library's default: every message is an error, and objects without `additionalProperties` get `false` injected, so a
 * field the schema does not declare fails as well as a missing or mistyped one.
 */
object Contract {
    val ROOT = File("src/test/resources/contracts")
    val version: String by lazy { File(ROOT, "VERSION").readText().trim() }

    val api: OpenAPI by lazy {
        // allOf merged into one schema: the injected `additionalProperties: false` would otherwise apply to each part of
        // an allOf alone (`OperationItem` = `OperationBase` + its own fields), and no object could match them all.
        val options = ParseOptions().apply {
            isResolve = true
            isResolveFully = true
            isResolveCombinators = true
        }
        val result = OpenAPIV3Parser().readLocation(File(ROOT, "openapi.yaml").toURI().toString(), null, options)
        check(result.messages.isNullOrEmpty()) { "openapi.yaml does not parse: ${result.messages}" }
        result.openAPI
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
    )

    val operations: Map<String, Operation> by lazy {
        api.paths.flatMap { (path, item) ->
            item.readOperationsMap().map { (method, op) ->
                val success = op.responses.keys.mapNotNull { it.toIntOrNull() }.single { it in 200..299 }
                @Suppress("UNCHECKED_CAST")
                val events = (op.responses[success.toString()]?.extensions?.get("x-sse-events") as? Map<String, Map<String, String>>)
                    .orEmpty().mapValues { (_, ref) -> ref.getValue("\$ref").substringAfterLast('/') }
                Operation(op.operationId, method.toValidator(), path, success, op.responses.keys.mapNotNull { it.toIntOrNull() }.toSet(), events)
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
        val response = SimpleResponse.Builder.status(status).apply {
            if (body != null) {
                withContentType("application/json")
                withBody(body)
            }
            headers.forEach { (name, value) -> withHeader(name, value) }
        }.build()
        val messages = validator.validateResponse(path, op.method, response).errors()
        val blocks = body?.let { blockMessages(Json.parseToJsonElement(it), "") }.orEmpty()
        return messages + blocks
    }

    /** Messages of one stream event's `data` against its schema in `x-sse-events`. */
    fun validateEvent(op: Operation, event: String, data: String): List<String> {
        val schema = op.events[event] ?: return listOf("event `$event` is not in x-sse-events of ${op.id}")
        val messages = schemas.validate(data, component(schema), "event.$event").errors()
        return messages + blockMessages(Json.parseToJsonElement(data), "")
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
            val own = if (type != null && json["v"] != null && data != null) {
                val name = type.split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Data"
                if (api.components.schemas.containsKey(name)) {
                    schemas.validate(data.toString(), component(name), "block").errors()
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

    /** A schema of `components`, as parsed: references resolved and allOf merged, like the operations' schemas. */
    private fun component(name: String): Schema<*> = api.components.schemas[name] ?: error("no schema $name in openapi.yaml at $version")

    private fun ValidationReport.errors(): List<String> =
        messages.filter { it.level == ValidationReport.Level.ERROR }.map { m ->
            val where = m.context.flatMap { it.pointers }.map { it.instance }.orElse(null)
            listOfNotNull(m.key, where, m.message).joinToString(" | ")
        }

    private fun PathItem.HttpMethod.toValidator() = Request.Method.valueOf(name)

    private val ROW = Regex("""^\|\s*`([A-Z_]+)`\s*\|\s*(\d{3}|—)\s*\|""", RegexOption.MULTILINE)
}
