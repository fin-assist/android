package ru.finassist.pf.feature.statements.impl.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.feature.statements.api.StatementsEvent
import ru.finassist.pf.feature.statements.api.StatementsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatementsRepositoryImpl @Inject constructor() : StatementsRepository {
    private val _events = MutableSharedFlow<StatementsEvent>(extraBufferCapacity = 8)
    override val events: Flow<StatementsEvent> = _events.asSharedFlow()
    private val results = HashMap<String, ImportResult>()

    override fun lastResult(uploadId: String): ImportResult? = results[uploadId]

    fun importCompleted(uploadId: String, result: ImportResult) {
        results[uploadId] = result
        _events.tryEmit(StatementsEvent.ImportCompleted(uploadId, result))
    }

    fun uploadDeleted(uploadId: String) {
        results.remove(uploadId)
        _events.tryEmit(StatementsEvent.UploadDeleted(uploadId))
    }
}
