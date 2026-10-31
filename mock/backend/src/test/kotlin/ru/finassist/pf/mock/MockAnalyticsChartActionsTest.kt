package ru.finassist.pf.mock

import org.junit.Test
import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.ChartData
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.domain.AnalyticsComposer
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MockAnalyticsChartActionsTest {
    @Test fun `all-time points carry exact month actions for covered zero and positive months across years`() {
        val backend = testBackend(TestClock("2026-10-31T12:00:00+03:00"))
        backend.load(statement("2025-09-01", "2025-09-30",
            trn("salary", "2025-09-03T12:00", 10, "Salary", "Зарплата", debit = false)))
        backend.load(statement("2026-09-01", "2026-09-30",
            trn("shop", "2026-09-03T12:00", 20, "Shop", "Супермаркеты")))
        for (mode in listOf(TransferMode.WITH, TransferMode.WITHOUT)) {
            val snapshot = backend.analytics.analytics(PeriodTypeCode.ALL_TIME, PeriodKey.ALL_TIME, mode)
            val chart = assertNotNull(AnalyticsComposer.compose(snapshot).blocks.single { it.id == "monthly_chart" }.decode(ChartData.serializer()))
            assertEquals(13, chart.points.size)
            assertEquals(0.0, chart.points.first().value)
            assertEquals("2025-09", chart.points.first().action?.date?.value)
            assertEquals("2026-09", chart.points.last().action?.date?.value)
            chart.points.forEach { point ->
                if (point.coverage == Coverage.NO_DATA) {
                    assertNull(point.action)
                } else {
                    assertEquals(ActionType.SELECT_ANALYTICS_PERIOD, point.action?.type)
                    assertEquals(PeriodTypeCode.MONTH, point.action?.period)
                    assertEquals(point.range.from.toString().take(7), point.action?.date?.value)
                    assertNull(point.action?.filter, "the screen retains its selected transfers mode")
                }
            }
        }
    }

    @Test fun `one partial covered zero month is selectable without a history threshold`() {
        val backend = testBackend(TestClock("2026-10-10T12:00:00+03:00"))
        backend.load(statement("2026-10-01", "2026-10-10",
            trn("salary", "2026-10-03T12:00", 10, "Salary", "Зарплата", debit = false)))
        val snapshot = backend.analytics.analytics(PeriodTypeCode.ALL_TIME, null, TransferMode.WITH)
        val point = assertNotNull(AnalyticsComposer.compose(snapshot).blocks.single { it.id == "monthly_chart" }
            .decode(ChartData.serializer())).points.single()
        assertEquals(Coverage.PARTIAL, point.coverage)
        assertEquals(0.0, point.value)
        assertEquals("2026-10", point.action?.date?.value)
    }

    @Test fun `calendar monthly charts get actions while balance points remain inert`() {
        val backend = testBackend().apply { load(fixtureDoc()) }
        for ((type, date) in listOf(PeriodTypeCode.MONTH to "2026-09", PeriodTypeCode.QUARTER to "2026-Q3", PeriodTypeCode.YEAR to "2026")) {
            val screen = AnalyticsComposer.compose(backend.analytics.analytics(type, PeriodKey(date), TransferMode.WITH))
            val chart = assertNotNull(screen.blocks.single { it.id == "monthly_chart" }.decode(ChartData.serializer()))
            assertTrue(chart.points.any { it.action?.type == ActionType.SELECT_ANALYTICS_PERIOD })
            screen.blocks.filter { it.id == "balance" }.forEach { balance ->
                assertTrue(assertNotNull(balance.decode(ChartData.serializer())).points.all { it.action == null })
            }
        }
    }
}
