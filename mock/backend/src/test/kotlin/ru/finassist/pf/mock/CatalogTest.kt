package ru.finassist.pf.mock

import org.junit.Test
import ru.finassist.pf.core.api.model.CategoryKind
import ru.finassist.pf.core.api.model.SystemCategories
import ru.finassist.pf.mock.domain.Catalog
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Bank categories of a `MEMO` (docs/calc.md, 3.1). */
class CatalogTest {
    private val catalog = Catalog()

    @Test
    fun `empty, literal Без категории and Возврат MEMO are the system Без категории`() {
        for (memo in listOf("", "   ", "Без категории", " Возврат ")) {
            assertSame(catalog.uncategorized, catalog.bankCategory(memo), "«$memo»")
        }
        assertEquals(SystemCategories.UNCATEGORIZED, catalog.uncategorized.id)
    }

    @Test
    fun `a new bank category has both directions, the tag icon and a name cut to 64 characters`() {
        val long = "Очень длинная категория банка, которой нет в списке известных категорий Т-Банка"
        val c = catalog.bankCategory(" $long ")
        assertEquals(long.take(Catalog.NAME_LIMIT), c.name)
        assertEquals(listOf(CategoryKind.EXPENSE, CategoryKind.INCOME), c.kinds)
        assertEquals("tag", c.icon)
        assertTrue(!catalog.isExpense(c))
        assertTrue(catalog.isExpense(catalog.bankCategory("Супермаркеты")))
    }

    @Test
    fun `a second name with the same slug gets the next free id`() {
        val first = catalog.bankCategory("Кафе!")
        val second = catalog.bankCategory("Кафе?")
        assertEquals("cat_kafe", first.id)
        assertEquals("cat_kafe_2", second.id)
        assertSame(second, catalog.byId("cat_kafe_2"))
    }
}
