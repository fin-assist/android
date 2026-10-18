package ru.finassist.pf.mock

import org.junit.Test
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.mock.domain.MetricLock
import ru.finassist.pf.mock.domain.MetricStatus
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.api.model.InsightCardData
import ru.finassist.pf.mock.domain.AnalyticsComposer
import ru.finassist.pf.mock.domain.SmallFrequentCard
import java.time.OffsetDateTime
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Gaps of [FixtureTest]: the current-month forecast and the locks of a short history. */
class AnalyticsEngineTest {
    private val october = PeriodKey("2026-10")

    @Test
    fun `the month forecast opens on the 10th and extrapolates the expense over the whole month`() {
        val clock = TestClock(NOW) // 6 October
        val backend = testBackend(clock).apply {
            load(statement("2026-10-01", "2026-10-05", trn("a", "2026-10-03T12:00", 1_000, "Магазин", "Супермаркеты")))
        }
        val early = assertNotNull(backend.analytics.analytics(PeriodTypeCode.MONTH, october, TransferMode.WITH).tiles?.forecast)
        assertEquals(MetricStatus.LOCKED, early.status)
        assertEquals(MetricLock(LockReason.TOO_EARLY_IN_MONTH, availableFrom = OffsetDateTime.parse("2026-10-10T00:00:00+03:00")), early.lock)

        clock.at = OffsetDateTime.parse("2026-10-20T12:00:00+03:00")
        backend.load(statement("2026-10-06", "2026-10-19", trn("b", "2026-10-15T12:00", 1_000, "Магазин", "Супермаркеты")))
        val forecast = assertNotNull(backend.analytics.analytics(PeriodTypeCode.MONTH, october, TransferMode.WITH).tiles?.forecast)
        assertEquals(MetricStatus.READY, forecast.status)
        // 2 000 ₽ over 19 days with data → 31 days of October.
        assertEquals(200_000L * 31 / 19, forecast.value?.minor)
    }

    @Test
    fun `one month of history locks the chart and the insights that need more months`() {
        val backend = testBackend().apply {
            load(statement("2026-09-01", "2026-09-30", trn("a", "2026-09-03T12:00", 1_000, "Магазин", "Супермаркеты")))
        }
        val a = backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH)
        assertEquals(MetricLock(LockReason.NEED_MONTHS_WITH_DATA, required = 2, available = 1), a.monthlyChart?.lock)
        val insights = assertNotNull(a.insights)
        assertEquals(MetricLock(LockReason.NEED_FULL_MONTHS, required = 2, available = 1), insights.regularPayments.lock)
        // Notable spending counts full months before the selected one (docs/calc.md, section 5).
        assertEquals(MetricLock(LockReason.NEED_FULL_MONTHS, required = 3, available = 0), insights.notableSpending.lock)
    }

    @Test
    fun `small frequent spending of an incomplete month is counted over the last full month and names it`() {
        // September is full, October runs to the 5th (today is 6 October).
        val coffee = (1..10).map { trn("s$it", "2026-09-%02dT09:00".format(it * 2), 250, "Кофейня", "Кафе") } +
            (1..3).map { trn("o$it", "2026-10-0${it}T09:00", 250, "Кофейня", "Кафе") }
        val backend = testBackend().apply { load(statement("2026-09-01", "2026-10-05", *coffee.toTypedArray())) }
        fun card(key: PeriodKey): Pair<SmallFrequentCard, String?> {
            val snapshot = backend.analytics.analytics(PeriodTypeCode.MONTH, key, TransferMode.WITH)
            val block = AnalyticsComposer.compose(snapshot).blocks.single { it.id == "small_frequent" }
            return snapshot.insights!!.smallFrequent to block.decode(InsightCardData.serializer())?.description?.replace('\u00A0', ' ')?.replace('\u202F', ' ')
        }
        val text = "в месяц · кофейня: 10 раз по 250 ₽ — около 30 000 ₽ в год"

        val (sep, sepText) = card(PeriodKey("2026-09"))
        assertEquals(YearMonth.of(2026, 9), sep.month)
        assertEquals(text, sepText)

        val (oct, octText) = card(october)
        assertEquals(YearMonth.of(2026, 9), oct.month)
        assertEquals("по сентябрю · $text", octText)
    }
}
