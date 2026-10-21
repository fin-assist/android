package ru.finassist.pf.feature.operations.api

import kotlinx.coroutines.flow.Flow
import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.OperationDetails

/** Category directory (api.md 5.1), cached per process; refreshed on demand. */
interface CategoriesRepository {
    suspend fun categories(forceRefresh: Boolean = false): List<Category>
}

/** Signals from the operations feature that other screens react to. */
interface OperationsRepository {
    /** A category was changed by hand: analytics for the affected month is being recalculated. */
    val categoryChanges: Flow<Unit>
}

/** Fresh operation records from details and category-change replies for the visible lists. */
interface OperationRecords {
    /** Each batch carries the current records returned by the server, including their timestamps. */
    val records: Flow<List<OperationDetails>>
}

/** Publishes successful detail and category changes without exposing a concrete event bus. */
interface OperationChangesPublisher {
    /** Replaces matching rows in subscribers with the records returned by the server. */
    fun updateRecords(items: List<OperationDetails>)

    /** Tells subscribers to refresh totals and filtering after a manual category change. */
    fun notifyCategoryChanged()
}
