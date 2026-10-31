package ru.finassist.pf.core.blocks

import org.junit.Test
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.common.time.PeriodKey
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AnalyticsPeriodTargetTest {
    @Test fun `supported types require exact matching keys`() {
        listOf(
            PeriodTypeCode.MONTH to "2026-09",
            PeriodTypeCode.QUARTER to "2026-Q3",
            PeriodTypeCode.YEAR to "2026",
            PeriodTypeCode.ALL_TIME to "all_time",
        ).forEach { (period, date) ->
            assertEquals(AnalyticsPeriodTarget(period, PeriodKey(date)), AnalyticsPeriodTargets.of(period, PeriodKey(date)))
        }
        assertNull(AnalyticsPeriodTargets.of(PeriodTypeCode.MONTH, PeriodKey("2026")))
        assertNull(AnalyticsPeriodTargets.of(PeriodTypeCode.MONTH, PeriodKey("2026-Q3")))
        assertNull(AnalyticsPeriodTargets.of(PeriodTypeCode.QUARTER, PeriodKey("2026-09")))
        assertNull(AnalyticsPeriodTargets.of(PeriodTypeCode.YEAR, PeriodKey("2026-Q3")))
        assertNull(AnalyticsPeriodTargets.of(PeriodTypeCode.ALL_TIME, PeriodKey("2026")))
        assertNull(AnalyticsPeriodTargets.of(PeriodTypeCode.MONTH, PeriodKey.ALL_TIME))
    }

    @Test fun `unknown missing and malformed fields leave the action inert`() {
        assertNull(AnalyticsPeriodTargets.of(PeriodTypeCode.UNKNOWN, PeriodKey("2026-09")))
        assertNull(AnalyticsPeriodTargets.of(null, PeriodKey("2026-09")))
        assertNull(AnalyticsPeriodTargets.of(PeriodTypeCode.MONTH, null))
        listOf("", "09", "2026-00", "2026-13", "2026-9", "2026-09-01", "2026-Q0", "2026-Q5", " 2026-09", "2026-09 ").forEach { value ->
            assertNull(AnalyticsPeriodTargets.of(PeriodTypeCode.MONTH, PeriodKey(value)), value)
        }
    }

    @Test fun `month keys retain the year`() {
        val earlier = AnalyticsPeriodTargets.of(PeriodTypeCode.MONTH, PeriodKey("2025-09"))
        val later = AnalyticsPeriodTargets.of(PeriodTypeCode.MONTH, PeriodKey("2026-09"))
        assertNotEquals(earlier, later)
        assertEquals("2025-09", earlier?.date?.value)
        assertEquals("2026-09", later?.date?.value)
    }
}
