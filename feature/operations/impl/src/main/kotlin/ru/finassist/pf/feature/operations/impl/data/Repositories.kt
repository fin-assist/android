package ru.finassist.pf.feature.operations.impl.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.CategoriesApi
import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.feature.operations.api.CategoriesRepository
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
class OperationsRepositoryImpl @Inject constructor() : OperationsRepository {
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val categoryChanges: Flow<Unit> = _changes.asSharedFlow()

    fun notifyCategoryChanged() {
        _changes.tryEmit(Unit)
    }
}
