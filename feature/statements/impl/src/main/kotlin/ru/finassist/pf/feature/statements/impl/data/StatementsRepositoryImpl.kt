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
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.feature.statements.api.StatementsEvent
import ru.finassist.pf.feature.statements.api.StatementsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatementsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val tokenStore: TokenStore,
) : StatementsRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<StatementsEvent>(extraBufferCapacity = 8)
    override val events: Flow<StatementsEvent> = _events.asSharedFlow()
    private val results = HashMap<String, ImportResult>()

    override fun lastResult(uploadId: String): ImportResult? = results[uploadId]

    /** Keyed by user: hints of one account must never show up for another one signing in on this phone. */
    override suspend fun consumeFirstImportHints(): Boolean {
        val key = hintsKey() ?: return false
        val pending = dataStore.data.first()[key] == true
        if (pending) dataStore.edit { it.remove(key) }
        return pending
    }

    private suspend fun hintsKey() = tokenStore.current()?.userId?.takeIf { it.isNotEmpty() }
        ?.let { booleanPreferencesKey("statements.first_import_hints.$it") }

    fun importCompleted(uploadId: String, result: ImportResult) {
        results[uploadId] = result
        if (result.isFirstImport && result.newCount > 0) {
            scope.launch { hintsKey()?.let { key -> dataStore.edit { it[key] = true } } }
        }
        _events.tryEmit(StatementsEvent.ImportCompleted(uploadId, result))
    }

    fun uploadDeleted(uploadId: String) {
        results.remove(uploadId)
        _events.tryEmit(StatementsEvent.UploadDeleted(uploadId))
    }

}
