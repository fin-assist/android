package ru.finassist.pf.core.api

import org.junit.Test
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.MetricStatus
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.OperationsList
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.money.Money
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SerializationTest {

    @Test
    fun `operations feed from api md decodes with snake_case and nullable fields`() {
        val json = """
        {"items":[
          {"id":"1","kind":"expense","occurred_at":"2026-09-25T19:11:00+03:00","amount":234000,"currency":"RUB",
           "title":"Пятёрочка","category_id":"cat_supermarkets","category_name":"Супермаркеты","category_icon":"cart",
           "is_category_manual":false,"note":null,"account":{"type_name":"Текущий","mask":"··4821"},"status":"posted"},
          {"id":"2","kind":"own_transfer","occurred_at":"2026-09-18T09:30:00+03:00","amount":2000000,"currency":"RUB",
           "title":"Перевод между счетами","category_id":"cat_own_transfer","category_name":"Между своими счетами",
           "category_icon":"transfer","is_category_manual":false,"note":"не в тратах",
           "from_account":{"type_name":"Накопительный","mask":"··0734"},"to_account":{"type_name":"Текущий","mask":"··4821"},
           "status":"posted","brand_new_field":{"x":1}}],
         "summary":[{"month":"2026-09","range":{"from":"2026-09-01T00:00:00+03:00","to":"2026-10-01T00:00:00+03:00"},
                     "expense":8432000,"income":15690000,"data_to":"2026-09-25T21:40:00+03:00"}],
         "state":{"stale":false,"last_operation_at":"2026-09-25T21:40:00+03:00"},
         "range":{"from":"2026-08-01T00:00:00+03:00","to":"2026-11-01T00:00:00+03:00"},
         "next_before":"2026-08-01T00:00:00+03:00"}
        """.trimIndent()

        val list = ApiJson.decodeFromString(OperationsList.serializer(), json)

        assertEquals(2, list.items.size)
        assertEquals(OperationKind.OWN_TRANSFER, list.items[1].kind)
        assertEquals("··0734", list.items[1].fromAccount?.mask)
        assertNull(list.items[0].note)
        assertEquals(Money(8_432_000), list.summary?.single()?.expense)
        assertEquals(8, list.nextBefore?.monthValue)
        assertNull(list.totalCount)
    }

    @Test
    fun `unknown open-enum values decode to UNKNOWN`() {
        val json = """{"id":"1","kind":"gift","occurred_at":"2026-09-25T19:11:00+03:00","amount":1,"currency":"RUB",
          "title":"x","category_id":"c","category_name":"n","category_icon":"i","is_category_manual":false,"status":"weird"}"""
        val item = ApiJson.decodeFromString(ru.finassist.pf.core.api.model.OperationItem.serializer(), json)
        assertEquals(OperationKind.UNKNOWN, item.kind)
        assertEquals(ru.finassist.pf.core.api.model.OperationStatus.UNKNOWN, item.status)
    }

    @Test
    fun `requests omit nulls and use snake_case`() {
        val encoded = ApiJson.encodeToString(AskRequest.serializer(), AskRequest(text = "Сколько?"))
        assertEquals("""{"text":"Сколько?"}""", encoded)
        val withMode = ApiJson.encodeToString(AskRequest.serializer(), AskRequest("x", TransferMode.WITHOUT))
        assertEquals("""{"text":"x","transfer_mode":"without"}""", withMode)
    }

    @Test
    fun `analytics locked metric and has_data false`() {
        val locked = """{"has_data":true,"params":{"period":"month","date":"2026-09","transfer_mode":"with"},
          "tiles":{"expense":{"status":"ready","value":8432000,"comparison":{"status":"locked","lock":{"reason":"need_full_months","required":1,"available":0}}},
                   "income":{"status":"ready","value":1},"balance":{"status":"ready","value":1,"share_of_income":null},
                   "daily_expense":{"status":"ready","value":1}},
          "monthly_chart":{"status":"locked","lock":{"reason":"need_months_with_data","required":2,"available":1}}}"""
        val analytics = ApiJson.decodeFromString(Analytics.serializer(), locked)
        assertTrue(analytics.hasData)
        assertEquals(LockReason.NEED_FULL_MONTHS, analytics.tiles?.expense?.comparison?.lock?.reason)
        assertEquals(MetricStatus.LOCKED, analytics.monthlyChart?.status)
        assertNull(analytics.tiles?.balance?.shareOfIncome)
        assertEquals("2026-09", analytics.params?.date?.value)

        val empty = ApiJson.decodeFromString(Analytics.serializer(), """{"has_data":false}""")
        assertFalse(empty.hasData)
        assertNull(empty.tiles)
    }

    @Test
    fun `assistant block of unknown type keeps alt_text`() {
        val block = ApiJson.decodeFromString(Block.serializer(), """{"type":"table","alt_text":"fallback","cells":[]}""")
        assertEquals(BlockType.UNKNOWN, block.type)
        assertEquals("fallback", block.altText)
    }

    @Test
    fun `operations filter round trip`() {
        val filter = OperationsFilter(categoryId = "cat_x", transferMode = TransferMode.WITH, selectionName = "Самокат")
        val json = ApiJson.encodeToString(OperationsFilter.serializer(), filter)
        assertEquals(filter, ApiJson.decodeFromString(OperationsFilter.serializer(), json))
        assertFalse(filter.isEmpty)
    }
}
