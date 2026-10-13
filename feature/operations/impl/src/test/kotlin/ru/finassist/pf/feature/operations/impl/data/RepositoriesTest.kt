package ru.finassist.pf.feature.operations.impl.data

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.feature.operations.impl.ui.FakeCategoriesApi
import ru.finassist.pf.feature.operations.impl.ui.category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RepositoriesTest {
    private val api = FakeCategoriesApi(listOf(category("cat_a")))
    private val repository = CategoriesRepositoryImpl(api)

    @Test fun `the directory is fetched once per process`() = runTest {
        assertEquals(listOf("cat_a"), repository.categories().map { it.id })
        api.list = listOf(category("cat_b"))
        assertEquals(listOf("cat_a"), repository.categories().map { it.id })
        assertEquals(1, api.calls)
    }

    @Test fun `a forced refresh fetches again and replaces the cache`() = runTest {
        repository.categories()
        api.list = listOf(category("cat_b"))
        assertEquals(listOf("cat_b"), repository.categories(forceRefresh = true).map { it.id })
        assertEquals(listOf("cat_b"), repository.categories().map { it.id })
        assertEquals(2, api.calls)
    }

    @Test fun `a failed fetch is not cached`() = runTest {
        api.failure = AppError.Offline()
        assertFailsWith<AppError.Offline> { repository.categories() }
        api.failure = null
        assertEquals(listOf("cat_a"), repository.categories().map { it.id })
        assertEquals(2, api.calls)
    }

    @Test fun `every manual category change is signalled to the subscribers`() = runTest {
        val operations = OperationsRepositoryImpl()
        operations.categoryChanges.test {
            operations.notifyCategoryChanged()
            operations.notifyCategoryChanged()
            awaitItem()
            awaitItem()
            expectNoEvents()
        }
    }
}
