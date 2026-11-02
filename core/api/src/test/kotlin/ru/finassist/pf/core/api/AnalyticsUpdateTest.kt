package ru.finassist.pf.core.api

import org.junit.Test
import ru.finassist.pf.core.api.model.*
import ru.finassist.pf.core.common.error.AppError
import kotlin.test.*

class AnalyticsUpdateTest {
    private fun update(blocks: String = """[{"id":"regular_payments","type":"insight_card","v":1,"status":"none","data":{"icon":"repeat","title":"Подписки"}}]""") = ApiJson.decodeFromString(AnalyticsBlockUpdate.serializer(), """{
        "params":{"period":"month","date":"2026-09","transfer_mode":"with"},"currency":"RUB",
        "time_zone":"Europe/Moscow","required_calc_version":42,"blocks":$blocks,"future":{}}
    """)

    @Test fun `last removed payment keeps a complete none block and unknown catalog types stay representable`() {
        AnalyticsIntegrity.requireValid(update())
        val unknown = update("""[{"id":"regular_payments","type":"new_type","v":2,"alt_text":"Описание","help_text":"Длинная справка"}]""")
        AnalyticsIntegrity.requireValid(unknown)
        assertEquals(BlockType.UNKNOWN, unknown.blocks.single().type)
        assertEquals("Длинная справка", unknown.blocks.single().helpText)
    }

    @Test fun `a broken update block cannot be dropped by the tolerant screen parser`() {
        assertFails { update("""[{"id":"regular_payments","type":{}},{"id":"other","type":"text","v":1}]""") }
        for (blocks in listOf("[]", """[{"id":"other","type":"text","v":1}]""",
            """[{"id":"regular_payments","type":"text","v":1},{"id":"regular_payments","type":"text","v":1}]""")) {
            assertFailsWith<AppError.Server> { AnalyticsIntegrity.requireValid(update(blocks)) }
        }
    }

    @Test fun `tolerant nested items never make a partial mutation replacement complete`() {
        for (items in listOf("{}", "null", """[{"title":"Good"},{}]""", """[{"title":"Good"},{"title":[]}]""")) {
            val value = update("""[{"id":"regular_payments","type":"insight_card","v":1,"data":{"icon":"repeat","title":"Подписки","value":10,"value_kind":"amount","description":"2 платежа","items":$items}}]""")
            assertFailsWith<AppError.Server> { AnalyticsIntegrity.requireValid(value) }
        }
    }

    @Test fun `invalid calculation does not certify readiness even for an empty ledger`() {
        for (wire in listOf("""{"has_data":false}""", """{"has_data":false,"calculation":{"calc_version":-1,"recalculating":false}}""")) {
            assertFailsWith<AppError.Server> { AnalyticsIntegrity.requireValid(ApiJson.decodeFromString(Analytics.serializer(), wire)) }
        }
        AnalyticsIntegrity.requireValid(ApiJson.decodeFromString(Analytics.serializer(), """{"has_data":false,"calculation":{"calc_version":42,"recalculating":false}}"""))
    }
}
