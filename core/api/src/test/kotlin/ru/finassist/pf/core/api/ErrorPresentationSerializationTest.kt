package ru.finassist.pf.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import java.time.OffsetDateTime
import ru.finassist.pf.core.api.model.*
import ru.finassist.pf.core.common.error.*
import ru.finassist.pf.core.common.time.ElapsedClock
import kotlin.test.*

class ErrorPresentationSerializationTest {
    @Test fun `malformed presentation never discards valid code or retry metadata`() {
        for (value in listOf("null", "42", "{}", "[]", "\" \"")) {
            val error = ApiJson.decodeFromString(ApiError.serializer(), """{"code":"RATE_LIMITED","message":"PRIVATE SQL","user_message":$value,"severity":{},"retryable":true,"retry_at":"2026-10-25T12:00:00Z"}""")
            assertEquals(UserMessage.Invalid, error.userMessage)
            assertEquals(ErrorSeverity.ERROR, error.severity)
            assertEquals("RATE_LIMITED", error.code)
            assertEquals(true, error.retryable)
            assertNotNull(error.retryAt)
            assertEquals("safe fallback", error.presentation.textOrFallback("safe fallback", "unsafe legacy"))
        }
        val absent = ApiJson.decodeFromString(ApiError.serializer(), """{"code":"NEW","message":"private"}""")
        assertEquals(UserMessage.Absent, absent.userMessage)
    }
    @Test fun `unknown severity and malformed optional boolean keep valid user text`() {
        for (value in listOf("null", "\"true\"", "1", "[]", "{}")) {
            val error = ApiJson.decodeFromString(ApiError.serializer(), """{"code":"NEW","message":"PRIVATE","user_message":"Безопасно","severity":"future","retryable":$value}""")
            assertNull(error.retryable)
            assertEquals("Безопасно", error.presentation.textOrFallback("fallback"))
            assertEquals(ErrorSeverity.ERROR, error.severity)
        }
    }
    @Test fun `terminal and history retain unknown raw codes independent result retry`() {
        val terminal = ApiJson.decodeFromString(AnswerErrorEvent.serializer(), """{"code":"MAINTENANCE","message":"private","remaining_limit":5,"retryable":false,"user_message":null,"text":"legacy"}""")
        assertEquals("MAINTENANCE", terminal.rawCode)
        assertEquals(AnswerErrorCode.UNKNOWN, terminal.code)
        assertEquals("fallback", terminal.presentation.textOrFallback("fallback", terminal.text))
        assertEquals(false, terminal.retryable)
        val history = ApiJson.decodeFromString(Message.serializer(), """{"id":"m","role":"assistant","status":"failed","error_code":"NEW_REASON","error_user_message":"Безопасно","retryable":true,"created_at":"2026-10-25T12:00:00Z"}""")
        assertEquals("NEW_REASON", history.errorRawCode)
        assertEquals(true, history.retryable)
        val encoded = ApiJson.encodeToString(Message.serializer(), history)
        assertTrue(encoded.contains("NEW_REASON"))
        assertEquals("Безопасно", history.errorPresentation.textOrFallback("fallback"))
    }
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun `producer defaults omit new optional fields while preserving old nullable fields`() {
        val wire = Json(ApiJson) { encodeDefaults = true; explicitNulls = true }
        val message = Message(id = "u", role = MessageRole.USER, createdAt = OffsetDateTime.parse("2026-10-25T12:00:00Z"))
        val json = wire.parseToJsonElement(wire.encodeToString(Message.serializer(), message)).jsonObject
        assertFalse("error_user_message" in json)
        assertFalse("error_severity" in json)
        assertEquals(JsonNull, json["error_text"])
        val absent = wire.parseToJsonElement(wire.encodeToString(ApiError.serializer(), ApiError("NEW", "private"))).jsonObject
        assertFalse("user_message" in absent)
        assertFalse("severity" in absent)
        assertFalse("retryable" in absent)
        val invalid = wire.parseToJsonElement(wire.encodeToString(ApiError.serializer(), ApiError("NEW", "private", userMessage = UserMessage.Invalid))).jsonObject
        assertEquals(JsonNull, invalid["user_message"])
    }
    @Test fun `restored key keeps same identity but never acquires a fresh replay window`() {
        val original = IdempotencyKey.random(ElapsedClock { 1000 })
        val restored = ApiJson.decodeFromString(IdempotencyKey.serializer(), ApiJson.encodeToString(IdempotencyKey.serializer(), original))
        assertEquals(original, restored)
        assertEquals(original.hashCode(), restored.hashCode())
        assertNull(restored.replayWindow)
        assertTrue(original.replayWindow!!.isOpen(ElapsedClock { 2000 }))
    }
}
