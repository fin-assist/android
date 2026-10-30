package ru.finassist.pf.e2e

import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Test
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.AnalyticsFeature
import ru.finassist.pf.core.api.model.ImportNoticeCode
import ru.finassist.pf.core.api.model.ImportResult
import java.io.IOException
import kotlin.test.*

class StageResponseTransformsTest {
    private val completion = """{"operation_count":2,"new_count":1,"duplicate_count":1,"unread_count":0,"is_first_import":false,"totals":{"scope":"all","expense":123,"income":456},"accounts":[{"type_name":"Карта","is_other_bank":false}],"categorized_count":1,"own_transfer_count":0,"uncategorized_count":0,"coverage":{"full_months":1,"full_months_before":0,"newly_full_months":["2026-09"],"incomplete_months":[],"features":[]},"notices":[],"currency":"RUB","future_server_field":{"kept":true}}"""

    @Test
    fun `only closed expense alias reaches real transport as expense without widening scope`() {
        val request = Request.Builder().url("https://stage.plainfin.ru/v1/operations?from=2026-09-01&kind=expense_e2e&transfer_mode=without&selection=scope%2Fid&category_id=cat_food&before=cursor").build()
        val changed = assertNotNull(StageResponseTransforms.expenseRequest(request, true))
        assertEquals("expense_e2e", request.url.queryParameter("kind"))
        assertEquals("expense", changed.url.queryParameter("kind"))
        for (name in request.url.queryParameterNames - "kind") assertEquals(request.url.queryParameterValues(name), changed.url.queryParameterValues(name))
        assertEquals(request.url.host, changed.url.host)
        assertNull(StageResponseTransforms.expenseRequest(request, false))
        assertNull(StageResponseTransforms.expenseRequest(request.newBuilder().url("https://other.example/v1/operations?kind=expense_e2e").build(), true))
        assertNull(StageResponseTransforms.expenseRequest(request.newBuilder().url("https://stage.plainfin.ru/v1/operations?kind=other_future").build(), true))
        assertNull(StageResponseTransforms.expenseRequest(request.newBuilder().url("https://stage.plainfin.ru/v1/operations?kind=expense_e2e&kind=expense_e2e").build(), true))
    }

    @Test
    fun `only actual expense tile action changes and retains all server filter fields`() {
        val original = Json.parseToJsonElement("""{"id":"tiles","type":"tile_group","v":1,"data":{"tiles":[{"id":"expense","value":42,"action":{"type":"open_operations","label":"Операции","filter":{"from":"2026-09-01","kind":"expense","transfer_mode":"without","selection":"scope"}}},{"id":"income","action":{"type":"open_operations","filter":{"kind":"income"}}}]}}""").jsonObject
        val changed = StageResponseTransforms.expenseTile(original)
        val tiles = changed["data"]!!.jsonObject["tiles"]!!.jsonArray
        val filter = tiles[0].jsonObject["action"]!!.jsonObject["filter"]!!.jsonObject
        assertEquals("expense_e2e", filter["kind"]!!.jsonPrimitive.content)
        assertEquals("scope", filter["selection"]!!.jsonPrimitive.content)
        assertEquals("without", filter["transfer_mode"]!!.jsonPrimitive.content)
        assertEquals(42, tiles[0].jsonObject["value"]!!.jsonPrimitive.int)
        assertEquals(original["data"]!!.jsonObject["tiles"]!!.jsonArray[1], tiles[1])
        val unrelated = JsonObject(original + ("id" to JsonPrimitive("other")))
        assertEquals(unrelated, StageResponseTransforms.expenseTile(unrelated))
    }

    @Test
    fun `real import completion gains only closed unknown notice and feature`() {
        val original = ": heartbeat\r\nid: actual-id\r\nretry: 1500\r\nevent: complete\r\ndata: $completion\r\n\r\n"
        val changed = StageResponseTransforms.importFrame(original.encodeToByteArray()).decodeToString()
        assertTrue(changed.startsWith(": heartbeat\r\nid: actual-id\r\nretry: 1500\r\nevent: complete\r\n"))
        assertTrue(changed.endsWith("\r\n\r\n"))
        val json = changed.lineSequence().single { it.startsWith("data:") }.removePrefix("data: ")
        val result = ApiJson.decodeFromString(ImportResult.serializer(), json)
        assertEquals(2, result.operationCount)
        assertEquals(1, result.newCount)
        assertEquals(1, result.duplicateCount)
        assertEquals("RUB", result.currency)
        assertEquals(ImportNoticeCode.UNKNOWN, result.notices.single().code)
        assertEquals(StageResponseTransforms.NOTICE_TEXT, result.notices.single().text)
        assertEquals(AnalyticsFeature.UNKNOWN, result.coverage.features.single().feature)
        assertFalse(result.coverage.features.single().open)
        assertEquals(StageResponseTransforms.FEATURE_TEXT, result.coverage.features.single().text)
        val oldRoot = Json.parseToJsonElement(completion).jsonObject
        val newRoot = Json.parseToJsonElement(json).jsonObject
        for (key in oldRoot.keys - setOf("notices", "coverage")) assertEquals(oldRoot[key], newRoot[key], key)
        for (key in oldRoot["coverage"]!!.jsonObject.keys - "features") assertEquals(oldRoot["coverage"]!!.jsonObject[key], newRoot["coverage"]!!.jsonObject[key])
    }

    @Test
    fun `nonterminal refused malformed and invalid utf8 frames never become completions`() {
        val unchanged = listOf(
            "id: one\nevent: progress\ndata: {\"percent\":25}\n\n".encodeToByteArray(),
            "event: error\ndata: {\"code\":\"PROCESSING_FAILED\"}\n\n".encodeToByteArray(),
            "event: complete\ndata: {}\n\n".encodeToByteArray(),
            "event: complete\ndata: malformed\n\n".encodeToByteArray(),
            "event: complete\ndata: ${"[".repeat(70)}\n\n".encodeToByteArray(),
            "event: complete\n".encodeToByteArray() + byteArrayOf(-1) + "\ndata: $completion\n\n".encodeToByteArray(),
        )
        unchanged.forEach { assertContentEquals(it, StageResponseTransforms.importFrame(it)) }
    }

    @Test
    fun `stream preserves progress and terminal ids and counts transformation only when consumed`() {
        val progress = "id: p\nevent: progress\ndata: {\"stage\":\"parsing\",\"percent\":50}\n\n"
        val complete = "id: c\nevent: complete\ndata: $completion\n\n"
        var count = 0
        val body = StageImportUnknownCodesBody((progress + complete).toResponseBody("text/event-stream".toMediaType())) { count++ }
        assertEquals(0, count)
        assertEquals(progress, body.source().readUtf8(progress.length.toLong()))
        assertEquals(0, count)
        val rest = body.string()
        assertTrue(rest.startsWith("id: c\nevent: complete\n"))
        assertTrue(rest.contains(StageResponseTransforms.NOTICE_TEXT))
        assertEquals(1, count)
    }

    @Test
    fun `multi-line complete data and eof without delimiter remain consumable`() {
        val split = completion.indexOf(",") + 1
        val source = "event: complete\ndata: ${completion.substring(0, split)}\ndata: ${completion.substring(split)}"
        var count = 0
        val body = StageImportUnknownCodesBody(source.toResponseBody()) { count++ }
        val result = body.string()
        assertTrue(result.contains(StageResponseTransforms.NOTICE_TEXT))
        assertEquals(1, result.lineSequence().count { it.startsWith("data:") })
        assertEquals(1, count)
    }

    @Test
    fun `oversize line and cumulative frame fail bounded without fake terminal`() {
        for (source in listOf("x".repeat(65_537), "a: x\n".repeat(15_000))) {
            var count = 0
            val body = StageImportUnknownCodesBody(source.toResponseBody()) { count++ }
            assertFailsWith<IOException> { body.source().readAll(Buffer()) }
            assertEquals(0, count)
        }
    }
}
