package ru.finassist.pf.core.network

import org.junit.Test
import ru.finassist.pf.core.common.error.AppError
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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
        assertNotNull(error.retryAt)
        assertEquals(27, error.retryAt.minute)
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
}
