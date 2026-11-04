package ru.finassist.pf.feature.analytics.impl.domain

import ru.finassist.pf.core.api.model.*
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.error.AppError
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
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
        hasData = true, params = key, currency = "RUB", timeZone = "Europe/Moscow", calculation = AnalyticsCalculation(10, false),
        period = AnalyticsPeriodInfo(DateRange(now.minusMonths(1), now), false, Coverage.COMPLETE, gaps = emptyList()),
        blocks = listOf(Block(id = "balance", type = BlockType.UNKNOWN, altText = "100")),
        state = AnalyticsState(stale = false, dataFrom = now, dataTo = now, fullMonths = 1, unreadLinesCount = 0, calculatedAt = now, recalculating = false),
    )
    private fun calculating(key: AnalyticsParams = params) = ready(key).let {
        it.copy(currency = "EUR", blocks = emptyList(), state = it.state!!.copy(recalculating = true), calculation = AnalyticsCalculation(10, true))
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
        assertNull(cache.display(calculating().copy(hasData = false, params = null), request))
    }

    @Test fun `an absent marked projection can use only its known request context`() {
        val original = ready()
        cache.display(original, params)
        val absent = calculating().copy(hasData = false, params = null)
        assertSame(original, cache.display(absent, params))
        assertNull(cache.display(absent, null))
    }

    @Test fun `missing readiness or normalized parameters never seeds ready cache`() {
        assertFailsWith<AppError.Server> { cache.display(ready().copy(state = null), params) }
        assertNull(cache.display(calculating(), params))
        assertFailsWith<AppError.Server> { cache.display(ready().copy(params = null), params) }
        assertNull(cache.display(calculating(), params))
        val invalid = params.copy(period = PeriodTypeCode.YEAR)
        assertFailsWith<AppError.Server> { cache.display(ready(invalid), invalid) }
        assertFailsWith<AppError.Server> { cache.display(calculating(invalid), invalid) }
    }

    @Test fun `an empty ledger and a semantic context change clear all snapshots`() {
        cache.display(ready(), params)
        val empty = Analytics(hasData = false, calculation = AnalyticsCalculation(10, false))
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
    private fun update(version: Int = 12, key: AnalyticsParams = params, zone: String = "Europe/Moscow") = AnalyticsBlockUpdate(
        key, "RUB", zone, version, listOf(Block(id = "regular_payments", type = BlockType.UNKNOWN, altText = "new amount")),
    )
    private fun withPayment() = ready().let { it.copy(blocks = it.blocks + Block(id = "regular_payments", type = BlockType.UNKNOWN, altText = "old amount")) }

    @Test fun `mutation replaces the complete listed block preserving all other data and global calculation`() {
        val old = withPayment()
        cache.display(old, params)
        val changed = cache.apply(update())!!
        assertEquals("new amount", changed.blocks.last().altText)
        assertSame(old.blocks.first(), changed.blocks.first())
        assertSame(old.state, changed.state)
        assertSame(old.calculation, changed.calculation)
        assertEquals(12, cache.requiredVersion)
        assertSame(changed, cache.display(old.copy(calculation = AnalyticsCalculation(11, false)), params))
        assertSame(changed, cache.display(calculating().copy(timeZone = "Asia/Tokyo"), params))
        val final = ready().copy(calculation = AnalyticsCalculation(12, false))
        assertSame(final, cache.display(final, params))
    }

    @Test fun `version barrier applies to other cached contexts and survives data invalidation`() {
        val otherKey = params.copy(date = PeriodKey("2026-08"))
        val other = ready(otherKey)
        cache.display(withPayment(), params)
        cache.display(other, otherKey)
        cache.apply(update())
        assertSame(other, cache.display(ready(otherKey), otherKey))
        cache.clear()
        assertNull(cache.display(ready(otherKey), otherKey))
        assertEquals(12, cache.requiredVersion)
    }

    @Test fun `unknown outcome cannot be confirmed by any GET and resolves only after successful command`() {
        val old = withPayment()
        cache.display(old, params)
        cache.unknownOutcome("payment")
        val newer = ready().copy(calculation = AnalyticsCalculation(100, false))
        assertSame(old, cache.display(newer, params))
        cache.apply(update())
        cache.resolved("payment")
        assertSame(newer, cache.display(newer, params))
    }

    @Test fun `wrong zone cannot mix a mutation block while a ready new zone replaces every cached context`() {
        val old = withPayment()
        cache.display(old, params)
        assertNull(cache.apply(update(zone = "Asia/Tokyo")))
        assertSame(old, cache.display(calculating(), params))
        val tokyo = ready().copy(timeZone = "Asia/Tokyo", calculation = AnalyticsCalculation(12, false))
        assertSame(tokyo, cache.display(tokyo, params))
        assertSame(tokyo, cache.display(calculating(), params))
    }

    @Test fun `intermediate empty responses retain the known whole snapshot and confirmed empty removes it`() {
        val old = withPayment()
        cache.display(old, params)
        val changed = cache.apply(update())
        val empty = Analytics(false, calculation = AnalyticsCalculation(11, false))
        assertSame(changed, cache.display(empty, params))
        assertSame(changed, cache.display(empty.copy(calculation = AnalyticsCalculation(12, true)), params))
        val confirmed = empty.copy(calculation = AnalyticsCalculation(12, false))
        assertSame(confirmed, cache.display(confirmed, params))
        assertNull(cache.display(calculating(), params))
    }

    @Test fun `explicit reads retire all freezes but preserve unknown commands and known version requirements`() {
        val old = withPayment()
        cache.display(old, params)
        cache.apply(update())
        cache.unknownOutcome("first")
        cache.unknownOutcome("second")
        cache.allowReadsDespiteUnknownOutcomes()
        assertTrue(cache.hasUnresolvedMutation)
        assertFalse(cache.freezesUnknownReads)
        assertEquals(12, cache.requiredVersion)
        assertTrue(cache.isWaiting(ready().copy(calculation = AnalyticsCalculation(11, false))))
        assertTrue(cache.isWaiting(calculating().copy(calculation = AnalyticsCalculation(12, true))))
        val fresh = ready().copy(calculation = AnalyticsCalculation(12, false))
        assertSame(fresh, cache.display(fresh, params))
        assertTrue(cache.hasUnresolvedMutation)
        cache.unknownOutcome("third")
        assertTrue(cache.freezesUnknownReads)
        cache.resolved("third")
        assertFalse(cache.freezesUnknownReads)
        cache.resetOwner()
        assertFalse(cache.hasUnresolvedMutation)
        assertEquals(0, cache.requiredVersion)
    }

}
