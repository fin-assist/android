package ru.finassist.pf.feature.operations.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.OperationItem
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationStatus
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.screenshot.DesignCheck
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.at
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.categories
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.today
import ru.finassist.pf.feature.operations.impl.ui.OperationsDesignFixtures.zone
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Artboards `Search` / `SearchDark` / `SearchInitial` / `SearchEmpty` / `SearchOlder` / `SearchPeriod`:
 * «Самокат» in «Супермаркеты», nine operations over three months.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchDesignCheckTest {

    private fun op(month: Int, day: Int, rub: Long) = OperationItem(
        id = "s_${month}_$day", kind = OperationKind.EXPENSE, occurredAt = at(month, day, 12), amount = Money(rub * 100),
        currency = "RUB", title = "Самокат", categoryId = "cat_supermarkets", categoryName = "Супермаркеты",
        categoryIcon = "cart", isCategoryManual = false, status = OperationStatus.POSTED,
    )

    private val items = listOf(
        op(9, 24, 820), op(9, 11, 1_150),
        op(8, 29, 640), op(8, 16, 930), op(8, 2, 780),
        op(7, 28, 690), op(7, 19, 810), op(7, 10, 560), op(7, 3, 820),
    )

    // The server's default window: the last 12 months, from October 2025.
    private val range = DateRange(
        OffsetDateTime.of(2025, 10, 1, 0, 0, 0, 0, ZoneOffset.ofHours(3)),
        OffsetDateTime.of(2026, 9, 29, 0, 0, 0, 0, ZoneOffset.ofHours(3)),
    )

    private val chips = SearchChips(period = true, category = true, amount = true, kind = true)

    private val results = SearchUiState(
        filter = OperationsFilter(q = "Самокат", categoryId = "cat_supermarkets"),
        result = SearchResult.Found(items, totalCount = 9, range = range, hasOlderData = false),
        chips = chips,
        categories = categories,
    )

    private val actions = SearchActions(
        setQuery = {}, openSheet = {}, toggleKind = {}, clearAnalyticsScope = {}, reset = {}, retry = {},
        searchAllTime = {}, setPeriod = { _, _, _ -> }, setCategory = {}, setAmount = { _, _ -> },
    )

    // A sheet is drawn over the viewport, so that artboard is not grown to the whole list.
    private fun capture(name: String, state: SearchUiState, heightDp: Int, dark: Boolean = false, popups: Boolean = false) =
        DesignCheck.capture(name, dark = dark, heightDp = heightDp, fullHeight = !popups && heightDp > DesignCheck.PHONE_HEIGHT_DP, popups = popups) {
            SearchContent(state, zone, today, onBack = {}, onOpenOperation = {}, actions = actions)
        }

    @Test fun search() = capture("Search", results, heightDp = 892)

    @Test fun searchDark() = capture("SearchDark", results, heightDp = 892, dark = true)

    @Test fun searchInitial() = capture(
        "SearchInitial",
        SearchUiState(chips = chips, categories = categories),
        heightDp = 844,
    )

    @Test fun searchEmpty() = capture(
        "SearchEmpty",
        SearchUiState(
            filter = OperationsFilter(q = "Самокатт"),
            result = SearchResult.Found(emptyList(), totalCount = 0, range = range, hasOlderData = false),
            chips = chips,
            categories = categories,
        ),
        heightDp = 844,
    )

    @Test fun searchOlder() = capture(
        "SearchOlder",
        results.copy(result = SearchResult.Found(items, totalCount = 9, range = range, hasOlderData = true)),
        heightDp = 960,
    )

    @Test fun searchPeriod() = capture("SearchPeriod", results.copy(sheet = SearchSheet.PERIOD), heightDp = 892, popups = true)
}
