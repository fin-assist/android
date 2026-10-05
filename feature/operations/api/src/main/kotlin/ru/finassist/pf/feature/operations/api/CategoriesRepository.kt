package ru.finassist.pf.feature.operations.api

import kotlinx.coroutines.flow.Flow

data class CategoryInfo(val id: String, val name: String, val icon: String, val isSystem: Boolean, val kinds: Set<String>, val assignable: Boolean, val note: String?)

/** Cached `/v1/categories`; other features only need names and icons. */
interface CategoriesRepository {
    val categories: Flow<List<CategoryInfo>>
    suspend fun refresh()
}
