package ru.finassist.pf.mock.calc

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsPeriodsList
import ru.finassist.pf.core.api.model.CategoriesList
import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.CategoryKind
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.ChangeCategoryResponse
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.OperationsList
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.FAST
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.TestClock
import ru.finassist.pf.mock.api.MockAnalyticsApi
import ru.finassist.pf.mock.api.MockOperationsApi
import ru.finassist.pf.mock.api.MockStatementsApi
import ru.finassist.pf.mock.domain.Catalog
import ru.finassist.pf.mock.ofx.OfxParser
import java.io.File
import java.net.URLDecoder
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Runs the scenario of the calculation fixtures (`scenario.json`, README «Сценарий») on the fake backend and returns
 * its responses as API JSON, keyed by the `expect` file. Mirrors the reference runner `tools/refcalc/refcalc/scenario.py`.
 */
internal class CalcScenario(private val root: File) {
    private val scenario = Json.parseToJsonElement(File(root, "scenario.json").readText()).jsonObject
    private val clock = TestClock("2025-01-01T00:00:00+03:00")
    private val backend = MockBackend(FAST, clock, catalog = Catalog(categories()))
    private val statements = MockStatementsApi(backend)
    private val operations = MockOperationsApi(backend)
    private val analytics = MockAnalyticsApi(backend)
    private val responses = HashMap<String, JsonElement?>()

    init {
        val zone = scenario.getValue("time_zone").jsonPrimitive.content
        check(ZoneId.of(zone) == clock.zone) { "the scenario zone $zone differs from the test clock zone ${clock.zone}" }
        backend.ready.complete(Unit)
        backend.profile = backend.emptyProfile("+79160000000", "u1").copy(timezone = zone)
    }

    /** `expect` file → the response of its step. */
    fun run(): Map<String, JsonElement?> {
        val out = LinkedHashMap<String, JsonElement?>()
        for (element in scenario.getValue("steps").jsonArray) {
            val step = element.jsonObject
            step["now"]?.let { clock.at = OffsetDateTime.parse(it.jsonPrimitive.content) }
            val response = try {
                runBlocking { execute(step) }
            } catch (e: AppError.Api) {
                buildJsonObject {
                    put("status", e.httpStatus)
                    put("error", buildJsonObject { put("code", e.code); put("message", e.code) })
                }
            }
            responses[step.string("name")] = response
            step["expect"]?.let { out[it.jsonPrimitive.content] = response }
        }
        return out
    }

    private suspend fun execute(step: JsonObject): JsonElement? = when (val action = step.string("do")) {
        "upload" -> {
            val file = File(root, step.string("file"))
            val upload = statements.startUpload(file.name, OfxParser.parse(file.readText()), clock.at, instant = true)
            upload.result?.let { encode(ImportResult.serializer(), it) }
        }
        "get" -> {
            val request = step["filter_from"]?.let { "GET /v1/operations" to filterQuery(resolve(it.jsonObject)) }
                ?: parse(step.string("request"))
            get(request.first.removePrefix("GET "), request.second)
        }
        "change_category" -> {
            val op = step.getValue("operation").jsonObject
            val record = checkNotNull(backend.ledger.byKey(op.string("account"), op.string("fitid"))) { "no operation $op" }
            encode(
                ChangeCategoryResponse.serializer(),
                operations.changeCategory(record.id, ChangeCategoryRequest(step.string("category_id"))),
            )
        }
        "dismiss_regular_payment" -> {
            // As a client does it: the payment_id of the row with this title in the card.
            val (path, query) = parse(step.string("request"))
            val screen = checkNotNull(get(path.removePrefix("GET "), query)).jsonObject
            val card = screen.getValue("blocks").jsonArray.first { it.jsonObject["id"]?.jsonPrimitive?.content == "regular_payments" }
            val row = card.jsonObject.getValue("data").jsonObject.getValue("items").jsonArray
                .first { it.jsonObject.string("title") == step.string("title") }
            analytics.dismissRegularPayment(row.jsonObject.getValue("button").jsonObject.string("payment_id"))
            null
        }
        "delete_upload" -> {
            val name = File(step.string("file")).name
            statements.deleteStatement(backend.uploads.first { it.fileName == name }.id)
            null
        }
        else -> error("unknown step $action")
    }

    private suspend fun get(path: String, query: Map<String, String>): JsonElement = when (path) {
        "/v1/analytics" -> encode(
            Analytics.serializer(),
            analytics.getAnalytics(
                period = query["period"]?.let { code -> PeriodTypeCode.entries.first { it.code == code } },
                date = query["date"]?.let(::PeriodKey),
                transferMode = query["transfer_mode"]?.let { code -> TransferMode.entries.first { it.code == code } },
            ),
        )
        "/v1/analytics/periods" -> encode(
            AnalyticsPeriodsList.serializer(),
            analytics.listPeriods(query["period"]?.let { code -> PeriodTypeCode.entries.first { it.code == code } }),
        )
        "/v1/categories" -> encode(CategoriesList.serializer(), operations.listCategories())
        "/v1/operations" -> {
            val filter = query - setOf("before", "all_time")
            encode(
                OperationsList.serializer(),
                operations.listOperations(
                    OperationsQuery(
                        filter = ApiJson.decodeFromJsonElement(
                            OperationsFilter.serializer(),
                            JsonObject(filter.mapValues { JsonPrimitive(it.value) }),
                        ),
                        allTime = query["all_time"] == "true",
                        before = query["before"]?.let(OffsetDateTime::parse),
                    ),
                ),
            )
        }
        else -> error("unknown request $path")
    }

    /** `{"step": "<name>", "path": [...]}` → a value of an earlier response; `{"id": …}` picks the element with that id. */
    private fun resolve(ref: JsonObject): JsonObject {
        var v: JsonElement = checkNotNull(responses[ref.string("step")]) { "no response of ${ref.string("step")}" }
        for (p in ref.getValue("path").jsonArray) {
            v = when {
                p is JsonObject -> v.jsonArray.first { it.jsonObject["id"] == p["id"] }
                p.jsonPrimitive.isString -> v.jsonObject.getValue(p.jsonPrimitive.content)
                else -> v.jsonArray[p.jsonPrimitive.int]
            }
        }
        return v.jsonObject
    }

    /** `operations_filter` → query parameters of 4.1 (`selection_name` is for display only). */
    private fun filterQuery(filter: JsonObject): Map<String, String> = filter
        .filter { (k, v) -> k != "selection_name" && v !is JsonNull }
        .mapValues { (_, v) -> v.jsonPrimitive.content }

    private fun parse(request: String): Pair<String, Map<String, String>> {
        val target = request.substringAfter(' ')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }
        return "GET ${target.substringBefore('?')}" to query
    }

    /** Known categories of `categories.json` (its `system` is `is_system` of the API). */
    private fun categories(): List<Category> =
        Json.parseToJsonElement(File(root, "categories.json").readText()).jsonObject.getValue("categories").jsonArray.map {
            val c = it.jsonObject
            Category(
                id = c.string("id"),
                name = c.string("name"),
                icon = c.string("icon"),
                isSystem = c["system"]?.jsonPrimitive?.boolean ?: false,
                kinds = c.getValue("kinds").jsonArray.map { k -> CategoryKind.entries.first { e -> e.code == k.jsonPrimitive.content } },
                assignable = c["assignable"]?.jsonPrimitive?.boolean ?: true,
                note = c["note"]?.jsonPrimitive?.content,
            )
        }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonElement = OUT.encodeToJsonElement(serializer, value)

    private companion object {
        /** The API JSON with defaults written: a response field the model fills by default is on the wire too. */
        val OUT = Json(ApiJson) { encodeDefaults = true }
    }
}

/** Loads a JSON file of the fixtures. */
internal fun readJson(file: File): JsonElement = Json.parseToJsonElement(file.readText())
