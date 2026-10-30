package ru.finassist.pf.e2e

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.finassist.pf.core.common.error.AppError
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException

/** Closed phases, persisted before a potentially suspending setup boundary. */
enum class StageBootstrapPhase(val wire: String) {
    INITIAL("initial"), SESSION("session"), LOCK("lock"), DOCUMENT("document"), PHONE("phone"),
    VERIFICATION("verification"), REGISTER("register"), SESSION_COMMIT("session_commit"), PROFILE("profile"),
    IMPORT("import"), IMPORT_PROGRESS("import_progress"), ANALYTICS("analytics"), PASSCODE("passcode"), CLEANUP_DELETE("cleanup_delete"), CLEANUP_LOCAL("cleanup_local"), DONE("done"),
}

enum class StageControlOperation(val wire: String) {
    CONFIG("config"), SETUP("setup_run"), CLEANUP("cleanup_run"), SIGN_IN("sign_in"), PROCESS_QUESTION("expect_process_question"),
}

enum class StageControlOutcome(val wire: String) { STARTED("started"), PASSED("passed"), FAILED("failed") }

enum class StageBootstrapFailureCode(val wire: String) {
    SESSION_STATE("session_state"), DOCUMENT_INVALID("document_invalid"), VERIFICATION_NOT_VERIFIED("verification_not_verified"),
    CANDIDATE_NOT_NEW("candidate_not_new"), REGISTRATION_RECEIPT_MISSING("registration_receipt_missing"),
    PROFILE_IDENTITY_MISMATCH("profile_identity_mismatch"), IMPORT_NOT_COMPLETE("import_not_complete"),
}

class StageBootstrapFailure(val code: StageBootstrapFailureCode) : IllegalStateException(code.wire)

/** Diagnostics contain only run UUID and closed codes; no exception message, account, URL or payload. */
class StageControlStatus(context: Context, private val runId: String, private val operation: StageControlOperation) {
    private val file = AtomicFile(File(context.filesDir, "stage-control-status.json"))

    init { require(UUID.fromString(runId).toString() == runId) }

    fun write(phase: StageBootstrapPhase, outcome: StageControlOutcome, error: Exception? = null) {
        val body = buildJsonObject {
            put("run_id", runId)
            put("operation", operation.wire)
            put("phase", phase.wire)
            put("outcome", outcome.wire)
            error?.let { put("error_class", errorClass(it)) }
        }.toString()
        val stream = file.startWrite()
        try {
            stream.write(body.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    companion object {
        fun errorClass(error: Exception): String = when (error) {
            is TimeoutCancellationException -> "timeout"
            is StageBootstrapFailure -> error.code.wire
            is AppError.Offline -> offlineClass(error.cause)
            is AppError.RateLimited -> "rate_limited"
            is AppError.Server -> "server"
            is AppError.Unauthorized -> "unauthorized"
            is AppError.UpdateRequired -> "update_required"
            is AppError.Maintenance -> "maintenance"
            is AppError -> "api_error"
            is CancellationException -> "cancelled"
            is IOException -> "io_error"
            is IllegalArgumentException -> "invalid_argument"
            is IllegalStateException -> "invalid_state"
            else -> "other"
        }

        private fun offlineClass(cause: Throwable?): String {
            var current = cause
            // Bound traversal even if an exception has a cyclic cause chain. Never inspect messages or class names.
            repeat(8) {
                when (current) {
                    is UnknownHostException -> return "offline_dns"
                    is SocketTimeoutException -> return "offline_timeout"
                    is SSLException -> return "offline_tls"
                    is ConnectException -> return "offline_connection"
                }
                current = current?.cause
            }
            return "offline"
        }
    }
}
