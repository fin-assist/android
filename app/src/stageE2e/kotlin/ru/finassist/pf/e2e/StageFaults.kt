package ru.finassist.pf.e2e

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import ru.finassist.pf.BuildConfig
import ru.finassist.pf.core.common.availability.ClientVersion
import java.util.UUID
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.AskResponse

/** Explicit client fault injection around the real HTTPS client; never a replacement backend. */
@Singleton
class StageFaults @Inject constructor(
    @ApplicationContext context: Context,
    private val runs: StageRunStore,
) : Interceptor {
    private val settingsFile = File(context.filesDir, "stage-faults.json")
    private val evidenceFile = File(context.filesDir, "stage-evidence.json")
    private val authEvidence = StageAuthEvidence()
    private val registrations = mutableListOf<JsonObject>()
    private val deletedPhones = mutableSetOf<String>()
    // Ephemeral actual-response Authorization -> synthetic phone. Never persisted or rendered.
    private val verifiedAuth = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val settings = mutableMapOf<String, String>()
    private val counts = mutableMapOf<String, Int>()
    private val rateLimited = MutableStateFlow(0)
    val streamRateLimited: StateFlow<Int> = rateLimited
    @Volatile private var afterDrop = false

    init {
        if (settingsFile.exists()) {
            require(settingsFile.length() <= 4096) { "Invalid fault state" }
            Json.decodeFromString<Map<String, String>>(settingsFile.readText()).forEach { (name, value) ->
                StageFaultPlan.validate(name, value)
                settings[name] = value
            }
        }
        if (evidenceFile.exists()) {
            require(evidenceFile.length() <= 4096) { "Invalid evidence" }
            val saved = Json.parseToJsonElement(evidenceFile.readText()).jsonObject
            require(saved["run_id"]?.jsonPrimitive?.content == runs.read().run_id) { "Evidence belongs to another run" }
            saved["counts"]?.jsonObject?.forEach { (name, count) -> counts[name] = count.jsonPrimitive.int }
            rateLimited.value = count("stream_429")
            afterDrop = count("stream_drop") > 0
            saved["successful_registrations"]?.jsonArray?.forEach { item ->
                val registration = item.jsonObject
                val run = runs.read()
                val phone = registration.getValue("phone").jsonPrimitive.content
                require(phone == run.phone || phone == run.new_phone)
                val id = registration.getValue("user_id").jsonPrimitive.content
                require(UUID.fromString(id).toString() == id)
                registrations += registration
            }
            saved["deleted_phones"]?.jsonArray?.forEach { item ->
                val phone = item.jsonPrimitive.content
                val run = runs.read()
                require(phone == run.phone || phone == run.new_phone)
                deletedPhones += phone
            }
        }
    }

    @Synchronized
    fun configure(values: Map<String, String>) {
        values.forEach { (name, value) -> StageFaultPlan.validate(name, value) }
        settings.putAll(values)
        saveSettings()
    }

    @Synchronized
    fun count(name: String): Int = counts[name] ?: 0

    /** Called only after the corresponding local control has been applied successfully. */
    fun flagOverrideApplied() = record("flag_override")

    fun flagsResetApplied() = record("flags_reset")

    fun pendingUploadSeeded() = record("pending_upload_seed")

    @Synchronized
    fun canCleanup(phone: String, userId: String): Boolean {
        val run = runs.read()
        return (phone == run.phone || phone == run.new_phone) && registrations.any {
            it["phone"]?.jsonPrimitive?.content == phone && it["user_id"]?.jsonPrimitive?.content == userId
        }
    }

    @Synchronized
    fun clearForCleanup() {
        settings.clear()
        afterDrop = false
        saveSettings()
    }

    @Synchronized
    private fun value(name: String, default: String = ""): String = settings[name] ?: default

    @Synchronized
    private fun record(name: String) {
        counts[name] = count(name) + 1
        if (name == "stream_429") rateLimited.value = count(name)
        saveEvidence()
    }

    @Synchronized
    private fun saveEvidence() {
        val evidence = buildJsonObject {
            put("run_id", runs.read().run_id)
            put("has_client_injections", counts.any { (name, count) -> name !in setOf("real_requests", "real_ask_requests", "real_ask_accepted", "real_ask_consent_required") && count > 0 })
            put("counts", Json.encodeToJsonElement(counts.toMap()))
            put("successful_registrations", JsonArray(registrations.toList()))
            put("deleted_phones", JsonArray(deletedPhones.sorted().map(::JsonPrimitive)))
        }
        // Only counters, run UUID and synthetic registration receipts; never tokens or financial payloads.
        writePrivate(evidenceFile, evidence.toString())
    }

    @Synchronized
    private fun consume(name: String): Boolean {
        val remaining = value(name, "0").toInt()
        if (remaining <= 0) return false
        settings[name] = (remaining - 1).toString()
        saveSettings()
        return true
    }

    private fun saveSettings() {
        writePrivate(settingsFile, Json.encodeToString(settings.toMap()))
    }

    private fun writePrivate(file: File, text: String) {
        val atomic = android.util.AtomicFile(file)
        val output = atomic.startWrite()
        try {
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response = try {
        checkedIntercept(chain)
    } catch (failure: IOException) {
        throw failure
    } catch (_: Exception) {
        // OkHttp's asynchronous dispatcher does not deliver unchecked interceptor exceptions as onFailure.
        // Scope/configuration rejection must fail the request without crashing the client process or echoing data.
        throw IOException("Stage harness rejected an invalid request or run receipt")
    }

    private fun checkedIntercept(chain: Interceptor.Chain): Response {
        val run = runs.read()
        var request = chain.request()
        check(request.url.scheme == "https" && request.url.host == "stage.plainfin.ru" && request.url.port == 443) {
            "Stage harness only permits the fixed stage origin"
        }
        var submittedPhone: String? = null
        var registeringPhone: String? = null
        if (request.method == "POST" && request.url.encodedPath in setOf("/v1/auth/phone", "/v1/auth/register")) {
            val body = checkNotNull(request.body)
            require(body.contentLength() in 1..2048) { "Invalid synthetic authentication request" }
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            val bodyText = buffer.readUtf8()
            if (request.url.encodedPath == "/v1/auth/phone") {
                val phone = Json.parseToJsonElement(bodyText).jsonObject["phone"]?.jsonPrimitive?.content
                require(phone != null && (phone == run.phone || phone == run.new_phone)) {
                    "Phone does not belong to this run receipt"
                }
                submittedPhone = phone
            } else registeringPhone = authEvidence.registrationPhone(bodyText)
        }
        val stream = request.url.encodedPath.endsWith("/events") || request.url.encodedPath.matches(Regex("/v1/statements/[^/]+/progress"))
        if (value("offline") == "true") {
            record("offline")
            throw IOException("stage UI injected disconnect")
        }
        val delay = value("network_delay_ms", "0").toLong()
        if (delay > 0) {
            record("latency")
            Thread.sleep(delay)
        }
        if (ClientVersion(BuildConfig.CLIENT_VERSION_NAME, BuildConfig.VERSION_CODE).isBelow(value("min_version"))) {
            record("update_required")
            return error(request, 400, "CLIENT_VERSION_UNSUPPORTED", "Обновите приложение, чтобы продолжить")
        }
        val maintenance = value("maintenance", "off")
        if (maintenance != "off") {
            record("maintenance")
            val until = maintenance.toLongOrNull()?.let { ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(it).toOffsetDateTime().toString() }
            return error(request, 503, "MAINTENANCE", "Сервис временно недоступен. Попробуйте позже", value("maintenance_retry_after", "15"), until)
        }
        if (StageFaultPlan.operation(request.method, request.url.encodedPath) == value("error_operation", "off")) {
            record("error_requests")
            val (status, body) = StageFaultPlan.error(value("error_scenario", "unknown_code"))
            return response(request, status, body, if (status == 429) "3" else null)
        }
        if (stream && afterDrop && consume("stream_rate_limits")) {
            record("stream_429")
            return error(request, 429, "RATE_LIMITED", "Слишком много запросов. Попробуйте позже", value("stream_retry_s", "3"), retryable = true)
        }
        if (stream && request.url.encodedPath == "/v1/auth/phone/events" && value("stream_drops", "0").toInt() == 0) {
            val callDelay = value("call_delay_ms", "0").toLong()
            if (callDelay > 0) {
                record("call_event_latency")
                Thread.sleep(callDelay)
            }
        }
        StageResponseTransforms.expenseRequest(request, value("unknown_codes") == "true")?.let { translated ->
            // The UI sent the unknown code intact. Only this closed peer fixture maps it for the current server.
            record("unknown_kind_requests")
            request = translated
        }
        record("real_requests")
        if (request.method == "POST" && request.url.encodedPath == "/v1/assistant/messages") {
            // Count attempts before the transport returns: a refused automatic resend is still a resend.
            record("real_ask_requests")
        }
        var result = chain.proceed(request)
        if (result.code == 403 && request.method == "POST" && request.url.encodedPath == "/v1/assistant/messages") {
            val code = runCatching {
                Json.parseToJsonElement(result.peekBody(65_536).string()).jsonObject["error"]
                    ?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (code == "CONSENT_REQUIRED") record("real_ask_consent_required")
        }
        if (result.isSuccessful && request.method == "GET" && request.url.encodedPath == "/v1/profile") {
            val profile = Json.parseToJsonElement(result.peekBody(65_536).string()).jsonObject
            val phone = (profile["phone"] as? JsonPrimitive)?.contentOrNull
            val authorization = result.request.header("Authorization")
            if (phone != null && (phone == run.phone || phone == run.new_phone) && authorization != null) {
                verifiedAuth[authorization] = phone
            }
        }
        if (result.isSuccessful && request.method == "POST" && request.url.encodedPath == "/v1/assistant/messages") {
            ApiJson.decodeFromString(AskResponse.serializer(), result.peekBody(65_536).string())
            record("real_ask_accepted")
            val holdMs = value("hold_ask_response_ms", "0").toLong()
            if (holdMs > 0) {
                val phone = result.request.header("Authorization")?.let(verifiedAuth::get)
                if (phone != run.phone) {
                    result.close()
                    throw IOException("Stage UI Ask hold requires the primary run-owned authenticated profile")
                }
                // These durable counters precede delivery, so the host can kill the real background process here.
                record("ask_response_held")
                try {
                    Thread.sleep(holdMs)
                } catch (_: InterruptedException) {
                    result.close()
                    Thread.currentThread().interrupt()
                    throw IOException("Stage UI Ask response hold interrupted")
                }
                record("ask_response_released")
            }
        }
        if (request.method == "DELETE" && request.url.encodedPath == "/v1/profile" && result.code in setOf(200, 204)) {
            val phone = result.request.header("Authorization")?.let(verifiedAuth::get)
            if (phone != null && (phone == run.phone || phone == run.new_phone)) synchronized(this) {
                deletedPhones += phone
                saveEvidence()
            }
        }
        if (result.isSuccessful && submittedPhone != null) {
            authEvidence.started(submittedPhone, result.peekBody(16_384).string())
        }
        if (result.isSuccessful && registeringPhone != null) {
            val id = authEvidence.registeredUserId(result.peekBody(16_384).string())
            require(UUID.fromString(id).toString() == id)
            synchronized(this) {
                val receipt = buildJsonObject { put("phone", registeringPhone); put("user_id", id) }
                if (receipt !in registrations) registrations += receipt
                saveEvidence()
            }
        }
        if (stream && result.isSuccessful && consume("stream_drops")) {
            result.close()
            afterDrop = true
            record("stream_drop")
            return result.newBuilder().body("".toResponseBody("text/event-stream".toMediaType())).build()
        }
        if (result.isSuccessful && request.url.encodedPath == "/v1/auth/phone/events") {
            result = result.newBuilder().body(authEvidence.observe(
                checkNotNull(result.body), checkNotNull(request.header("X-Verification-Token")),
                setOfNotNull(run.phone.takeIf { run.new_account }, run.new_phone),
            )).build()
        }
        if (result.isSuccessful && value("unknown_codes") == "true" &&
            request.method == "GET" && request.url.encodedPath.matches(Regex("/v1/statements/[^/]+/progress"))) {
            result = result.newBuilder().body(StageImportUnknownCodesBody(checkNotNull(result.body)) {
                record("import_response_transform")
            }).build()
        }
        if (result.isSuccessful && request.url.encodedPath == "/v1/analytics" &&
            (value("unknown_codes") == "true" || value("failing_blocks").isNotEmpty())) {
            val raw = result.body ?: return result
            val root = Json.parseToJsonElement(raw.string()).jsonObject
            val blocks = root["blocks"]?.jsonArray ?: return result.newBuilder().body(root.toString().toResponseBody(JSON)).build()
            val changed = blocks.map { block ->
                val obj = block.jsonObject
                if (obj["id"]?.jsonPrimitive?.content == value("failing_blocks")) {
                    JsonObject(obj.filterKeys { it !in setOf("data", "action") } + mapOf(
                        "status" to JsonPrimitive("unavailable"),
                        "alt_text" to JsonPrimitive("Не получилось посчитать — обновите экран позже"),
                        "empty_text" to JsonPrimitive("Не получилось посчитать — обновите экран позже"),
                    ))
                } else if (value("unknown_codes") == "true") StageResponseTransforms.expenseTile(obj) else block
            }
            val augmented = if (value("unknown_codes") == "true") StageResponseTransforms.unknownAnalyticsBlocks(changed) else JsonArray(changed)
            record("analytics_response_transform")
            return result.newBuilder().body(JsonObject(root + ("blocks" to augmented)).toString().toResponseBody(JSON)).build()
        }
        return result
    }

    private fun error(
        request: okhttp3.Request, status: Int, code: String, message: String,
        retry: String? = null, until: String? = null, retryable: Boolean = false,
    ): Response =
        response(request, status, buildJsonObject { put("error", buildJsonObject {
            put("code", code); put("message", "Stage client fault fixture"); put("user_message", message); put("severity", "warning"); put("retryable", retryable)
            until?.let { put("until", it) }
        }) }.toString(), retry)

    private fun response(request: okhttp3.Request, status: Int, body: String, retry: String?): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Stage client fault injection")
            .header("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(java.time.ZoneOffset.UTC)))
            .apply { retry?.let { header("Retry-After", it) } }
            .body(body.toResponseBody(JSON)).build()

    private companion object { val JSON = "application/json".toMediaType() }
}

@Module
@InstallIn(SingletonComponent::class)
object StageFaultModule {
    @Provides @IntoSet @Named("clientInterceptors")
    fun interceptor(faults: StageFaults): Interceptor = faults
}
