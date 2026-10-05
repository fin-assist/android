package ru.finassist.pf.feature.operations.impl.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.hintsStore: DataStore<Preferences> by preferencesDataStore("operations_hints")

/** Which coachmarks were dismissed (README «Coachmark»: three hints after the first import, one at a time). */
@Singleton
class HintsStore @Inject constructor(@ApplicationContext private val context: Context) {
    fun dismissed(id: String): Flow<Boolean> = context.hintsStore.data.map { it[booleanPreferencesKey(id)] ?: false }
    suspend fun dismiss(id: String) = context.hintsStore.edit { it[booleanPreferencesKey(id)] = true }
}
