package ru.finassist.pf.feature.operations.api

import kotlinx.coroutines.flow.Flow
import ru.finassist.pf.core.api.model.Category

/** Category directory (api.md 5.1), cached per process; refreshed on demand. */
interface CategoriesRepository {
    suspend fun categories(forceRefresh: Boolean = false): List<Category>
}

/** Signals from the operations feature that other screens react to. */
interface OperationsRepository {
    /** A category was changed by hand: analytics for the affected month is being recalculated. */
    val categoryChanges: Flow<Unit>
}
