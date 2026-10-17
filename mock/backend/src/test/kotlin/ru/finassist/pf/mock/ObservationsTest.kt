package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.api.MockAnalyticsApi
import ru.finassist.pf.mock.api.MockOperationsApi
import kotlin.test.assertEquals

/**
 * Observations of the Analytics screen (docs/calc.md, section 5) on branches the calculation fixtures do not reach:
 * small frequent spending grouped by merchant, then by category without the merchant's operations; a regular payment
 * with exactly one charge a month; notable spending against the 3 full months before.
 */
class ObservationsTest {
    // June to September are full months («now» is 6 October).
    private val backend = testBackend().apply {
        load(
            statement(
                "2026-06-01", "2026-09-30",
                *(1..9).map { trn("coffee$it", "2026-09-%02dT09:00".format(it), 100, "Кофейня", "Фастфуд") }.toTypedArray(),
                *(1..9).map { trn("kiosk$it", "2026-09-%02dT13:00".format(it), 100, "Ларёк $it", "Фастфуд") }.toTypedArray(),
                trn("cloud7", "2026-07-15T10:00", 299, "Облако", "Цифровые товары"),
                trn("cloud8", "2026-08-15T10:00", 299, "Облако", "Цифровые товары"),
                trn("cloud9", "2026-09-15T10:00", 299, "Облако", "Цифровые товары"),
                trn("gym7", "2026-07-03T10:00", 1_500, "Фитнес", "Тренировки"),
                trn("gym8a", "2026-08-03T10:00", 1_500, "Фитнес", "Тренировки"),
                trn("gym8b", "2026-08-17T10:00", 1_500, "Фитнес", "Тренировки"),
                trn("gym9", "2026-09-03T10:00", 1_500, "Фитнес", "Тренировки"),
                trn("taxi6", "2026-06-20T10:00", 2_000, "Такси", "Такси"),
                trn("taxi7", "2026-07-20T10:00", 3_000, "Такси", "Такси"),
                trn("taxi8", "2026-08-20T10:00", 4_000, "Такси", "Такси"),
                trn("taxi9", "2026-09-20T10:00", 7_200, "Такси", "Такси"),
            ),
        )
    }
    private val analytics = MockAnalyticsApi(backend)
    private val operations = MockOperationsApi(backend)

    private fun september(): Analytics = runBlocking { analytics.getAnalytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH) }
    private fun Analytics.block(id: String) = blocks.filter { it.id == id }
    private fun JsonObject.items() = getValue("items").jsonArray.map { it.jsonObject }
    private fun JsonObject.filter(): OperationsFilter =
        ru.finassist.pf.core.api.ApiJson.decodeFromJsonElement(OperationsFilter.serializer(), getValue("action").jsonObject.getValue("filter"))

    private fun fitIds(filter: OperationsFilter): Set<String> = runBlocking {
        operations.listOperations(OperationsQuery(filter)).items.map { item -> backend.ledger.byId(item.id)!!.fitId }.toSet()
    }

    @Test
    fun `small frequent - the merchant group first, the category group without the merchant's operations`() {
        val card = september().block("small_frequent").single()
        assertEquals(BlockStatus.READY, card.status)
        // Equal totals (900 ₽ each): by title, «Кофейня» before «Фастфуд».
        val first = checkNotNull(card.action?.filter)
        assertEquals("Кофейня", first.selectionName)
        assertEquals((1..9).map { "coffee$it" }.toSet(), fitIds(first))
        val row = card.data!!.items().single()
        assertEquals("Фастфуд", row.getValue("title").jsonPrimitive.content)
        assertEquals((1..9).map { "kiosk$it" }.toSet(), fitIds(row.filter()))
    }

    @Test
    fun `a regular payment has exactly one charge in every month of the window`() {
        val card = september().block("regular_payments").single()
        val rows = card.data!!.items()
        assertEquals(listOf("Облако"), rows.map { it.getValue("title").jsonPrimitive.content })
        // The row opens the charges of the payment in the selected month.
        assertEquals(setOf("cloud9"), fitIds(rows.single().filter()))
    }

    @Test
    fun `notable spending - usual is the average of the 3 full months before the selected one`() {
        val cards = september().block("notable_spending")
        assertEquals(listOf("Такси"), cards.map { it.data!!.getValue("title").jsonPrimitive.content })
        assertEquals(720_000.0, cards.single().data!!.getValue("value").jsonPrimitive.content.toDouble())
    }
}
