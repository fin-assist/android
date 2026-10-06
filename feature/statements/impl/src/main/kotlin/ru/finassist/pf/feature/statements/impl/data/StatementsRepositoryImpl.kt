package ru.finassist.pf.feature.statements.impl.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.feature.statements.api.StatementsEvent
import ru.finassist.pf.feature.statements.api.StatementsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatementsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : StatementsRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<StatementsEvent>(extraBufferCapacity = 8)
    override val events: Flow<StatementsEvent> = _events.asSharedFlow()
    private val results = HashMap<String, ImportResult>()

    override fun lastResult(uploadId: String): ImportResult? = results[uploadId]

    override suspend fun consumeFirstImportHints(): Boolean {
        val pending = dataStore.data.first()[KEY_HINTS] == true
        if (pending) dataStore.edit { it.remove(KEY_HINTS) }
        return pending
    }

    fun importCompleted(uploadId: String, result: ImportResult) {
        results[uploadId] = result
        if (result.isFirstImport && result.newCount > 0) scope.launch { dataStore.edit { it[KEY_HINTS] = true } }
        _events.tryEmit(StatementsEvent.ImportCompleted(uploadId, result))
    }

    fun uploadDeleted(uploadId: String) {
        results.remove(uploadId)
        _events.tryEmit(StatementsEvent.UploadDeleted(uploadId))
    }

    private companion object {
        val KEY_HINTS = booleanPreferencesKey("statements.first_import_hints")
    }
}
