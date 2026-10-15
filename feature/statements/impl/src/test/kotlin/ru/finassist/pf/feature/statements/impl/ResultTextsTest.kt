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
        assertEquals("1 086 операций · 1 апреля — 25 сентября 2026", ResultTexts.subtitle(result())!!.plain())
        assertEquals("1 апреля — 25 сентября", ResultTexts.period(result())!!.plain())
        assertNull(ResultTexts.period(result().copy(firstOperationAt = null)))
        assertEquals("1 операция · 1 апреля — 25 сентября 2026", ResultTexts.subtitle(result(new = 1, total = 1))!!.plain())
        assertEquals("Новых: 1 214 · 1 апреля — 25 сентября 2026", ResultTexts.subtitle(result(new = 1214, total = 1300).copy(isFirstImport = false))!!.plain())
        assertNull(ResultTexts.subtitle(result(new = 0)))
        assertNull(ResultTexts.subtitle(result(new = 0, total = 0)))
        assertEquals("312 операций уже были загружены — пропустили", ResultTexts.duplicates(result(new = 774))!!.plain())
        assertNull(ResultTexts.duplicates(result()))
    }

    @Test
    fun `nothing new, unread lines and categories`() {
        assertEquals("Всё из этого файла уже было загружено: 1 086 операций пропустили", ResultTexts.nothingNew(result(new = 0)).plain())
        assertEquals("Всё из этого файла уже было загружено: 1 операцию пропустили", ResultTexts.nothingNew(result(new = 0, total = 1)).plain())
        assertEquals("5 строк не прочитали — их нет в суммах.", ResultTexts.unread(result(unread = 5))!!.plain())
        assertEquals("1 строку не прочитали — их нет в суммах.", ResultTexts.unread(result(unread = 1))!!.plain())
        assertNull(ResultTexts.unread(result()))
        assertEquals("Категории проставлены у 1 052 операций", ResultTexts.categories(result())!!.plain())
        assertNull(ResultTexts.categories(result(new = 0)))
        assertEquals("24 из них — в категории «Между своими счетами»: не считаем их тратами и доходами", ResultTexts.ownTransfers(result())!!.plain())
        assertNull(ResultTexts.ownTransfers(result().copy(ownTransferCount = 0)))
        assertNull(ResultTexts.ownTransfers(result(new = 0)))
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
    }

    private val september = DateRange(t("2026-09-01T00:00:00+03:00"), t("2026-10-01T00:00:00+03:00"))
    private val septemberInProgress = IncompleteMonth("2026-09", september, t("2026-09-01T08:15:00+03:00"), t("2026-09-25T21:40:00+03:00"), emptyList(), true)

    private fun locked(f: AnalyticsFeature, months: Int) = FeatureAvailability(f, open = false, openedNow = false, requiredFullMonths = months)

    @Test
    fun `no notice after the first import of a long statement`() {
        assertNull(ResultTexts.coverageNotice(result()))
    }

    @Test
    fun `a short first statement says what is closed and when it opens`() {
        val r = result(new = 181, total = 181).copy(
            coverage = ImportCoverage(
                0, 0, emptyList(), listOf(septemberInProgress),
                listOf(locked(AnalyticsFeature.COMPARISON, 1), locked(AnalyticsFeature.REGULAR_PAYMENTS, 2)),
            ),
        )
        val n = ResultTexts.coverageNotice(r)!!
        assertEquals(false, n.positive)
        assertEquals("Часть аналитики пока закрыта", n.title)
        assertEquals(
            "Сентябрь ещё не закончился — в выписке операции по 25 сентября. Сравнение с прошлым месяцем появится с выпиской за август, " +
                "регулярные платежи — с 2 полными месяцами.",
            n.text.plain(),
        )
    }

    @Test
    fun `a closed feature this client does not know is told by the server's text (FIN-83)`() {
        val unknown = FeatureAvailability(
            AnalyticsFeature.UNKNOWN, open = false, openedNow = false, requiredFullMonths = 6,
            featureName = "Новая метрика", text = "Новая метрика — нужно 6 полных месяцев",
        )
        val r = result(new = 30, total = 30).copy(
            coverage = ImportCoverage(0, 0, emptyList(), emptyList(), listOf(locked(AnalyticsFeature.YEAR_FORECAST, 1), unknown)),
        )
        assertEquals("Новая метрика — нужно 6 полных месяцев. Прогноз на год появится с 1 полным месяцем.", ResultTexts.coverageNotice(r)!!.text)
    }

    @Test
    fun `a closed feature without a month in progress`() {
        val r = result(new = 30, total = 30).copy(
            coverage = ImportCoverage(0, 0, emptyList(), emptyList(), listOf(locked(AnalyticsFeature.YEAR_FORECAST, 1), locked(AnalyticsFeature.UNKNOWN, 3))),
        )
        assertEquals("Прогноз на год появится с 1 полным месяцем.", ResultTexts.coverageNotice(r)!!.text)
    }

    @Test
    fun `a later upload that completes a month says what opened`() {
        val r = result(new = 184, total = 184).copy(
            isFirstImport = false,
            coverage = ImportCoverage(
                1, 0, listOf("2026-08"), emptyList(),
                listOf(
                    FeatureAvailability(AnalyticsFeature.COMPARISON, open = true, openedNow = true),
                    FeatureAvailability(AnalyticsFeature.MONTHLY_CHART, open = true, openedNow = true, requiredMonthsWithData = 2),
                    locked(AnalyticsFeature.REGULAR_PAYMENTS, 2),
                ),
            ),
        )
        val n = ResultTexts.coverageNotice(r)!!
        assertEquals(true, n.positive)
        assertEquals("Теперь есть 1 полный месяц — август", n.title.plain())
        assertEquals(
            "На «Аналитике» открылись сравнение с прошлым месяцем и графики по месяцам. Регулярные платежи появятся с 2 полными месяцами.",
            n.text.plain(),
        )
        val one = r.copy(coverage = r.coverage.copy(features = listOf(FeatureAvailability(AnalyticsFeature.TYPICAL, open = true, openedNow = true))))
        assertEquals("На «Аналитике» открылось: обычные расходы за месяц.", ResultTexts.coverageNotice(one)!!.text)
        val three = r.copy(
            coverage = r.coverage.copy(
                features = listOf(AnalyticsFeature.NOTABLE_SPENDING, AnalyticsFeature.SMALL_FREQUENT, AnalyticsFeature.YEAR_FORECAST)
                    .map { FeatureAvailability(it, open = true, openedNow = true) },
            ),
        )
        assertEquals("На «Аналитике» открылись заметные траты, мелкие частые траты и прогноз на год.", ResultTexts.coverageNotice(three)!!.text)
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

    @Test
    fun `an unknown feature first does not take the capital letter and verb from the first known one (FIN-83 review)`() {
        val unknown = FeatureAvailability(
            AnalyticsFeature.UNKNOWN, open = false, openedNow = false, requiredFullMonths = 6,
            featureName = "Новая метрика", text = "Новая метрика — нужно 6 полных месяцев",
        )
        val r = result(new = 30, total = 30).copy(
            coverage = ImportCoverage(0, 0, emptyList(), emptyList(), listOf(unknown, locked(AnalyticsFeature.YEAR_FORECAST, 1))),
        )
        assertEquals("Новая метрика — нужно 6 полных месяцев. Прогноз на год появится с 1 полным месяцем.", ResultTexts.coverageNotice(r)!!.text)
    }
}
