package ru.finassist.pf.feature.statements.impl.domain

import org.junit.Test
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.api.model.LoadedSummary
import ru.finassist.pf.core.common.time.DateRange
import java.time.OffsetDateTime
import kotlin.test.assertEquals

class DownloadTextsTest {
    private fun t(value: String) = OffsetDateTime.parse(value)
    private val first = ImportConfig(
        downloadUrl = "https://example.org/download", loginUrl = "https://example.org/login",
        maxFileSizeBytes = 10_485_760, acceptedFormats = listOf("OFX"),
        suggestedPeriod = DateRange(t("2025-10-08T00:00:00+03:00"), t("2026-10-09T00:00:00+03:00")),
    )
    private val loaded = LoadedSummary(t("2026-09-01T15:00:00+03:00"), t("2026-09-25T23:40:00+03:00"), 181)

    @Test fun `first and empty loaded states offer the yearly file`() {
        assertEquals("Скачать за год", DownloadTexts.label(first))
        assertEquals("Скачать за год", DownloadTexts.label(first.copy(loaded = loaded.copy(operationCount = 0))))
        assertEquals("Нажмите «Скачать за год» — файл за 8 октября 2025 — 8 октября 2026 сохранится в «Загрузки»", DownloadTexts.instruction(first))
    }

    @Test fun `existing operations offer new data for the server range`() {
        val config = first.copy(loaded = loaded,
            suggestedPeriod = DateRange(t("2026-09-25T00:00:00+03:00"), t("2026-10-09T00:00:00+03:00")))
        assertEquals("Скачать новые данные", DownloadTexts.label(config))
        assertEquals("Нажмите «Скачать новые данные» — файл за 25 сентября — 8 октября 2026 сохранится в «Загрузки»", DownloadTexts.instruction(config))
    }

    @Test fun `one day range excludes tomorrow and preserves profile calendar dates`() {
        val config = first.copy(loaded = loaded,
            suggestedPeriod = DateRange(t("2026-01-01T00:00:00+14:00"), t("2026-01-02T00:00:00+14:00")))
        assertEquals("Нажмите «Скачать новые данные» — файл за 1 января 2026 сохранится в «Загрузки»", DownloadTexts.instruction(config))
    }

    @Test fun `emptying imported operations restores first download copy`() {
        val config = first.copy(loaded = loaded)
        assertEquals("Скачать новые данные", DownloadTexts.label(config))
        assertEquals("Скачать за год", DownloadTexts.label(config.copy(loaded = null)))
    }
}
