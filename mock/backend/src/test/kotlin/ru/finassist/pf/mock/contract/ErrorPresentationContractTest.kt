package ru.finassist.pf.mock.contract

import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import ru.finassist.pf.mock.MockErrorCatalog
import ru.finassist.pf.mock.apiError
import ru.finassist.pf.mock.testBackend
import kotlin.test.*

class ErrorPresentationContractTest {
    @Test fun `actual fake REST failure writes canonical safe text permission and Date into pinned schema`() {
        val backend = testBackend()
        try {
            val error = backend.apiError(ErrorCodes.VALIDATION_ERROR, 400, message = "secret diagnostic")
            val exchange = Wire.error(Contract.operation("askAssistant"), Contract.operation("askAssistant").path, error)
            ContractCheck.assertContract("canonical REST error", exchange)
            val wire = Json.parseToJsonElement(assertNotNull(exchange.body)).jsonObject.getValue("error").jsonObject
            assertEquals(assertIs<UserMessage.Safe>(error.presentation.userMessage).text, wire.getValue("user_message").jsonPrimitive.content)
            assertEquals("false", wire.getValue("retryable").jsonPrimitive.content)
            assertNotNull(exchange.headers["Date"])
            assertFalse(wire.getValue("user_message").toString().contains("secret"))
        } finally { backend.scope.cancel() }
    }

    @Test fun `import cancellation event has catalog warning without request permission`() {
        val presentation = MockErrorCatalog.presentation(ErrorCodes.CANCELLED)
        val (_, data) = Wire.errorEvent(ErrorCodes.CANCELLED, "cancelled by user", presentation)
        val wire = Json.parseToJsonElement(data).jsonObject
        assertEquals("warning", wire.getValue("severity").jsonPrimitive.content)
        assertEquals(ErrorSeverity.WARNING, presentation.severity)
        assertEquals("Загрузка отменена", wire.getValue("user_message").jsonPrimitive.content)
        assertFalse(wire.containsKey("retryable"))
        assertEquals(emptyList(), Contract.validateEvent(Contract.operation("streamStatementProgress"), "error", data))
    }
}
