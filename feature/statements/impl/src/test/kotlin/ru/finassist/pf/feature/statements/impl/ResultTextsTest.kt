package ru.finassist.pf.feature.statements.impl

import ru.finassist.pf.core.api.model.AnalyticsFeature
import ru.finassist.pf.core.api.model.FeatureAvailability
import ru.finassist.pf.core.api.model.ImportAccount
import ru.finassist.pf.core.api.model.ImportCoverage
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.IncompleteMonth
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.feature.statements.impl.domain.ResultTexts
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResultTextsTest {
    private fun String.plain() = replace(' ', ' ').replace(' ', ' ')
    private fun t(s: String) = OffsetDateTime.parse(s)

    private val coverage = ImportCoverage(
        fullMonths = 5, fullMonthsBefore = 0,
        newlyFullMonths = listOf("2026-04", "2026-05", "2026-06", "2026-07", "2026-08"),
        incompleteMonths = emptyList(),
        features = listOf(
            FeatureAvailability(AnalyticsFeature.COMPARISON, open = true, openedNow = true),
            FeatureAvailability(AnalyticsFeature.YEAR_FORECAST, open = false, openedNow = false, requiredFullMonths = 12),
            FeatureAvailability(AnalyticsFeature.UNKNOWN, open = false, openedNow = false),
        ),
    )

    private fun result(new: Int = 1086, total: Int = 1086, unread: Int = 0) = ImportResult(
        operationCount = total, newCount = new, duplicateCount = total - new, unreadCount = unread,
        firstOperationAt = t("2026-04-01T09:12:00+03:00"), lastOperationAt = t("2026-09-25T21:40:00+03:00"),
        isFirstImport = true, accounts = emptyList(), categorizedCount = 1052, ownTransferCount = 24,
        uncategorizedCount = 34, coverage = coverage, notices = emptyList(),
    )

    @Test
    fun titles() {
        assertEquals("Выписка загружена", ResultTexts.title(result()))
        assertEquals("Выписка загружена, кроме 5 строк", ResultTexts.title(result(unread = 5)).plain())
        assertEquals("Выписка загружена, кроме 1 строки", ResultTexts.title(result(unread = 1)).plain())
        assertEquals("Новых операций нет", ResultTexts.title(result(new = 0)))
        assertEquals("В выписке нет операций", ResultTexts.title(result(new = 0, total = 0)))
    }

    @Test
    fun `subtitle and duplicates`() {
        assertEquals("1 086 новых операций · 1 апреля — 25 сентября", ResultTexts.subtitle(result())!!.plain())
        assertEquals("312 операций уже были загружены — пропустили", ResultTexts.duplicates(result(new = 774))!!.plain())
        assertNull(ResultTexts.duplicates(result()))
    }

    @Test
    fun accounts() {
        val text = ResultTexts.accounts(
            listOf(
                ImportAccount("Текущий", "··4821", false),
                ImportAccount("Накопительный", "··0734", false),
                ImportAccount("Счёт другого банка", null, true),
            ),
        )
        assertEquals("В выписке 3 счёта: текущий ··4821, накопительный ··0734 и счёт другого банка", text!!.plain())
    }

    @Test
    fun coverage() {
        assertEquals("Теперь есть 5 полных месяцев — апрель — август", ResultTexts.newlyFull(coverage)!!.plain())
        assertEquals(listOf("Прогноз на год — нужно 12 полных месяцев, есть 5"), ResultTexts.locked(coverage).map { it.plain() })
        assertEquals("Открылось: сравнение с прошлым месяцем", ResultTexts.openedNow(coverage))
    }

    @Test
    fun `incomplete months`() {
        val sep = DateRange(t("2026-09-01T00:00:00+03:00"), t("2026-10-01T00:00:00+03:00"))
        val jul = DateRange(t("2026-07-01T00:00:00+03:00"), t("2026-08-01T00:00:00+03:00"))
        val jun = DateRange(t("2026-06-01T00:00:00+03:00"), t("2026-07-01T00:00:00+03:00"))
        assertEquals(
            "Сентябрь ещё не закончился — в выписке операции по 25 сентября",
            ResultTexts.incomplete(IncompleteMonth("2026-09", sep, t("2026-09-01T08:15:00+03:00"), t("2026-09-25T21:40:00+03:00"), emptyList(), true)).plain(),
        )
        assertEquals(
            "Июль — неполный: нет данных за 11–19 июля",
            ResultTexts.incomplete(
                IncompleteMonth(
                    "2026-07", jul, t("2026-07-01T08:00:00+03:00"), t("2026-07-31T20:00:00+03:00"),
                    listOf(DateRange(t("2026-07-11T00:00:00+03:00"), t("2026-07-20T00:00:00+03:00"))), false,
                ),
            ).plain(),
        )
        assertEquals(
            "Июнь — неполный: выписка начинается с 29 июня",
            ResultTexts.incomplete(IncompleteMonth("2026-06", jun, t("2026-06-29T10:00:00+03:00"), t("2026-06-30T20:00:00+03:00"), emptyList(), false)).plain(),
        )
    }
}
