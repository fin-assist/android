package ru.finassist.pf.core.api

import org.junit.Test
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.ValueKind
import ru.finassist.pf.core.api.model.TileGroupData
import ru.finassist.pf.core.api.model.RowsData
import ru.finassist.pf.core.api.model.ChartData
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.OperationsList
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.money.Money
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `analytics blocks - locked tile with server text, unknown and broken blocks do not fail the screen (FIN-4)`() {
        val json = """{"has_data":true,"params":{"period":"month","date":"2026-09","transfer_mode":"with"},"currency":"RUB",
          "blocks":[
            {"id":"tiles","type":"tile_group","v":1,"data":{"tiles":[
              {"id":"daily_expense","title":"Расходы в день","status":"locked",
               "lock":{"reason":"need_full_months","text":"Нужно 1 полный месяц, есть 0","required":1,"available":0}},
              {"id":"income","title":"Доходы","value":15690000,"value_kind":"amount","show_plus":true,"brand_new":1}]}},
            {"id":"spend_map","type":"geo_map","v":1,"alt_text":"Карта расходов"},
            {"type":42},
            {"id":"monthly_chart","type":"chart","v":3,"status":"weird","lock":{"reason":"need_more_coffee","text":"Позже"}}
          ]}"""
        val a = ApiJson.decodeFromString(Analytics.serializer(), json)
        assertEquals(listOf("tiles", "spend_map", "monthly_chart"), a.blocks.map { it.id }, "the broken envelope is dropped")
        val tiles = assertNotNull(a.blocks[0].decode(TileGroupData.serializer())).tiles
        assertEquals(BlockStatus.LOCKED, tiles[0].status)
        assertEquals("Нужно 1 полный месяц, есть 0", tiles[0].lock?.text)
        assertEquals(ValueKind.AMOUNT, tiles[1].valueKind)
        assertTrue(tiles[1].showPlus)
        assertEquals(BlockType.UNKNOWN, a.blocks[1].type)
        assertEquals("Карта расходов", a.blocks[1].altText)
        assertEquals(BlockStatus.LOCKED, a.blocks[2].status.effective, "an unknown status is locked")
        assertEquals(LockReason.UNKNOWN, a.blocks[2].lock?.reason)
        assertEquals(3, a.blocks[2].v)
        assertNull(a.blocks[2].decode(ChartData.serializer()), "no data — nothing to decode")

        val empty = ApiJson.decodeFromString(Analytics.serializer(), """{"has_data":false}""")
        assertFalse(empty.hasData)
        assertTrue(empty.blocks.isEmpty())
    }

    @Test
    fun `block values carry their kind, an unknown kind decodes and a broken row is dropped (FIN-4)`() {
        val rows = ApiJson.decodeFromString(
            RowsData.serializer(),
            """{"rows":[{"label":"Операций","value":9,"value_kind":"count"},{"value":1},{"label":"Скорость","value":3,"value_kind":"km_h"}]}""",
        )
        assertEquals(listOf("Операций", "Скорость"), rows.rows.map { it.label })
        assertEquals(ValueKind.UNKNOWN, rows.rows[1].valueKind)
        val text = Block.text("**7 200 ₽**")
        assertEquals("**7 200 ₽**", text.text)
        assertEquals(BlockType.TEXT, ApiJson.decodeFromString(Block.serializer(), ApiJson.encodeToString(Block.serializer(), text)).type)
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

    @Test
    fun `an unknown theme is the system one and does not break the profile (FIN-83)`() {
        val json = """{"user_id":"u1","phone":"+79161234567","theme":"sepia","timezone":"Europe/Moscow",
          "consents":[{"type":"assistant","granted":true,"version":"1","current_version":"1"},
                      {"type":"marketing","granted":true,"current_version":"3"}],
          "created_at":"2026-10-04T20:00:00+03:00","brand_new_field":1}"""
        val p = ApiJson.decodeFromString(ru.finassist.pf.core.api.model.Profile.serializer(), json)
        assertEquals(ru.finassist.pf.core.api.model.Theme.SYSTEM, p.theme)
        assertEquals(true, p.assistantConsent?.granted)
        assertEquals(ru.finassist.pf.core.api.model.ConsentType.UNKNOWN, p.consents[1].type)

        val none = ApiJson.decodeFromString(
            ru.finassist.pf.core.api.model.Profile.serializer(),
            """{"user_id":"u1","phone":"+7","theme":"dark","timezone":"Europe/Moscow","consents":[],"created_at":"2026-10-04T20:00:00+03:00"}""",
        )
        assertNull(none.assistantConsent, "no element - no consent")
    }

    @Test
    fun `a filter kind this client does not know is kept and sent back as is (FIN-83)`() {
        val f = ApiJson.decodeFromString(OperationsFilter.serializer(), """{"kind":"expense_v2","transfer_mode":"with"}""")
        assertEquals("expense_v2", f.kind?.code)
        assertFalse(f.kind!!.isKnown)
        assertTrue(ru.finassist.pf.core.api.model.OperationKindFilter.EXPENSE.isKnown)
        assertTrue(ApiJson.encodeToString(OperationsFilter.serializer(), f).contains("\"kind\":\"expense_v2\""))
    }

    @Test
    fun `import notices and features carry server texts, an unknown code decodes with its text (FIN-83)`() {
        val n = ApiJson.decodeFromString(
            ru.finassist.pf.core.api.model.ImportNotice.serializer(), """{"code":"partial_import","text":"Часть операций не загрузилась"}""",
        )
        assertEquals(ru.finassist.pf.core.api.model.ImportNoticeCode.UNKNOWN, n.code)
        assertEquals("Часть операций не загрузилась", n.text)
        val f = ApiJson.decodeFromString(
            ru.finassist.pf.core.api.model.FeatureAvailability.serializer(),
            """{"feature":"weekly_chart","feature_name":"Графики по неделям","open":false,"opened_now":false,"text":"Графики по неделям — нужно 4 недели"}""",
        )
        assertEquals(ru.finassist.pf.core.api.model.AnalyticsFeature.UNKNOWN, f.feature)
        assertEquals("Графики по неделям — нужно 4 недели", f.text)
        val s = ApiJson.decodeFromString(
            ru.finassist.pf.core.api.model.AnswerSource.serializer(),
            """{"calculated_at":"2026-09-25T19:11:00+03:00","transfer_mode":"with","changed_since":["rules"],"changed_since_text":"с тех пор менялись правила"}""",
        )
        assertEquals("с тех пор менялись правила", s.changedSinceText)
    }

    @Test
    fun `a notice without text does not fail the import result (FIN-83 review)`() {
        val n = ApiJson.decodeFromString(ru.finassist.pf.core.api.model.ImportNotice.serializer(), """{"code":"no_expenses"}""")
        assertEquals(ru.finassist.pf.core.api.model.ImportNoticeCode.NO_EXPENSES, n.code)
        assertNull(n.text)
    }

    @Test
    fun `a broken consent or notice is dropped, the rest of the response stays (FIN-83 review)`() {
        val p = ApiJson.decodeFromString(
            ru.finassist.pf.core.api.model.Profile.serializer(),
            """{"user_id":"u1","phone":"+7","theme":"dark","timezone":"Europe/Moscow",
              "consents":[{"type":"marketing","granted":true},{"type":"assistant","granted":true,"current_version":"1"}],
              "created_at":"2026-10-04T20:00:00+03:00"}""",
        )
        assertEquals(listOf(ru.finassist.pf.core.api.model.ConsentType.ASSISTANT), p.consents.map { it.type })

        val notices = ApiJson.decodeFromString(
            ru.finassist.pf.core.api.model.ImportNoticeList,
            """[{"text":"без кода"},{"code":"no_expenses","text":"нет расходов"}]""",
        )
        assertEquals(listOf(ru.finassist.pf.core.api.model.ImportNoticeCode.NO_EXPENSES), notices.map { it.code })
    }

    @Test
    fun `assistant answers carry the currency of their amounts, roubles when absent (FIN-82)`() {
        val snap = ApiJson.decodeFromString(
            ru.finassist.pf.core.api.model.AnswerSnapshotEvent.serializer(),
            """{"status":"generating","blocks":[],"chips":[],"currency":"USD"}""",
        )
        assertEquals("USD", snap.currency)
        val old = ApiJson.decodeFromString(
            ru.finassist.pf.core.api.model.Message.serializer(),
            """{"id":"m","role":"assistant","created_at":"2026-10-01T10:00:00+03:00"}""",
        )
        assertEquals("RUB", old.currency)
    }

    @Test
    fun `an insight card row has its own tap and a separate button (FIN-4 review)`() {
        val item = ApiJson.decodeFromString(
            ru.finassist.pf.core.api.model.InsightItem.serializer(),
            """{"title":"Яндекс Плюс","action":{"type":"open_operations"},"button":{"type":"dismiss_regular_payment","payment_id":"rp","label":"Не подписка"}}""",
        )
        assertEquals(ru.finassist.pf.core.api.model.ActionType.OPEN_OPERATIONS, item.action?.type)
        assertEquals("rp", item.button?.paymentId)
    }
}
