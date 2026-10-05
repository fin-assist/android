package ru.finassist.pf.core.mockbackend.engine

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.finassist.pf.core.network.client.PfJson
import ru.finassist.pf.core.network.dto.*
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Protocol-agnostic core of the mock backend: users, sessions, per-user ledgers, uploads in progress,
 * assistant answers, idempotency store. The OkHttp interceptor is a thin HTTP adapter over this class.
 * Everything is guarded by [lock]; long-running work (import, answer generation) runs on threads and
 * re-takes the lock when it touches state.
 */
class MockServer(val config: Config, val clock: () -> OffsetDateTime = { OffsetDateTime.now() }) {

    data class Config(
        /** Numbers that "exist" on the server; others are new users (registration flow). */
        val existingPhones: Set<String>,
        /** Seconds until the simulated callback "arrives". */
        val callDelaySeconds: Long = 5,
        val accessTokenTtlSeconds: Long = 15 * 60,
        val verificationTtlSeconds: Long = 180,
        val dailyAssistantLimit: Int = 5,
        /** Preloaded statement for [existingPhones] (file name + bytes), or null to start empty. */
        val preloaded: Pair<String, ByteArray>? = null,
        val pdConsentVersion: String = "2026-09-28",
        val assistantConsentVersion: String = "2026-09-28",
    )

    val lock = Any()
    val json: Json = PfJson

    // ---- users & sessions ------------------------------------------------------------------------------------

    class User(val id: String, val phone: String, val createdAt: OffsetDateTime) {
        var theme: ThemeDto = ThemeDto.system
        var timezone: ZoneId = ZoneId.of("Europe/Moscow")
        var assistantConsentVersion: String? = null
        val ledger = Ledger(timezone)
        var questionsToday = 0
        var questionsDay: java.time.LocalDate? = null
        val messages = mutableListOf<MessageDto>()
        val answers = ConcurrentHashMap<String, AnswerProcess>()
    }

    class Verification(val token: String, val phone: String, val createdAt: OffsetDateTime, val expiresAt: OffsetDateTime) {
        var verified = false
        var consumed = false
        var registrationToken: String? = null
        var tokens: TokenPairDto? = null
    }

    class Session(val userId: String, var refreshToken: String, val chain: String) {
        val usedRefreshTokens = mutableSetOf<String>()
    }

    private val users = linkedMapOf<String, User>()               // by id
    private val usersByPhone = hashMapOf<String, User>()
    private val verifications = hashMapOf<String, Verification>() // by token
    private val registrationTokens = hashMapOf<String, Verification>()
    private val sessions = hashMapOf<String, Session>()           // by refresh token
    private val accessTokens = hashMapOf<String, Pair<String, OffsetDateTime>>() // token -> (userId, expiresAt)
    private val sessionsByChain = hashMapOf<String, Session>()

    init {
        config.existingPhones.forEach { phone ->
            val u = createUser(phone, clock())
            config.preloaded?.let { (name, bytes) ->
                when (val r = OfxParser.parse(bytes)) {
                    is OfxParser.Result.Ok -> u.ledger.import(r.statement, name, clock().minusDays(1), Ids.next())
                    else -> Unit
                }
            }
        }
    }

    private fun createUser(phone: String, now: OffsetDateTime): User {
        val u = User(Ids.next(), phone, now)
        users[u.id] = u; usersByPhone[phone] = u
        return u
    }

    fun wire(user: User) = Wire(user.timezone)

    // ---- auth ----------------------------------------------------------------------------------------------------

    fun startVerification(phone: String): Result<PhoneVerificationDto> {
        if (!Regex("""^\+7\d{10}$""").matches(phone)) return Result.failure(ApiFailure(400, "INVALID_PHONE", "phone must be +7 and 10 digits"))
        val now = clock()
        val v = Verification(Ids.secret("vt_"), phone, now, now.plusSeconds(config.verificationTtlSeconds))
        verifications[v.token] = v
        return Result.success(PhoneVerificationDto(v.token, "+78001234567", Wire(ZoneId.of("Europe/Moscow")).time(v.expiresAt)))
    }

    /** Current status for the SSE stream; the "call" arrives [Config.callDelaySeconds] after start. */
    fun verificationStatus(token: String): Result<PhoneVerificationStatusEventDto> {
        val v = verifications[token] ?: return Result.failure(ApiFailure(401, "INVALID_TOKEN", "unknown verification"))
        val now = clock()
        if (v.consumed || now.isAfter(v.expiresAt)) return if (v.consumed) Result.failure(ApiFailure(401, "INVALID_TOKEN", "consumed")) else Result.success(PhoneVerificationStatusEventDto("expired"))
        if (!v.verified && ChronoUnit.SECONDS.between(v.createdAt, now) >= config.callDelaySeconds) {
            v.verified = true
            val user = usersByPhone[v.phone]
            if (user != null) v.tokens = issueTokens(user, newChain = true) else v.registrationToken = Ids.secret("reg_").also { registrationTokens[it] = v }
        }
        if (!v.verified) return Result.success(PhoneVerificationStatusEventDto("pending"))
        return Result.success(
            if (v.tokens != null) PhoneVerificationStatusEventDto("verified", isNewUser = false, accessToken = v.tokens!!.accessToken, refreshToken = v.tokens!!.refreshToken)
            else PhoneVerificationStatusEventDto("verified", isNewUser = true, registrationToken = v.registrationToken),
        )
    }

    fun register(req: RegisterRequestDto): Result<RegisterResponseDto> {
        val v = registrationTokens[req.registrationToken] ?: return Result.failure(ApiFailure(401, "INVALID_TOKEN", "unknown registration token"))
        val now = clock()
        if (now.isAfter(v.expiresAt)) return Result.failure(ApiFailure(401, "TOKEN_EXPIRED", "registration token expired"))
        if (!req.pdConsentAccepted) return Result.failure(ApiFailure(403, "CONSENT_REQUIRED", "consent not accepted"))
        if (req.pdConsentVersion != config.pdConsentVersion) return Result.failure(ApiFailure(422, "CONSENT_OUTDATED", "consent version outdated"))
        val user = usersByPhone[v.phone] ?: createUser(v.phone, now)
        req.timezone?.let { tz -> runCatching { ZoneId.of(tz) }.getOrNull()?.let { user.timezone = it } }
        v.consumed = true
        registrationTokens.remove(req.registrationToken)
        val pair = issueTokens(user, newChain = true)
        return Result.success(RegisterResponseDto(pair.accessToken, pair.refreshToken, user.id))
    }

    private fun issueTokens(user: User, newChain: Boolean, chain: String? = null): TokenPairDto {
        val access = Ids.secret("at_")
        val refresh = Ids.secret("rt_")
        accessTokens[access] = user.id to clock().plusSeconds(config.accessTokenTtlSeconds)
        val c = if (newChain || chain == null) Ids.next() else chain
        val session = sessionsByChain.getOrPut(c) { Session(user.id, refresh, c) }
        session.refreshToken = refresh
        sessions[refresh] = session
        return TokenPairDto(access, refresh)
    }

    fun refresh(refreshToken: String): Result<TokenPairDto> {
        val session = sessions[refreshToken] ?: return Result.failure(ApiFailure(401, "INVALID_TOKEN", "unknown refresh token"))
        if (refreshToken in session.usedRefreshTokens) {
            // Reuse → the whole chain is revoked.
            revokeChain(session)
            return Result.failure(ApiFailure(401, "TOKEN_REVOKED", "refresh token reused"))
        }
        session.usedRefreshTokens += refreshToken
        // Also mark the verification consumed once the first refresh with its token happens.
        verifications.values.firstOrNull { it.tokens?.refreshToken == refreshToken }?.consumed = true
        val user = users[session.userId] ?: return Result.failure(ApiFailure(401, "INVALID_TOKEN", "user gone"))
        return Result.success(issueTokens(user, newChain = false, chain = session.chain))
    }

    private fun revokeChain(session: Session) {
        sessions.entries.removeIf { it.value === session }
        sessionsByChain.remove(session.chain)
    }

    fun logout(refreshToken: String) {
        sessions[refreshToken]?.let(::revokeChain)
    }

    /** Returns the user for a bearer token, or a failure with the right 401 code. */
    fun authenticate(bearer: String?): Result<User> {
        val token = bearer?.removePrefix("Bearer ")?.trim() ?: return Result.failure(ApiFailure(401, "INVALID_TOKEN", "no token"))
        val (userId, exp) = accessTokens[token] ?: return Result.failure(ApiFailure(401, "INVALID_TOKEN", "unknown token"))
        if (clock().isAfter(exp)) return Result.failure(ApiFailure(401, "INVALID_TOKEN", "expired"))
        return users[userId]?.let { Result.success(it) } ?: Result.failure(ApiFailure(401, "INVALID_TOKEN", "user gone"))
    }

    fun consentDocument(type: String): ConsentDocumentDto? = when (type) {
        "personal_data" -> ConsentDocumentDto(type, config.pdConsentVersion, "Согласие на обработку персональных данных", "https://example.ru/legal/pd-consent/${config.pdConsentVersion}")
        "assistant" -> ConsentDocumentDto(type, config.assistantConsentVersion, "Согласие на передачу данных помощнику", "https://example.ru/legal/assistant-consent/${config.assistantConsentVersion}")
        else -> null
    }

    // ---- profile -------------------------------------------------------------------------------------------------

    fun profile(u: User): ProfileDto = ProfileDto(
        u.id, u.phone, u.theme, u.timezone.id,
        AssistantConsentDto(u.assistantConsentVersion == config.assistantConsentVersion, u.assistantConsentVersion, config.assistantConsentVersion),
        wire(u).time(u.createdAt),
    )

    fun deleteAccount(u: User) {
        users.remove(u.id); usersByPhone.remove(u.phone)
        sessions.values.filter { it.userId == u.id }.distinct().forEach(::revokeChain)
        accessTokens.entries.removeIf { it.value.first == u.id }
    }

    // ---- uploads -------------------------------------------------------------------------------------------------

    class UploadProcess(val id: String, val fileName: String) {
        /** Progress events emitted so far; the terminal one is `complete` or `error`. */
        val events = CopyOnWriteArrayList<Pair<String, String>>()
        @Volatile var done = false
        @Volatile var cancelled = false
        @Volatile var outcome: Ledger.ImportOutcome? = null
    }

    val uploads = ConcurrentHashMap<String, UploadProcess>()

    /** Synchronous checks (size, format, bank) → 202 with an upload id, then async parsing with progress. */
    fun startUpload(u: User, fileName: String, bytes: ByteArray): Result<UploadAcceptedDto> {
        if (bytes.size > 10L * 1024 * 1024) return Result.failure(ApiFailure(413, "FILE_TOO_LARGE", "file exceeds 10 MB"))
        val parsed = when (val r = OfxParser.parse(bytes)) {
            is OfxParser.Result.Ok -> r.statement
            OfxParser.Result.CsvNotAccepted -> return Result.failure(ApiFailure(422, "CSV_NOT_ACCEPTED", "CSV instead of OFX"))
            OfxParser.Result.WrongFormat -> return Result.failure(ApiFailure(422, "WRONG_FORMAT", "not an OFX file"))
            is OfxParser.Result.WrongBank -> return Result.failure(ApiFailure(422, "WRONG_BANK", "OFX is not from T-Bank"))
        }
        val id = Ids.next()
        val process = UploadProcess(id, fileName)
        uploads[id] = process
        thread(name = "mock-import-$id") {
            val stages = listOf("parsing" to 20, "parsing" to 45, "dedup" to 65, "rules" to 85)
            for ((stage, pct) in stages) {
                Thread.sleep(400)
                if (process.cancelled) { process.events += "error" to json.encodeToString(StreamErrorDto("CANCELLED", "cancelled by user")); process.done = true; return@thread }
                process.events += "progress" to json.encodeToString(ImportProgressEventDto(stage, pct))
            }
            synchronized(lock) {
                val before = Coverage(u.ledger.uploads, u.timezone, wire(u).localDate(clock()))
                val outcome = u.ledger.import(parsed, fileName, clock(), id)
                process.outcome = outcome
                val result = StatementsService(u.ledger, u.timezone, wire(u)).importResult(outcome, before, clock())
                process.events += "complete" to json.encodeToString(result)
                process.done = true
            }
        }
        return Result.success(UploadAcceptedDto(id, fileName))
    }

    fun deleteUpload(u: User, uploadId: String): Boolean {
        uploads[uploadId]?.let { p -> if (!p.done) { p.cancelled = true; return true } }
        val existed = u.ledger.uploads.any { it.id == uploadId } || uploads.containsKey(uploadId)
        u.ledger.deleteUpload(uploadId, clock())
        return existed
    }

    // ---- assistant -----------------------------------------------------------------------------------------------

    class AnswerProcess(val id: String, val questionId: String) {
        val events = CopyOnWriteArrayList<Pair<String, String>>()
        @Volatile var status = "generating"
        @Volatile var blocks: List<BlockDto> = emptyList()
        @Volatile var chips: List<ChipDto> = emptyList()
        @Volatile var source: AnswerSourceDto? = null
        @Volatile var errorCode: String? = null
        @Volatile var charged: Boolean? = null
        @Volatile var replaced = false
    }

    private fun resetDailyLimit(u: User) {
        val today = clock().atZoneSameInstant(ZoneOffset.ofHours(3)).toLocalDate()
        if (u.questionsDay != today) { u.questionsDay = today; u.questionsToday = 0 }
    }

    fun limit(u: User): AssistantLimitDto {
        resetDailyLimit(u)
        val resets = clock().atZoneSameInstant(ZoneOffset.ofHours(3)).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.ofHours(3)).toOffsetDateTime()
        return AssistantLimitDto(config.dailyAssistantLimit - u.questionsToday, config.dailyAssistantLimit, resets.toString())
    }

    fun ask(u: User, req: AskRequestDto): Result<AskResponseDto> {
        if (req.text.isBlank() || req.text.length > 2000) return Result.failure(ApiFailure(400, "VALIDATION_ERROR", "text length", listOf(ErrorDetailDto("text", "OUT_OF_RANGE", "1..2000"))))
        if (req.transferMode != null && req.transferMode != "with" && req.transferMode != "without") return Result.failure(ApiFailure(400, "VALIDATION_ERROR", "transfer_mode", listOf(ErrorDetailDto("transfer_mode", "INVALID_FORMAT", "with|without"))))
        if (u.assistantConsentVersion != config.assistantConsentVersion) return Result.failure(ApiFailure(403, "CONSENT_REQUIRED", "assistant consent missing"))
        resetDailyLimit(u)
        if (u.questionsToday >= config.dailyAssistantLimit) return Result.failure(ApiFailure(403, "LIMIT_EXCEEDED", "daily limit", resetsAt = limit(u).resetsAt))
        u.questionsToday++
        val now = clock()
        val question = MessageDto(Ids.next(), "user", text = req.text, createdAt = wire(u).time(now))
        u.messages += question
        val answerId = Ids.next()
        u.messages += MessageDto(answerId, "assistant", status = "generating", blocks = emptyList(), createdAt = wire(u).time(now.plusSeconds(1)))
        generate(u, answerId, question.id, req.text, req.transferMode ?: "with")
        return Result.success(AskResponseDto(question, answerId, config.dailyAssistantLimit - u.questionsToday))
    }

    fun retry(u: User, answerId: String): Result<RetryResponseDto> {
        val old = u.answers[answerId] ?: return Result.failure(ApiFailure(404, "NOT_FOUND", "no such answer"))
        if (old.status != "failed" || old.errorCode != "MODEL_ERROR" || old.replaced) return Result.failure(ApiFailure(422, "RETRY_NOT_ALLOWED", "answer is not retryable"))
        if (u.assistantConsentVersion != config.assistantConsentVersion) return Result.failure(ApiFailure(403, "CONSENT_REQUIRED", "assistant consent missing"))
        resetDailyLimit(u)
        if (u.questionsToday >= config.dailyAssistantLimit) return Result.failure(ApiFailure(403, "LIMIT_EXCEEDED", "daily limit", resetsAt = limit(u).resetsAt))
        u.questionsToday++
        old.replaced = true
        val question = u.messages.first { it.id == old.questionId }
        val newId = Ids.next()
        val idx = u.messages.indexOfFirst { it.id == answerId }
        u.messages[idx] = MessageDto(newId, "assistant", status = "generating", blocks = emptyList(), createdAt = wire(u).time(clock()))
        generate(u, newId, question.id, question.text.orEmpty(), "with")
        return Result.success(RetryResponseDto(newId, config.dailyAssistantLimit - u.questionsToday))
    }

    /** Simulates streaming: text in chunks, then non-text blocks, source, chips, done. */
    private fun generate(u: User, answerId: String, questionId: String, text: String, transferMode: String) {
        val process = AnswerProcess(answerId, questionId)
        u.answers[answerId] = process
        thread(name = "mock-answer-$answerId") {
            Thread.sleep(800)
            val outcome = synchronized(lock) { AssistantEngine(u.ledger, u.timezone, wire(u)).answer(text, transferMode, clock()) }
            val forceError = text.contains("ошибка", true) || text.contains("error", true)
            if (forceError || outcome is AssistantEngine.Outcome.CannotAnswer) {
                val code = if (forceError) "MODEL_ERROR" else "CANNOT_ANSWER"
                synchronized(lock) {
                    u.questionsToday = maxOf(0, u.questionsToday - 1)
                    process.status = "failed"; process.errorCode = code
                    replaceMessage(u, answerId) { it.copy(status = "failed", errorCode = code, blocks = emptyList()) }
                    process.events += "error" to json.encodeToString(StreamErrorDto(code, "mock failure", remainingLimit = config.dailyAssistantLimit - u.questionsToday))
                }
                return@thread
            }
            val answer = (outcome as AssistantEngine.Outcome.Ok).answer
            val blocks = mutableListOf<BlockDto>()
            answer.blocks.forEachIndexed { index, block ->
                if (block.type == "text") {
                    val words = block.text.orEmpty().split(" ")
                    var acc = ""
                    words.chunked(4).forEach { chunk ->
                        val piece = (if (acc.isEmpty()) "" else " ") + chunk.joinToString(" ")
                        acc += piece
                        process.events += "chunk" to json.encodeToString(AnswerChunkEventDto(index, piece))
                        process.blocks = blocks + BlockDto("text", text = acc)
                        Thread.sleep(120)
                    }
                    blocks += BlockDto("text", text = acc)
                } else {
                    Thread.sleep(300)
                    blocks += block
                    process.blocks = blocks.toList()
                    process.events += "block" to json.encodeToString(AnswerBlockEventDto(index, block))
                }
            }
            process.source = answer.source
            process.events += "source" to json.encodeToString(answer.source)
            answer.chips.forEach { chip -> process.chips = process.chips + chip; process.events += "chip" to json.encodeToString(chip) }
            synchronized(lock) {
                if (!answer.charged) u.questionsToday = maxOf(0, u.questionsToday - 1)
                process.charged = answer.charged
                process.status = "complete"
                replaceMessage(u, answerId) { it.copy(status = "complete", charged = answer.charged, blocks = blocks, chips = answer.chips, source = answer.source) }
                process.events += "done" to json.encodeToString(AnswerDoneEventDto(answer.charged, config.dailyAssistantLimit - u.questionsToday))
            }
        }
    }

    private fun replaceMessage(u: User, id: String, f: (MessageDto) -> MessageDto) {
        val i = u.messages.indexOfFirst { it.id == id }
        if (i >= 0) u.messages[i] = f(u.messages[i])
    }

    fun snapshot(u: User, answerId: String): Result<AnswerSnapshotEventDto> {
        val p = u.answers[answerId] ?: return Result.failure(ApiFailure(404, "NOT_FOUND", "no such answer"))
        if (p.replaced) return Result.failure(ApiFailure(404, "NOT_FOUND", "answer replaced"))
        return Result.success(AnswerSnapshotEventDto(p.status, p.blocks, p.chips, p.source))
    }

    fun messages(u: User, limit: Int, beforeId: String?): MessagesListDto {
        val all = u.messages.map { m ->
            // changed_since is computed at request time
            val src = m.source
            if (src != null) m.copy(source = src.copy(changedSince = changedSince(u, src.calculatedAt))) else m
        }
        val end = beforeId?.let { id -> all.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: all.size
        val start = maxOf(0, end - limit)
        return MessagesListDto(all.subList(start, end), hasOlder = start > 0)
    }

    private fun changedSince(u: User, calculatedAt: String): List<String> {
        val t = runCatching { OffsetDateTime.parse(calculatedAt) }.getOrNull() ?: return emptyList()
        return buildList {
            if (u.ledger.lastCategoryChangeAt?.isAfter(t) == true) add("categories")
            if (u.ledger.lastUploadChangeAt?.isAfter(t) == true) add("uploads")
        }
    }

    // ---- idempotency ---------------------------------------------------------------------------------------------

    class StoredResponse(val bodyHash: Int, val status: Int, val body: String, val storedAt: OffsetDateTime)
    private val idempotency = hashMapOf<String, StoredResponse>()

    /** Returns a stored response for (scope, endpoint, key) or null; throws [ApiFailure] on a conflicting body. */
    fun idempotencyLookup(scope: String, endpoint: String, key: String, bodyHash: Int, ttlSeconds: Long): StoredResponse? {
        val k = "$scope|$endpoint|$key"
        val s = idempotency[k] ?: return null
        if (ChronoUnit.SECONDS.between(s.storedAt, clock()) > ttlSeconds) { idempotency.remove(k); return null }
        if (s.bodyHash != bodyHash) throw ApiFailure(409, "IDEMPOTENCY_CONFLICT", "same key, different body")
        return s
    }

    fun idempotencyStore(scope: String, endpoint: String, key: String, bodyHash: Int, status: Int, body: String) {
        if (status in 200..299 || status == 400 || status == 413 || status == 422) {
            idempotency["$scope|$endpoint|$key"] = StoredResponse(bodyHash, status, body, clock())
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    fun errorBody(f: ApiFailure): String = json.encodeToString(ErrorResponseDto(ErrorDto(f.code, f.message ?: "", f.details, f.retryAt, f.resetsAt)))

}

/** An API error with everything needed to render the contract's error body. */
class ApiFailure(
    val status: Int,
    val code: String,
    message: String,
    val details: List<ErrorDetailDto>? = null,
    val retryAt: String? = null,
    val resetsAt: String? = null,
    val retryAfterSeconds: Int? = null,
) : Exception(message)
