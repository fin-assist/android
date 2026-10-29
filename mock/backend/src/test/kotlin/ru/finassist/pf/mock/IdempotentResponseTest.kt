package ru.finassist.pf.mock

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.common.error.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class IdempotentResponseTest {
    @Test
    fun `request dependent errors keep response data and reserve the key`() = runBlocking {
        val backend = testBackend()
        for (status in listOf(400, 413, 422)) {
            val details = listOf(AppError.FieldError("file", "INVALID_FORMAT", "invalid file"))
            val original = if (status == 400) AppError.Api(ErrorCodes.VALIDATION_ERROR, status, details)
                else backend.apiError(ErrorCodes.VALIDATION_ERROR, status, details = details)
            var calls = 0
            suspend fun attempt() = backend.idempotentResponse<String>("$status", "body") {
                calls++
                throw original
            }
            val first = assertFailsWith<AppError.Api> { attempt() }
            val replay = assertFailsWith<AppError.Api> { attempt() }
            assertEquals(status, replay.httpStatus)
            assertEquals(original.code, replay.code)
            assertEquals(original.details, replay.details)
            assertEquals(first.response, replay.response)
            assertNotSame(original, replay)
            assertNotSame(first, replay)
            assertEquals(1, calls)
            val conflict = assertFailsWith<AppError.Api> {
                backend.idempotentResponse("$status", "changed") { "new body" }
            }
            assertEquals(ErrorCodes.IDEMPOTENCY_CONFLICT, conflict.code)
            assertEquals("new action", backend.idempotentResponse("new-$status", "changed") { "new action" })
        }
    }

    @Test
    fun `state dependent errors and cancellation do not reserve the key`() = runBlocking {
        val backend = testBackend()
        val errors = listOf(
            AppError.Offline(), AppError.Unauthorized(ErrorCodes.INVALID_TOKEN),
            AppError.RateLimited(null), AppError.InProgress(1), AppError.UpdateRequired(), AppError.Maintenance(null, null),
            AppError.Server(500), CancellationException("cancelled"),
            AppError.Api(ErrorCodes.CLIENT_VERSION_UNSUPPORTED, 400),
        ) + listOf(401, 403, 404, 409, 429, 500, 503).map { AppError.Api("STATE_CHANGED", it) }
        errors.forEachIndexed { index, error ->
            val thrown = assertFailsWith<Throwable> {
                backend.idempotentResponse<String>("$index", "body") { throw error }
            }
            assertSame(error, thrown)
            assertEquals("ok", backend.idempotentResponse("$index", "another body") { "ok" })
        }
    }
}
