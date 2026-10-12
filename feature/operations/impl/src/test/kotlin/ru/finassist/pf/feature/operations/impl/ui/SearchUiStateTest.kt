package ru.finassist.pf.feature.operations.impl.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.OperationsFilter

class SearchUiStateTest {
    private val chips = SearchChips(period = true, category = true, amount = true, kind = true)

    @Test
    fun `query alone is the only filter, so reset must clear it`() {
        val s = SearchUiState(filter = OperationsFilter(q = "Самокатт"), chips = chips)
        assertTrue(s.hasFilters)
        assertTrue(s.onlyQuery)
    }

    @Test
    fun `another filter next to the query keeps the query on reset`() {
        assertFalse(SearchUiState(filter = OperationsFilter(q = "кофе", kind = OperationKindFilter.EXPENSE), chips = chips).onlyQuery)
        assertFalse(SearchUiState(filter = OperationsFilter(q = "кофе"), allTime = true, chips = chips).onlyQuery)
    }

    @Test
    fun `nothing entered is not a query-only state`() {
        val s = SearchUiState(chips = chips)
        assertFalse(s.hasFilters)
        assertTrue(s.onlyQuery) // nothing to keep either: reset leads to the initial state
    }
}
