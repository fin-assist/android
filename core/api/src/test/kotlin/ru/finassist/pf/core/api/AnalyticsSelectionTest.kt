package ru.finassist.pf.core.api

import org.junit.Test
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.AnswerSnapshotEvent
import ru.finassist.pf.core.api.model.MessagesList
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

    @Test fun `snapshots and history retain calendar siblings while rolling selections remain analytics-only`() {
        val chips = listOf("month" to "2026-10", "quarter" to "2026-Q4", "year" to "2026",
            "rolling_year" to "rolling_year", "rolling_quarter" to "rolling_quarter", "all_time" to "all_time", "week" to "2026-W40")
            .joinToString(",", "[", "]") { (period, date) ->
                """{"label":"$period","screen":"analytics","params":{"period":"$period","date":"$date","transfer_mode":"with"}}"""
            }
        val snapshot = ApiJson.decodeFromString(AnswerSnapshotEvent.serializer(),
            """{"status":"complete","blocks":[],"chips":$chips}""")
        val history = ApiJson.decodeFromString(MessagesList.serializer(),
            """{"messages":[{"id":"a","role":"assistant","status":"complete","created_at":"2026-10-01T12:00:00+03:00","blocks":[],"chips":$chips}],"has_older":false}""")
        for (decoded in listOf(snapshot.chips, history.messages.single().chips!!)) {
            assertEquals(listOf("month", "quarter", "year"), decoded.filter { it.isSupported }.map { it.label })
            for (rolling in decoded.filter { it.params!!.period.isRolling }) {
                assertTrue(rolling.params!!.isValidSelection, "rolling selections remain valid on the Analytics screen")
                assertFalse(rolling.isSupported, "the assistant has no negotiated rolling capability")
            }
        }
        assertTrue(ApiJson.decodeFromString(Chip.serializer(), """{"label":"Operations","screen":"operations"}""").isSupported)
    }

    @Test fun `analytics chips remain calendar-only and malformed future chips are hidden individually`() {
        for ((type, key, supported) in listOf(Triple("month", "2026-10", true), Triple("quarter", "2026-Q4", true),
            Triple("year", "2026", true), Triple("week", "2026-W40", false), Triple("all_time", "all_time", false),
            Triple("rolling_year", "rolling_year", false), Triple("rolling_quarter", "rolling_quarter", false),
            Triple("month", "not-a-key", false), Triple("year", "2026-10", false))) {
            val chip = ApiJson.decodeFromString(Chip.serializer(),
                """{"label":"Analytics","screen":"analytics","params":{"period":"$type","date":"$key","transfer_mode":"with"},"future":true}""")
            assertEquals(supported, chip.isSupported, "$type / $key")
        }
        assertFalse(ApiJson.decodeFromString(Chip.serializer(), """{"label":"Missing","screen":"analytics"}""").isSupported)
        assertFalse(ApiJson.decodeFromString(Chip.serializer(), """{"label":"Future","screen":"future"}""").isSupported)
    }
}
