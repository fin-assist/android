package ru.finassist.pf.feature.statements.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.AnalyticsFeature
import ru.finassist.pf.core.api.model.FeatureAvailability
import ru.finassist.pf.core.api.model.ImportAccount
import ru.finassist.pf.core.api.model.ImportCoverage
import ru.finassist.pf.core.api.model.ImportNotice
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.ImportTotals
import ru.finassist.pf.core.api.model.ImportTotalsScope
import ru.finassist.pf.core.api.model.IncompleteMonth
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.UnreadLine
import ru.finassist.pf.core.api.model.UnreadReason
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.screenshot.DesignCheck
import java.time.OffsetDateTime

/**
 * Artboards `ImportResult*`: first import, repeat with unread lines (and the lines list), nothing new, empty
 * statement, a short September, an unlocked August, no expenses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportResultDesignCheckTest {
    private fun t(s: String) = OffsetDateTime.parse(s)

    private val accounts = listOf(
        ImportAccount("Текущий", "··4821", false),
        ImportAccount("Накопительный", "··0734", false),
        ImportAccount("Счёт другого банка", null, true),
    )
    private val uncategorized = OperationsFilter(selection = "uncategorized", selectionName = "Без категории")

    private val september = DateRange(t("2026-09-01T00:00:00+03:00"), t("2026-10-01T00:00:00+03:00"))
    private val septemberInProgress = IncompleteMonth(
        "2026-09", september, t("2026-09-01T08:15:00+03:00"), t("2026-09-25T21:40:00+03:00"), emptyList(), inProgress = true,
    )

    private fun feature(f: AnalyticsFeature, open: Boolean, openedNow: Boolean = false, full: Int? = null) =
        FeatureAvailability(f, open = open, openedNow = openedNow, requiredFullMonths = full)

    private fun totals(scope: ImportTotalsScope, expense: Long, income: Long) =
        ImportTotals(scope, Money(expense * 100), Money(income * 100))

    /** 1 086 operations, 1 April — 25 September 2026. */
    private val first = ImportResult(
        operationCount = 1086, newCount = 1086, duplicateCount = 0, unreadCount = 0,
        firstOperationAt = t("2026-04-01T09:12:00+03:00"), lastOperationAt = t("2026-09-25T21:40:00+03:00"),
        isFirstImport = true, totals = totals(ImportTotalsScope.ALL, 528_820, 921_400), accounts = accounts,
        categorizedCount = 1052, ownTransferCount = 24, uncategorizedCount = 34, uncategorizedFilters = uncategorized,
        coverage = ImportCoverage(
            fullMonths = 5, fullMonthsBefore = 0,
            newlyFullMonths = listOf("2026-04", "2026-05", "2026-06", "2026-07", "2026-08"),
            incompleteMonths = listOf(septemberInProgress),
            features = listOf(feature(AnalyticsFeature.COMPARISON, open = true, openedNow = true)),
        ),
        notices = emptyList(),
    )

    /** Repeat upload: 214 new of 526, 312 duplicates, 5 unread lines. */
    private val repeat = first.copy(
        operationCount = 526, newCount = 214, duplicateCount = 312, unreadCount = 5,
        firstOperationAt = t("2026-09-01T08:15:00+03:00"), isFirstImport = false,
        totals = totals(ImportTotalsScope.NEW, 96_340, 154_200),
        categorizedCount = 203, ownTransferCount = 6, uncategorizedCount = 11,
        coverage = ImportCoverage(5, 5, emptyList(), listOf(septemberInProgress), emptyList()),
    )

    private val nothingNew = first.copy(
        newCount = 0, duplicateCount = 1086, isFirstImport = false, totals = null,
        categorizedCount = 0, ownTransferCount = 0, uncategorizedCount = 0, uncategorizedFilters = null,
        coverage = ImportCoverage(5, 5, emptyList(), listOf(septemberInProgress), emptyList()),
    )

    private val empty = ImportResult(
        operationCount = 0, newCount = 0, duplicateCount = 0, unreadCount = 0, isFirstImport = true,
        accounts = emptyList(), categorizedCount = 0, ownTransferCount = 0, uncategorizedCount = 0,
        coverage = ImportCoverage(0, 0, emptyList(), emptyList(), emptyList()),
        notices = listOf(ImportNotice.EMPTY_STATEMENT),
    )

    /** First import of 1–25 September only: no full month yet, comparison and regular payments closed. */
    private val short = first.copy(
        operationCount = 181, newCount = 181,
        firstOperationAt = t("2026-09-01T08:15:00+03:00"),
        totals = totals(ImportTotalsScope.ALL, 84_320, 156_900),
        categorizedCount = 176, ownTransferCount = 4, uncategorizedCount = 5,
        coverage = ImportCoverage(
            fullMonths = 0, fullMonthsBefore = 0, newlyFullMonths = emptyList(),
            incompleteMonths = listOf(septemberInProgress),
            features = listOf(
                feature(AnalyticsFeature.COMPARISON, open = false, full = 1),
                feature(AnalyticsFeature.REGULAR_PAYMENTS, open = false, full = 2),
            ),
        ),
    )

    /** August added to September: the first full month opens comparison and the monthly chart. */
    private val unlocked = first.copy(
        operationCount = 184, newCount = 184, isFirstImport = false,
        firstOperationAt = t("2026-08-01T08:40:00+03:00"), lastOperationAt = t("2026-08-31T20:10:00+03:00"),
        totals = totals(ImportTotalsScope.NEW, 89_700, 152_400),
        categorizedCount = 180, ownTransferCount = 5, uncategorizedCount = 4,
        coverage = ImportCoverage(
            fullMonths = 1, fullMonthsBefore = 0, newlyFullMonths = listOf("2026-08"),
            incompleteMonths = emptyList(),
            features = listOf(
                feature(AnalyticsFeature.COMPARISON, open = true, openedNow = true),
                FeatureAvailability(AnalyticsFeature.MONTHLY_CHART, open = true, openedNow = true, requiredMonthsWithData = 2),
                feature(AnalyticsFeature.REGULAR_PAYMENTS, open = false, full = 2),
            ),
        ),
    )

    private val noExpenses = short.copy(
        operationCount = 12, newCount = 12,
        totals = totals(ImportTotalsScope.ALL, 0, 3_460),
        accounts = listOf(ImportAccount("Накопительный", "··0734", false)),
        categorizedCount = 12, ownTransferCount = 0, uncategorizedCount = 0, uncategorizedFilters = null,
        coverage = short.coverage.copy(features = emptyList()),
        notices = listOf(ImportNotice.NO_EXPENSES),
    )

    private fun capture(name: String, result: ImportResult, heightDp: Int, dark: Boolean = false) =
        DesignCheck.capture(name, dark = dark, heightDp = heightDp) {
            ResultContent(
                result, uploadEnabled = true, onDone = {}, onOpenSearch = {}, onOpenUnread = {}, onOpenAnalytics = {},
                onUploadAnother = {},
            )
        }

    @Test fun importResult() = capture("ImportResult", first, 856)

    @Test fun importResultDark() = capture("ImportResultDark", first, 856, dark = true)

    @Test fun importResultRepeat() = capture("ImportResultRepeat", repeat, 1028)

    @Test fun importResultNothingNew() = capture("ImportResultNothingNew", nothingNew, 844)

    @Test fun importResultEmpty() = capture("ImportResultEmpty", empty, 844)

    @Test fun importResultShort() = capture("ImportResultShort", short, 1044)

    @Test fun importResultUnlocked() = capture("ImportResultUnlocked", unlocked, 1044)

    @Test fun importResultNoExpenses() = capture("ImportResultNoExpenses", noExpenses, 844)

    /** The mockup opens the unread lines as a sheet over the result; the app opens them as a separate screen. */
    @Test fun importResultRepeatSheet() {
        fun line(n: Int, date: String?, reason: UnreadReason, name: String) = UnreadLine(
            uploadId = "u2", fileName = "operations_2026.ofx", lineNumber = n, date = date?.let(::t), reason = reason,
            reasonName = name,
        )
        val state = UnreadUiState(
            loading = false,
            lines = listOf(
                line(214, "2026-09-03T12:00:00+03:00", UnreadReason.BAD_AMOUNT, "Не распознали сумму"),
                line(251, null, UnreadReason.BAD_DATE, "Неизвестный формат даты"),
                line(298, "2026-09-11T12:00:00+03:00", UnreadReason.NO_DESCRIPTION, "Пустое описание"),
                line(312, null, UnreadReason.NO_DATE, "Нет даты операции"),
                line(377, "2026-09-21T12:00:00+03:00", UnreadReason.TRUNCATED, "Строка обрезана — не хватает данных"),
            ),
            userId = "u1",
        )
        DesignCheck.capture("ImportResultRepeatSheet", heightDp = 1028) {
            UnreadLinesContent(state, copied = false, onBack = {}, onRetry = {}, onReport = {})
        }
    }
}
