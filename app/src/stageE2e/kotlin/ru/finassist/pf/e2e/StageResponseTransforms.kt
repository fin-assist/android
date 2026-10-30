package ru.finassist.pf.e2e

import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.ImportResult
import java.io.IOException

/** Closed compatibility peer fixtures; none of these codes is advertised as supported by the live server. */
object StageResponseTransforms {
    const val UNKNOWN_EXPENSE = "expense_e2e"
    const val NOTICE_TEXT = "Часть операций пришла из нового источника — проверьте категории"
    const val FEATURE_TEXT = "Новая метрика — нужно 6 полных месяцев"

    /** Only the UI test's expense alias is translated; all other filters and the origin remain unchanged. */
    fun expenseRequest(request: Request, enabled: Boolean): Request? {
        if (!enabled || request.url.scheme != "https" || request.url.host != "stage.plainfin.ru" || request.url.port != 443 ||
            request.method != "GET" || request.url.encodedPath != "/v1/operations" ||
            request.url.queryParameterValues("kind") != listOf(UNKNOWN_EXPENSE)) return null
        return request.newBuilder().url(request.url.newBuilder().setQueryParameter("kind", "expense").build()).build()
    }

    /** The real expense tile keeps its complete original filter, apart from the closed client alias. */
    fun expenseTile(block: JsonObject): JsonObject {
        if (block.string("id") != "tiles" ||
            block.string("type") != "tile_group") return block
        val data = block["data"] as? JsonObject ?: return block
        val tiles = data["tiles"] as? JsonArray ?: return block
        val changed = tiles.map { element ->
            val tile = element as? JsonObject ?: return@map element
            if (tile.string("id") != "expense") return@map element
            val action = tile["action"] as? JsonObject ?: return@map element
            if (action.string("type") != "open_operations") return@map element
            val filter = action["filter"] as? JsonObject ?: return@map element
            if (filter.string("kind") != "expense") return@map element
            JsonObject(tile + ("action" to JsonObject(action +
                ("filter" to JsonObject(filter + ("kind" to JsonPrimitive(UNKNOWN_EXPENSE)))))))
        }
        return JsonObject(block + ("data" to JsonObject(data + ("tiles" to JsonArray(changed)))))
    }

    /** Match the original unknown-block scenario: keep the fixture just after tiles, not behind all live content. */
    fun unknownAnalyticsBlocks(blocks: List<JsonElement>): JsonArray {
        val changed = blocks.toMutableList()
        val tiles = blocks.indexOfFirst { (it as? JsonObject)?.string("id") == "tiles" }
        val at = if (tiles >= 0) tiles + 1 else minOf(1, blocks.size)
        changed.addAll(at, listOf(
            Json.parseToJsonElement("""{"id":"e2e_unknown","type":"e2e_new_primitive","v":1,"alt_text":"Карта расходов по районам — обновите приложение, чтобы её увидеть"}"""),
            Json.parseToJsonElement("""{"id":"e2e_locked","type":"insight_card","v":1,"status":"locked","lock":{"reason":"e2e_reason","text":"Откроется, когда подключите второй банк"},"data":{"icon":"lock","title":"Сравнение банков"}}"""),
        ))
        return JsonArray(changed)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Nonterminal, error, malformed and unrecognised frames remain byte-for-byte unchanged. */
    internal fun importFrame(raw: ByteArray): ByteArray {
        val text = try { raw.decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) { return raw }
        val lines = Regex("[^\\n]*(?:\\n|$)").findAll(text).map { it.value }.filter { it.isNotEmpty() }.toList()
        fun value(line: String, field: String): String? = line.removeSuffix("\n").removeSuffix("\r")
            .takeIf { it.startsWith("$field:") }?.substring(field.length + 1)?.removePrefix(" ")
        if (lines.mapNotNull { value(it, "event") }.lastOrNull() != "complete") return raw
        val data = lines.mapNotNull { value(it, "data") }.joinToString("\n")
        if (!boundedDepth(data)) return raw
        val root = try {
            // An injected notice must never manufacture a valid completion from a failed/malformed result.
            ApiJson.decodeFromString(ImportResult.serializer(), data)
            Json.parseToJsonElement(data).jsonObject
        } catch (_: Exception) { return raw }
        val notices = root["notices"] as? JsonArray ?: return raw
        val coverage = root["coverage"] as? JsonObject ?: return raw
        val features = coverage["features"] as? JsonArray ?: return raw
        val notice = buildJsonObject { put("code", "e2e_notice"); put("text", NOTICE_TEXT) }
        val feature = buildJsonObject {
            put("feature", "e2e_feature"); put("open", false); put("opened_now", false)
            put("required_full_months", 6); put("feature_name", "Новая метрика"); put("text", FEATURE_TEXT)
        }
        val changed = JsonObject(root + mapOf(
            "notices" to JsonArray(notices + notice),
            "coverage" to JsonObject(coverage + ("features" to JsonArray(features + feature))),
        )).toString()
        var emitted = false
        return buildString {
            lines.forEach { line ->
                if (value(line, "data") == null) append(line)
                else if (!emitted) {
                    append("data: ").append(changed)
                    append(if (line.endsWith("\r\n")) "\r\n" else if (line.endsWith("\n")) "\n" else "")
                    emitted = true
                }
            }
        }.encodeToByteArray()
    }

    private fun boundedDepth(json: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in json) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 64) return false
                '}', ']' -> depth--
            }
        }
        return true
    }
}

/** Buffers one bounded SSE frame at a time; the independent server still owns processing and its terminal result. */
class StageImportUnknownCodesBody(private val delegate: ResponseBody, onTransform: () -> Unit) : ResponseBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = -1L
    private val transformed = object : Source {
        private val source = delegate.source()
        private val output = Buffer()
        override fun timeout(): Timeout = source.timeout()
        override fun close() = source.close()

        override fun read(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0)
            if (byteCount == 0L) return 0
            if (output.size == 0L) {
                val frame = Buffer()
                while (true) {
                    val remaining = MAX_FRAME_BYTES - frame.size
                    val newline = source.indexOf('\n'.code.toByte(), 0, remaining + 1)
                    val size = if (newline >= 0) newline + 1 else source.buffer.size
                    if (size > remaining) throw IOException("Stage import SSE frame exceeds harness limit")
                    if (size == 0L) break
                    val line = source.readByteArray(size)
                    frame.write(line)
                    if (line.contentEquals(byteArrayOf(10)) || line.contentEquals(byteArrayOf(13, 10)) || newline < 0) break
                }
                if (frame.size == 0L) return -1
                val raw = frame.readByteArray()
                val changed = StageResponseTransforms.importFrame(raw)
                if (!changed.contentEquals(raw)) onTransform()
                output.write(changed)
            }
            return output.read(sink, minOf(byteCount, output.size))
        }
    }.buffer()
    override fun source(): BufferedSource = transformed

    companion object { const val MAX_FRAME_BYTES = 65_536L }
}
