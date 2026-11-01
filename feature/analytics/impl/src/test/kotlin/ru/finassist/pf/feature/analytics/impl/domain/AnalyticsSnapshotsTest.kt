package ru.finassist.pf.feature.analytics.impl.domain

import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.AnalyticsState
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.PeriodKey
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AnalyticsSnapshotsTest {
    private val cache = AnalyticsSnapshots()
    private val now = OffsetDateTime.parse("2026-09-30T12:00:00+03:00")
    private val params = AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH)
    private fun ready(key: AnalyticsParams = params) = Analytics(
        hasData = true, params = key, currency = "RUB",
        blocks = listOf(Block(id = "balance", type = BlockType.UNKNOWN, altText = "100")),
        state = AnalyticsState(stale = false, dataFrom = now, dataTo = now, fullMonths = 1, unreadLinesCount = 0, calculatedAt = now, recalculating = false),
    )
    private fun calculating(key: AnalyticsParams = params) = ready(key).let {
        it.copy(currency = "EUR", blocks = emptyList(), state = it.state!!.copy(recalculating = true))
    }

    @Test fun `marked responses retain the exact whole ready object and cannot overwrite it`() {
        val original = ready()
        assertSame(original, cache.display(original, params))
        repeat(3) { assertSame(original, cache.display(calculating(), params)) }
        val replacement = ready().copy(currency = "EUR", blocks = emptyList())
        assertSame(replacement, cache.display(replacement, params))
        assertSame(replacement, cache.display(calculating(), params))
    }

    @Test fun `period date and transfer mode each isolate snapshots`() {
        val original = ready()
        cache.display(original, params)
        val keys = listOf(
            params.copy(date = PeriodKey("2026-08")),
            params.copy(period = PeriodTypeCode.QUARTER, date = PeriodKey("2026-Q3")),
            params.copy(transferMode = TransferMode.WITHOUT),
            params.copy(period = PeriodTypeCode.ALL_TIME, date = PeriodKey.ALL_TIME),
            params.copy(period = PeriodTypeCode.ROLLING_YEAR, date = PeriodKey.ROLLING_YEAR),
        )
        for (key in keys) {
            assertNull(cache.display(calculating(key), key))
            val other = ready(key)
            cache.display(other, key)
            assertSame(other, cache.display(calculating(key), key))
            assertSame(original, cache.display(calculating(), params))
        }
    }

    @Test fun `the normalized response owns the snapshot rather than the finer request`() {
        val normalized = params.copy(period = PeriodTypeCode.YEAR, date = PeriodKey("2026"))
        val request = normalized.copy(date = PeriodKey("2026-09"))
        val snapshot = ready(normalized)
        cache.display(snapshot, request)
        assertSame(snapshot, cache.display(calculating(normalized), request))
        assertNull(cache.display(calculating().copy(params = null), request))
    }

    @Test fun `an absent marked projection can use only its known request context`() {
        val original = ready()
        cache.display(original, params)
        val absent = calculating().copy(hasData = false, params = null)
        assertSame(original, cache.display(absent, params))
        assertNull(cache.display(absent, null))
    }

    @Test fun `missing readiness or normalized parameters never seeds ready cache`() {
        cache.display(ready().copy(state = null), params)
        assertNull(cache.display(calculating(), params))
        cache.display(ready().copy(params = null), params)
        assertNull(cache.display(calculating(), params))
        val invalid = params.copy(period = PeriodTypeCode.YEAR)
        cache.display(ready(invalid), invalid)
        assertNull(cache.display(calculating(invalid), invalid))
    }

    @Test fun `an empty ledger and a semantic context change clear all snapshots`() {
        cache.display(ready(), params)
        val empty = Analytics(hasData = false)
        assertEquals(empty, cache.display(empty, null))
        assertNull(cache.display(calculating(), params))
        cache.display(ready(), params)
        cache.clear()
        assertNull(cache.display(calculating(), params))
    }

    @Test fun `memory is bounded and an updated snapshot stays among recent responses`() {
        for (year in 2000..2031) {
            val key = params.copy(period = PeriodTypeCode.YEAR, date = PeriodKey(year.toString()))
            cache.display(ready(key), key)
        }
        val first = params.copy(period = PeriodTypeCode.YEAR, date = PeriodKey("2000"))
        cache.display(ready(first), first)
        cache.display(ready(), params)
        assertEquals(ready(first), cache.display(calculating(first), first))
        val evicted = first.copy(date = PeriodKey("2001"))
        assertNull(cache.display(calculating(evicted), evicted))
    }
}
