package ru.finassist.pf.core.api

import org.junit.Test
import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.ChartData
import ru.finassist.pf.core.api.model.PeriodTypeCode
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChartActionsSerializationTest {
    private fun point(action: String) = """{
        "range":{"from":"2026-09-01T00:00:00+03:00","to":"2026-10-01T00:00:00+03:00"},
        "value":0,"coverage":"complete","future":true,"action":$action
    }"""

    private fun chart(vararg points: String) = ApiJson.decodeFromString(ChartData.serializer(),
        """{"kind":"bar","value_kind":"amount","points":[${points.joinToString(",")}]}""")

    @Test fun `point action preserves its exact year month and ignores added fields`() {
        val point = chart(point("""{"type":"select_analytics_period","period":"month","date":"2025-09","future":{}}""")).points.single()
        assertEquals(0.0, point.value)
        assertEquals(ActionType.SELECT_ANALYTICS_PERIOD, point.action?.type)
        assertEquals(PeriodTypeCode.MONTH, point.action?.period)
        assertEquals("2025-09", point.action?.date?.value)
    }

    @Test fun `unknown codes and missing action fields remain representable for inert fallback`() {
        val points = chart(
            point("""{"type":"future_action","period":"month","date":"2026-09"}"""),
            point("""{"type":"select_analytics_period","period":"future_period","date":"2026-09"}"""),
            point("""{"type":"select_analytics_period"}"""),
            point("""{"type":"select_analytics_period","period":"month","date":"broken"}"""),
            point("null"),
        ).points
        assertEquals(5, points.size)
        assertEquals(ActionType.UNKNOWN, points[0].action?.type)
        assertEquals(PeriodTypeCode.UNKNOWN, points[1].action?.period)
        assertNull(points[2].action?.period)
        assertNull(points[2].action?.date)
        assertEquals("broken", points[3].action?.date?.value)
        assertNull(points[4].action)
    }

    @Test fun `a broken action drops only its point and keeps valid neighbours`() {
        val points = chart(
            point("""{"type":"select_analytics_period","period":"month","date":{}}"""),
            point("""{"type":"select_analytics_period","period":[],"date":"2026-09"}"""),
            point("""{"type":"select_analytics_period","period":"month","date":"2026-09"}"""),
        ).points
        assertEquals(1, points.size)
        assertEquals("2026-09", points.single().action?.date?.value)
    }
}
