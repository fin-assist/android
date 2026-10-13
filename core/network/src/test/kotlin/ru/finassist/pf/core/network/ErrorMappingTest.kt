package ru.finassist.pf.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.junit.Test
import java.io.IOException
import ru.finassist.pf.core.common.error.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ErrorMappingTest {

    @Test
    fun `validation error keeps all details`() {
        val body = """
            {"error":{"code":"VALIDATION_ERROR","message":"bad","details":[
              {"field":"amount_from","code":"OUT_OF_RANGE","message":"must be >= 0"},
              {"field":"date","code":"INVALID_FORMAT","message":"bad"}]}}
        """.trimIndent()
        val error = assertIs<AppError.Api>(mapHttpError(400, body, null))
        assertEquals("VALIDATION_ERROR", error.code)
        assertEquals(listOf("amount_from", "date"), error.details.map { it.field })
    }

    @Test
    fun `rate limited carries retry_at`() {
        val body = """{"error":{"code":"RATE_LIMITED","message":"slow down","retry_at":"2026-10-04T22:27:00+03:00"}}"""
        val error = assertIs<AppError.RateLimited>(mapHttpError(429, body, "600"))
        val retryAt = assertNotNull(error.retryAt)
        assertEquals(27, retryAt.minute)
    }

    @Test
    fun `limit exceeded carries resets_at`() {
        val body = """{"error":{"code":"LIMIT_EXCEEDED","message":"x","resets_at":"2026-10-05T00:00:00+03:00"}}"""
        val error = assertIs<AppError.Api>(mapHttpError(403, body, null))
        assertEquals("LIMIT_EXCEEDED", error.code)
        assertNotNull(error.resetsAt)
    }

    @Test
    fun `invalid token on 401 is unauthorized`() {
        val body = """{"error":{"code":"INVALID_TOKEN","message":"x"}}"""
        assertIs<AppError.Unauthorized>(mapHttpError(401, body, null))
    }

    @Test
    fun `request in progress reads Retry-After header`() {
        val body = """{"error":{"code":"REQUEST_IN_PROGRESS","message":"x"}}"""
        val error = assertIs<AppError.InProgress>(mapHttpError(409, body, "3"))
        assertEquals(3, error.retryAfterSeconds)
    }

    @Test
    fun `non json 5xx is a server error`() {
        assertIs<AppError.Server>(mapHttpError(502, "<html>bad gateway</html>", null))
    }

    @Test
    fun `unknown code on 4xx is a generic api error by status`() {
        val error = assertIs<AppError.Api>(mapHttpError(422, """{"error":{"code":"SOMETHING_NEW","message":"x"}}""", null))
        assertEquals("SOMETHING_NEW", error.code)
        assertEquals(422, error.httpStatus)
    }

    @Test
    fun `token codes outside 401 stay api errors`() {
        val error = assertIs<AppError.Api>(mapHttpError(403, """{"error":{"code":"INVALID_TOKEN","message":"x"}}""", null))
        assertEquals("INVALID_TOKEN", error.code)
        assertEquals(403, error.httpStatus)
    }

    @Test
    fun `revoked token on 401 is unauthorized with its code`() {
        val error = assertIs<AppError.Unauthorized>(mapHttpError(401, """{"error":{"code":"TOKEN_REVOKED","message":"x"}}""", null))
        assertEquals("TOKEN_REVOKED", error.code)
    }

    @Test
    fun `known json error on 5xx is a server error`() {
        val error = assertIs<AppError.Server>(mapHttpError(503, """{"error":{"code":"INTERNAL_ERROR","message":"x"}}""", null))
        assertEquals(503, error.httpStatus)
    }

    @Test
    fun `4xx without our json falls back to the status`() {
        for (body in listOf(null, "", "  ", "<html>not found</html>", """{"unexpected":true}""")) {
            val error = assertIs<AppError.Api>(mapHttpError(404, body, null), "body: $body")
            assertEquals("HTTP_404", error.code)
            assertEquals(404, error.httpStatus)
        }
    }

    @Test
    fun `request in progress without a usable Retry-After has no delay`() {
        val body = """{"error":{"code":"REQUEST_IN_PROGRESS","message":"x"}}"""
        assertNull(assertIs<AppError.InProgress>(mapHttpError(409, body, null)).retryAfterSeconds)
        assertNull(assertIs<AppError.InProgress>(mapHttpError(409, body, "Wed, 21 Oct 2026 07:28:00 GMT")).retryAfterSeconds)
    }

    @Test
    fun `api error keeps message, retry_at and field details`() {
        val body = """{"error":{"code":"IDEMPOTENCY_CONFLICT","message":"Другой запрос","retry_at":"2026-10-04T22:27:00+03:00",
            "details":[{"code":"REQUIRED","message":"m"}]}}"""
        val error = assertIs<AppError.Api>(mapHttpError(422, body, null))
        assertEquals("Другой запрос", error.message)
        assertEquals(27, error.retryAt?.minute)
        assertEquals(listOf(AppError.FieldError(null, "REQUIRED", "m")), error.details)
    }

    @Test
    fun `transport throwables map by kind`() {
        val typed = AppError.Unauthorized("INVALID_TOKEN")
        assertSame(typed, typed.toNetworkError())
        val io = IOException("reset")
        assertSame(io, assertIs<AppError.Offline>(io.toNetworkError()).cause)
        assertEquals(200, assertIs<AppError.Server>(SerializationException("bad").toNetworkError()).httpStatus)
        assertIs<AppError.Unknown>(IllegalStateException("bug").toNetworkError())
        assertFailsWith<CancellationException> { CancellationException("cancelled").toNetworkError() }
    }
}
