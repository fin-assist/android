package ru.finassist.pf.feature.analytics.impl.ui

import ru.finassist.pf.core.api.model.AnalyticsPeriodInfo
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.common.time.DateRange
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyticsCoverageCaptionTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val period = AnalyticsPeriodInfo(
        range = DateRange(OffsetDateTime.parse("2026-09-01T00:00:00+03:00"), OffsetDateTime.parse("2026-10-01T00:00:00+03:00")),
        isCurrent = false, coverage = Coverage.NO_DATA, gaps = emptyList(),
    )
    private val tiles = Block(id = "tiles", type = BlockType.TILE_GROUP, status = BlockStatus.NONE, emptyText = "За этот период данных нет")

    @Test
    fun `visible supported empty tiles provide the one no data explanation`() {
        for (current in listOf(false, true)) {
            for (type in listOf(PeriodTypeCode.MONTH, PeriodTypeCode.QUARTER, PeriodTypeCode.YEAR, PeriodTypeCode.ALL_TIME)) {
                for (version in listOf(1, 2)) {
                    assertNull(analyticsCoverageCaption(period.copy(isCurrent = current), type, prepareAnalyticsBlocks(listOf(tiles.copy(v = version)), emptySet()), zone))
                }
            }
        }
    }

    @Test
    fun `hidden absent unsupported or empty server message retains local explanation`() {
        val variants = listOf(
            prepareAnalyticsBlocks(listOf(tiles), setOf("tiles")),
            prepareAnalyticsBlocks(emptyList(), emptySet()),
            prepareAnalyticsBlocks(listOf(tiles.copy(v = 99, altText = "Новая версия блока")), emptySet()),
            prepareAnalyticsBlocks(listOf(tiles.copy(emptyText = null)), emptySet()),
            prepareAnalyticsBlocks(listOf(tiles.copy(emptyText = " ")), emptySet()),
        )
        for (current in listOf(false, true)) {
            for (prepared in variants) {
                assertEquals("За этот период данных нет", analyticsCoverageCaption(period.copy(isCurrent = current), PeriodTypeCode.MONTH, prepared, zone))
            }
        }
    }

    @Test
    fun `rolling caption path receives profile zone for range and actual coverage`() {
        val rolling = period.copy(
            range = DateRange(OffsetDateTime.parse("2025-10-31T00:00:00+03:00"), OffsetDateTime.parse("2026-10-31T00:00:00+03:00")),
            coverage = Coverage.PARTIAL, dataFrom = OffsetDateTime.parse("2026-08-19T21:00:00Z"), dataTo = OffsetDateTime.parse("2026-10-29T22:15:00Z"),
        )
        val caption = analyticsCoverageCaption(rolling, PeriodTypeCode.ROLLING_YEAR, emptyList(), zone)!!.replace(' ', ' ').replace(' ', ' ')
        assertEquals("31 октября 2025 — 30 октября 2026 · Данные за 20 августа — 30 октября · период неполный", caption)
    }

    @Test
    fun `complete current period still has no local caption`() {
        assertNull(analyticsCoverageCaption(period.copy(isCurrent = true, coverage = Coverage.COMPLETE), PeriodTypeCode.MONTH, emptyList(), zone))
    }
}
