package ru.finassist.pf.mock.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.UploadFile
import ru.finassist.pf.core.api.model.AnswerBlockEvent
import ru.finassist.pf.core.api.model.AnswerChunkEvent
import ru.finassist.pf.core.api.model.AnswerDoneEvent
import ru.finassist.pf.core.api.model.AnswerErrorEvent
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AnswerSnapshotEvent
import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.Chip
import ru.finassist.pf.core.api.model.ImportProgress
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.PhoneVerificationStatusEvent
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.FAST
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.MockConfig
import ru.finassist.pf.mock.NOW
import ru.finassist.pf.mock.TestClock
import ru.finassist.pf.mock.api.MockAnalyticsApi
import ru.finassist.pf.mock.api.MockAssistantApi
import ru.finassist.pf.mock.api.MockAuthApi
import ru.finassist.pf.mock.api.MockOperationsApi
import ru.finassist.pf.mock.api.MockProfileApi
import ru.finassist.pf.mock.api.MockStatementsApi
import ru.finassist.pf.mock.fixtureDoc
import ru.finassist.pf.mock.fixtureText
import ru.finassist.pf.mock.load
import ru.finassist.pf.mock.testBackend

/**
 * One user of the fake backend for a contract case: signed in, the anonymized statement loaded ([loaded]), the
 * assistant consent given ([consent]). [call] and [stream] render a mock call as its HTTP exchange ([Wire]).
 */
internal class Scene(
    config: MockConfig = FAST,
    loaded: Boolean = true,
    consent: Boolean = true,
    signedIn: Boolean = true,
) {
    val clock = TestClock(NOW)
    val backend: MockBackend = testBackend(clock, config, userId = if (signedIn) "u1" else "", assistantConsent = consent)
        .also { if (loaded) it.load(fixtureDoc()) }

    val auth = MockAuthApi(backend)
    val profile = MockProfileApi(backend)
    val statements = MockStatementsApi(backend)
    val operations = MockOperationsApi(backend)
    val analytics = MockAnalyticsApi(backend)
    val assistant = MockAssistantApi(backend)

    /** `{param}` of the operation's path filled in order. */
    fun path(operationId: String, vararg params: String): String {
        var i = 0
        return Contract.operation(operationId).path.replace(PARAM) { params.getOrElse(i++) { _ -> error("$operationId: no value for a parameter") } }
    }

    suspend fun <T> call(operationId: String, serializer: KSerializer<T>?, vararg params: String, block: suspend () -> T) =
        Wire.exchange(operationId, path(operationId, *params), serializer, block)

    suspend fun phoneEvents(token: String) =
        Wire.stream("streamPhoneVerification", path("streamPhoneVerification"), auth.streamPhoneVerification(token)) {
            Wire.event("status", PhoneVerificationStatusEvent.serializer(), it)
        }

    /** [flow] taken earlier stays the stream of an upload removed since (a cancelled import is discarded). */
    suspend fun progressEvents(uploadId: String, flow: Flow<ImportEvent> = statements.streamProgress(uploadId)) =
        Wire.stream("streamStatementProgress", path("streamStatementProgress", uploadId), flow) { e ->
            when (e) {
                is ImportEvent.Progress -> Wire.event("progress", ImportProgress.serializer(), e.progress)
                is ImportEvent.Complete -> Wire.event("complete", ImportResult.serializer(), e.result)
                is ImportEvent.Error -> Wire.errorEvent(e.code, e.message, e.presentation)
            }
        }

    suspend fun answerEvents(answerId: String) =
        Wire.stream("streamAnswer", path("streamAnswer", answerId), assistant.streamAnswer(answerId)) { e ->
            when (e) {
                is AnswerEvent.Snapshot -> Wire.event("snapshot", AnswerSnapshotEvent.serializer(), e.event)
                is AnswerEvent.Chunk -> Wire.event("chunk", AnswerChunkEvent.serializer(), e.event)
                is AnswerEvent.BlockAdded -> Wire.event("block", AnswerBlockEvent.serializer(), e.event)
                is AnswerEvent.Source -> Wire.event("source", AnswerSource.serializer(), e.event)
                is AnswerEvent.ChipAdded -> Wire.event("chip", Chip.serializer(), e.event)
                is AnswerEvent.Done -> Wire.event("done", AnswerDoneEvent.serializer(), e.event)
                is AnswerEvent.Error -> Wire.event("error", AnswerErrorEvent.serializer(), e.event)
                is AnswerEvent.Unknown -> throw AssertionError("the fake backend sent an event the client does not know: ${e.type}")
            }
        }

    /** Waits for a stream to end (an answer generated, an import finished). */
    suspend fun <T> drain(flow: Flow<T>): List<T> = withTimeout(10_000) { flow.toList() }

    fun statementFile(name: String = "statement.ofx", text: String = fixtureText): UploadFile {
        val bytes = text.toByteArray()
        return UploadFile(name, bytes.size.toLong()) { bytes.inputStream() }
    }

    /** Ids of the regular payments the fixture has in its last full month. */
    suspend fun regularPaymentIds(): List<String> =
        backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH).insights!!.regularPayments.items.orEmpty().map { it.id }

    private companion object {
        val PARAM = Regex("""\{[a-z_]+}""")
    }
}
