package ru.finassist.pf.core.common.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionFailureTest {
    @Test fun `only a detected VPN suggests switching it off`() {
        assertEquals("Попробуйте отключить VPN и повторить подключение", ConnectionFailureTexts.forEnvironment(ConnectionEnvironment.VPN).hint)
        for (environment in listOf(ConnectionEnvironment.OTHER_NETWORK, ConnectionEnvironment.NO_NETWORK, ConnectionEnvironment.UNKNOWN)) {
            val texts = ConnectionFailureTexts.forEnvironment(environment)
            assertEquals("Не удаётся подключиться к серверу", texts.title)
            assertEquals("Проверьте интернет и попробуйте ещё раз", texts.hint)
            assertFalse(texts.message.contains("VPN"))
        }
    }

    @Test fun `local transport hint does not change request restrictions`() {
        val error = AppError.Offline()
        assertTrue(error.presentation.connectionFailure)
        assertEquals(null, error.requestRetryable)
        assertFalse(AppError.Server(503).presentation.connectionFailure)
        assertFalse(AppError.Api("NETWORK_ERROR", 400).presentation.connectionFailure)
        assertFalse(ErrorPresentation(UserMessage.from("VPN")).connectionFailure)
    }
}
