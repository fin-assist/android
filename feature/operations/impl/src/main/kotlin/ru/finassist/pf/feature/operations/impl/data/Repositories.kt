package ru.finassist.pf.feature.operations.impl.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.CategoriesApi
import ru.finassist.pf.core.api.model.OperationDetails
import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.feature.operations.api.CategoriesRepository
import ru.finassist.pf.feature.operations.api.OperationChangesPublisher
import ru.finassist.pf.feature.operations.api.OperationRecords
import ru.finassist.pf.feature.operations.api.OperationsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** The category directory changes only with new banks, so one fetch per process is enough (api.md 5.1). */
@Singleton
class CategoriesRepositoryImpl @Inject constructor(private val api: CategoriesApi) : CategoriesRepository {
    private val mutex = Mutex()
    private var cache: List<Category>? = null

    override suspend fun categories(forceRefresh: Boolean): List<Category> = mutex.withLock {
        cache?.takeUnless { forceRefresh } ?: api.listCategories().categories.also { cache = it }
    }
}

@Singleton
class OperationsRepositoryImpl @Inject constructor() : OperationsRepository, OperationRecords, OperationChangesPublisher {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val categoryChanges: Flow<Unit> = _changes.asSharedFlow()

    private val _records = MutableSharedFlow<List<OperationDetails>>(extraBufferCapacity = 4)
    override val records = _records.asSharedFlow()

    override fun updateRecords(items: List<OperationDetails>) {
        _records.tryEmit(items)
    }

    override fun notifyCategoryChanged() {
        _changes.tryEmit(Unit)
    }
}
