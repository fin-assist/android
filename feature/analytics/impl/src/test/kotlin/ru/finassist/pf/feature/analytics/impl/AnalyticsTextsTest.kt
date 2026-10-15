package ru.finassist.pf.feature.analytics.impl

import ru.finassist.pf.core.api.model.AnalyticsPeriodInfo
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.feature.analytics.impl.domain.AnalyticsTexts
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyticsTextsTest {
    private fun String.plain() = replace(' ', ' ').replace(' ', ' ')
    private fun t(s: String) = OffsetDateTime.parse(s)


    private fun month(m: Int, y: Int = 2026) = DateRange(
        t("%04d-%02d-01T00:00:00+03:00".format(y, m)),
        t(if (m == 12) "%04d-01-01T00:00:00+03:00".format(y + 1) else "%04d-%02d-01T00:00:00+03:00".format(y, m + 1)),
    )

    private fun period(type: Coverage = Coverage.PARTIAL, from: String? = "2026-09-01T08:15:00+03:00", to: String? = "2026-09-25T21:40:00+03:00", range: DateRange = month(9)) =
        AnalyticsPeriodInfo(range = range, isCurrent = true, coverage = type, dataFrom = from?.let(::t), dataTo = to?.let(::t), gaps = emptyList())

    @Test
    fun `period titles`() {
        assertEquals("Сентябрь 2026", AnalyticsTexts.periodTitle(PeriodKey("2026-09")))
        assertEquals("III квартал 2026", AnalyticsTexts.periodTitle(PeriodKey("2026-Q3")))
        assertEquals("2026", AnalyticsTexts.periodTitle(PeriodKey("2026")))
    }

    @Test
    fun `incomplete year caption`() {
        val year = AnalyticsPeriodInfo(
            range = DateRange(t("2026-01-01T00:00:00+03:00"), t("2027-01-01T00:00:00+03:00")), isCurrent = true,
            coverage = Coverage.PARTIAL, dataFrom = t("2026-04-01T00:00:00+03:00"), dataTo = t("2026-09-25T21:40:00+03:00"), gaps = emptyList(),
        )
        assertEquals("Апрель — сентябрь 2026 · год неполный", AnalyticsTexts.partialPeriod(year, PeriodTypeCode.YEAR)!!.plain())
        assertNull(AnalyticsTexts.partialPeriod(year, PeriodTypeCode.MONTH))
    }

    @Test
    fun `early period is named by its days`() {
        assertEquals(true, AnalyticsTexts.isEarly(PeriodTypeCode.MONTH, period(), hasPrevious = false))
        assertEquals(false, AnalyticsTexts.isEarly(PeriodTypeCode.MONTH, period(), hasPrevious = true))
        assertEquals(false, AnalyticsTexts.isEarly(PeriodTypeCode.MONTH, period(Coverage.COMPLETE), hasPrevious = false))
        assertEquals(false, AnalyticsTexts.isEarly(PeriodTypeCode.YEAR, period(), hasPrevious = false))
        assertEquals("1–25 сентября 2026", AnalyticsTexts.periodLabel(PeriodKey("2026-09"), period(), PeriodTypeCode.MONTH, early = true).plain())
        assertEquals("Сентябрь 2026", AnalyticsTexts.periodLabel(PeriodKey("2026-09"), period(), PeriodTypeCode.MONTH, early = false))
        assertEquals("Сентябрь 2026", AnalyticsTexts.periodLabel(PeriodKey("2026-09"), period(from = null), PeriodTypeCode.MONTH, early = true))
    }

    @Test
    fun `unread lines name the month`() {
        assertEquals("5 строк за сентябрь не прочитали — суммы могут быть неполными", AnalyticsTexts.unreadLines(5, PeriodTypeCode.MONTH, period()).plain())
        assertEquals("1 строку за период не прочитали — суммы могут быть неполными", AnalyticsTexts.unreadLines(1, PeriodTypeCode.YEAR, period()).plain())
    }

    @Test
    fun `unit names of the period types`() {
        assertEquals("месяц", AnalyticsTexts.unitName(PeriodTypeCode.MONTH))
        assertEquals("квартал", AnalyticsTexts.unitName(PeriodTypeCode.QUARTER))
        assertEquals("год", AnalyticsTexts.unitName(PeriodTypeCode.YEAR))
        assertEquals("месяц", AnalyticsTexts.unitName(PeriodTypeCode.UNKNOWN))
    }

    @Test
    fun `coverage caption under the period`() {
        assertNull(AnalyticsTexts.coverage(period(Coverage.COMPLETE)))
        assertEquals("За этот период данных нет", AnalyticsTexts.coverage(period(Coverage.NO_DATA)))
        assertEquals("Данные за 1–25 сентября", AnalyticsTexts.coverage(period())!!.plain())
        // Unknown coverage is shown as partial.
        assertEquals("Данные за 1–25 сентября", AnalyticsTexts.coverage(period(Coverage.UNKNOWN))!!.plain())
        assertEquals("Данные за часть периода", AnalyticsTexts.coverage(period(to = null)))
        val gap = DateRange(t("2026-07-11T00:00:00+03:00"), t("2026-07-20T00:00:00+03:00"))
        val july = AnalyticsPeriodInfo(
            range = month(7), isCurrent = false, coverage = Coverage.PARTIAL,
            dataFrom = t("2026-07-01T09:00:00+03:00"), dataTo = t("2026-07-31T20:00:00+03:00"), gaps = listOf(gap),
        )
        assertEquals("Данные за 1–31 июля, без 11–19 июля · период неполный", AnalyticsTexts.coverage(july)!!.plain())
    }
}
