package ru.finassist.pf.mock

import org.junit.Test
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TileGroupData
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.domain.AnalyticsComposer
import java.time.OffsetDateTime
import java.io.File
import ru.finassist.pf.mock.ofx.OfxParser
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ForecastHistoryTest {
    @Test fun `rolling year keeps actual expenses and explains eleven full calendar months`() {
        val clock = TestClock(OffsetDateTime.parse("2026-10-31T12:00:00+03:00"))
        val backend = testBackend(clock).apply {
            load(statement("2025-10-30", "2026-10-30", trn("a", "2026-08-15T12:00", 1000, "Магазин", "Супермаркеты")))
        }
        val snapshot = backend.analytics.analytics(PeriodTypeCode.YEAR, PeriodKey("2026"), TransferMode.WITH)
        val data = AnalyticsComposer.compose(snapshot)
        val tiles = assertNotNull(data.blocks.single { it.id == "tiles" }.decode(TileGroupData.serializer())).tiles
        assertEquals(listOf("balance", "expense", "daily_expense", "income", "forecast"), tiles.map { it.id })
        assertEquals(100_000.0, tiles.single { it.id == "expense" }.value)
        assertEquals(BlockStatus.LOCKED, tiles.last().status)
        val text = assertNotNull(tiles.last().lock).text.replace('\u00a0', ' ')
        assertTrue(text.contains("есть 11"))
        assertTrue(text.contains("независимо от выбранного года"))
        assertTrue(text.contains("ноябрь 2025"))
        assertTrue(text.contains("сентябрь 2026"))
        assertTrue(text.contains("октябрь 2025 (данные за 30–31 октября)"))
        assertTrue(text.contains("октябрь 2026 (данные за 1–30 октября; месяц ещё не закончился)"))
        assertEquals(11, snapshot.state?.fullMonths)
    }

    @Test fun `several uploads and gaps do not invent a continuous full history`() {
        val clock = TestClock(OffsetDateTime.parse("2026-10-20T12:00:00+03:00"))
        val backend = testBackend(clock).apply {
            load(statement("2025-11-01", "2025-11-15", trn("a", "2025-11-10T12:00", 1000, "Магазин", "Супермаркеты")))
            load(statement("2025-11-16", "2025-11-30"))
            load(statement("2026-02-01", "2026-02-28"))
            load(statement("2026-10-01", "2026-10-09"))
            load(statement("2026-10-13", "2026-10-19"))
        }
        val snapshot = backend.analytics.analytics(PeriodTypeCode.YEAR, PeriodKey("2026"), TransferMode.WITH)
        val tiles = assertNotNull(AnalyticsComposer.compose(snapshot).blocks.single { it.id == "tiles" }.decode(TileGroupData.serializer())).tiles
        val text = assertNotNull(tiles.last().lock).text.replace('\u00a0', ' ')
        assertEquals(2, snapshot.state?.fullMonths)
        assertTrue(text.contains("Учтены полные месяцы: ноябрь 2025, февраль 2026."))
        assertTrue(text.contains("нет данных за 10–12 октября"))
        assertTrue(text.contains("Месяцы без данных: декабрь 2025 — январь 2026"))
        assertFalse(text.contains("ноябрь 2025 — февраль 2026"))
    }

    @Test fun `explicit period without coverage has an empty tile group and no zero amounts`() {
        val backend = testBackend().apply { load(statement("2026-09-01", "2026-09-30", trn("a", "2026-09-10T12:00", 1000, "Магазин", "Супермаркеты"))) }
        val snapshot = backend.analytics.analytics(PeriodTypeCode.YEAR, PeriodKey("2025"), TransferMode.WITH)
        val tiles = AnalyticsComposer.compose(snapshot).blocks.single { it.id == "tiles" }
        assertEquals(null, snapshot.navigation?.previous)
        assertEquals(null, snapshot.navigation?.next)
        assertEquals(BlockStatus.NONE, tiles.status)
        assertEquals("За этот период данных нет", tiles.emptyText)
        assertEquals(emptyList(), assertNotNull(tiles.decode(TileGroupData.serializer())).tiles)
    }
    @Test fun `bundled UI fixture reproduces the blocked year and never needs edge transactions`() {
        val clock = TestClock(OffsetDateTime.parse("2026-10-08T12:00:00+03:00"))
        val document = OfxParser.parse(File("../../app/src/mockE2e/assets/e2e/statements/forecast_history.ofx").readText())
        assertTrue(document.unreadLines.isEmpty())
        assertEquals(1, document.transactions.size)
        val backend = testBackend(clock).apply { load(document) }
        val year = backend.analytics.analytics(PeriodTypeCode.YEAR, PeriodKey("2026"), TransferMode.WITH)
        assertEquals(11, year.state?.fullMonths)
        val tiles = assertNotNull(AnalyticsComposer.compose(year).blocks.single { it.id == "tiles" }.decode(TileGroupData.serializer())).tiles
        assertEquals(100_000.0, tiles.single { it.id == "expense" }.value)
        assertTrue(tiles.last().lock?.text?.contains("есть 11") == true)
        val month = backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey("2026-10"), TransferMode.WITH)
        assertEquals(ru.finassist.pf.core.api.model.LockReason.TOO_EARLY_IN_MONTH, month.tiles?.forecast?.lock?.reason)
    }

    @Test fun `long gaps are grouped while disconnected full months remain separate`() {
        val clock = TestClock(OffsetDateTime.parse("2026-11-01T12:00:00+03:00"))
        val backend = testBackend(clock).apply {
            load(statement("2020-01-01", "2020-01-31", trn("old", "2020-01-15T12:00", 500, "Магазин", "Супермаркеты")))
            load(statement("2026-09-01", "2026-10-31", trn("recent", "2026-10-15T12:00", 1000, "Магазин", "Супермаркеты")))
        }
        for (mode in listOf(TransferMode.WITH, TransferMode.WITHOUT)) {
            val snapshot = backend.analytics.analytics(PeriodTypeCode.YEAR, PeriodKey("2026"), mode)
            val tiles = assertNotNull(AnalyticsComposer.compose(snapshot).blocks.single { it.id == "tiles" }.decode(TileGroupData.serializer())).tiles
            val text = assertNotNull(tiles.last().lock).text
            assertTrue(text.contains("Учтены полные месяцы: январь 2020, сентябрь 2026 — октябрь 2026."))
            assertTrue(text.contains("Месяцы без данных: февраль 2020 — август 2026."))
            assertFalse(text.contains("месяц ещё не закончился"))
            assertEquals(3, snapshot.state?.fullMonths)
        }
    }

    @Test fun `one incomplete month hides the chart also for an explicit no-data period`() {
        val backend = testBackend().apply { load(statement("2026-09-15", "2026-09-30", trn("one", "2026-09-20T12:00", 1000, "Магазин", "Супермаркеты"))) }
        val snapshot = backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey("2026-08"), TransferMode.WITH)
        val response = AnalyticsComposer.compose(snapshot)
        assertEquals(null, response.navigation?.previous)
        assertEquals(null, response.navigation?.next)
        assertFalse(response.blocks.any { it.id == "monthly_chart" })
        assertEquals(BlockStatus.NONE, response.blocks.single { it.id == "tiles" }.status)
    }

}
