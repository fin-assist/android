package ru.finassist.pf.core.api

import org.junit.Test
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.Chip
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.common.time.PeriodKey
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyticsSelectionTest {
    @Test fun `all-time selection decodes additively without fabricating a calendar year`() {
        val params = ApiJson.decodeFromString(AnalyticsParams.serializer(),
            """{"period":"all_time","date":"all_time","transfer_mode":"without","future":42}""")
        assertEquals(PeriodTypeCode.ALL_TIME, params.period)
        assertEquals(PeriodKey.ALL_TIME, params.date)
        assertTrue(params.isValidSelection)
    }

    @Test fun `unknown period and mismatched keys cannot become a known selection`() {
        for ((type, key) in listOf("week" to "2026-W40", "all_time" to "2026", "month" to "all_time", "year" to "2026-10")) {
            val params = ApiJson.decodeFromString(AnalyticsParams.serializer(),
                """{"period":"$type","date":"$key","transfer_mode":"with"}""")
            assertFalse(params.isValidSelection, "$type / $key")
        }
    }

    @Test fun `analytics chips remain calendar-only and malformed future chips are hidden individually`() {
        for ((type, key, supported) in listOf(Triple("month", "2026-10", true), Triple("quarter", "2026-Q4", true),
            Triple("year", "2026", true), Triple("week", "2026-W40", false), Triple("all_time", "all_time", false),
            Triple("month", "not-a-key", false), Triple("year", "2026-10", false))) {
            val chip = ApiJson.decodeFromString(Chip.serializer(),
                """{"label":"Analytics","screen":"analytics","params":{"period":"$type","date":"$key","transfer_mode":"with"},"future":true}""")
            assertEquals(supported, chip.isSupported, "$type / $key")
        }
        assertFalse(ApiJson.decodeFromString(Chip.serializer(), """{"label":"Missing","screen":"analytics"}""").isSupported)
        assertFalse(ApiJson.decodeFromString(Chip.serializer(), """{"label":"Future","screen":"future"}""").isSupported)
    }
}
