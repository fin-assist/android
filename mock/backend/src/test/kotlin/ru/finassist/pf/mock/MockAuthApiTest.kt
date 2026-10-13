package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.FieldErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.SystemClock
import ru.finassist.pf.mock.api.MockAuthApi
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MockAuthApiTest {
    private val auth = MockAuthApi(MockBackend(MockConfig(networkDelayMs = 0), SystemClock()).also { it.ready.complete(Unit) })

    @Test
    fun `a number outside +7 and 10 digits is a VALIDATION_ERROR at phone (FIN-68)`() = runBlocking {
        for (phone in listOf("+7916123456", "+380501234567", "89161234567")) {
            val e = assertFailsWith<AppError.Api> { auth.startPhoneVerification(IdempotencyKey("k-$phone"), phone) }
            assertEquals(ErrorCodes.VALIDATION_ERROR, e.code)
            assertEquals(400, e.httpStatus)
            assertEquals(listOf("phone" to FieldErrorCodes.INVALID_FORMAT), e.details.map { it.field to it.code })
        }
    }

    @Test
    fun `a valid number starts the verification`() = runBlocking {
        val v = auth.startPhoneVerification(IdempotencyKey("k1"), "+79161234567")
        assertTrue(v.verificationToken.startsWith("vt_"))
    }
}
