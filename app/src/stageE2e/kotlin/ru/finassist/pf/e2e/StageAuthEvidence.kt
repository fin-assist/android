package ru.finassist.pf.e2e

import kotlinx.serialization.json.*
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Ephemeral correlation of real authentication responses; no verification/registration secret is persisted. */
class StageAuthEvidence {
    private val verifications = ConcurrentHashMap<String, String>()
    private val registrations = ConcurrentHashMap<String, String>()

    fun started(phone: String, json: String) {
        val token = textField(parse(json), "verification_token")
        require(token.length in 1..2048)
        verifications[token] = phone
    }

    fun registrationPhone(json: String): String {
        val token = textField(parse(json), "registration_token")
        return requireNotNull(registrations[token]) { "Registration has no run-owned verification receipt" }
    }

    fun registeredUserId(json: String): String = textField(parse(json), "user_id")

    private fun parse(json: String): JsonObject = try {
        Json.parseToJsonElement(json).jsonObject
    } catch (_: Exception) {
        throw IllegalArgumentException("Malformed authentication receipt")
    }

    private fun textField(value: JsonObject, field: String): String =
        requireNotNull((value[field] as? JsonPrimitive)?.contentOrNull) { "Missing authentication receipt field" }

    fun observe(body: ResponseBody, verificationToken: String): ResponseBody {
        val phone = requireNotNull(verifications[verificationToken]) { "Unknown verification receipt" }
        return object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            private val observed = object : ForwardingSource(body.source()) {
                private val lines = Buffer()
                private val frame = StringBuilder()

                override fun read(sink: Buffer, byteCount: Long): Long {
                    val before = sink.size
                    val size = super.read(sink, byteCount)
                    if (size > 0) {
                        sink.copyTo(lines, before, size)
                        while (lines.indexOf('\n'.code.toByte()) >= 0) {
                            val line = lines.readUtf8LineStrict().removeSuffix("\r")
                            if (line.isEmpty()) {
                                terminal(phone, frame.toString())
                                frame.clear()
                            } else {
                                if (frame.length + line.length > MAX_FRAME) throw IOException("Authentication event exceeds harness limit")
                                frame.append(line).append('\n')
                            }
                        }
                        if (lines.size > MAX_FRAME) throw IOException("Authentication line exceeds harness limit")
                    }
                    return size
                }
            }.buffer()
            override fun source(): BufferedSource = observed
        }
    }

    private fun terminal(phone: String, frame: String) {
        val lines = frame.lineSequence().toList()
        if (lines.none { it == "event: status" || it == "event:status" }) return
        val data = lines.filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trimStart() }
        val result = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        if ((result["status"] as? JsonPrimitive)?.contentOrNull != "verified" || (result["is_new_user"] as? JsonPrimitive)?.booleanOrNull != true) return
        val token = (result["registration_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.length in 1..2048 } ?: return
        registrations[token] = phone
    }

    private companion object { const val MAX_FRAME = 64 * 1024 }
}
