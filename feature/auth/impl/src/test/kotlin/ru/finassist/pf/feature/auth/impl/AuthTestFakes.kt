package ru.finassist.pf.feature.auth.impl

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.AssistantConsent
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.api.model.PhoneVerificationStatusEvent
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Breadcrumb
import ru.finassist.pf.core.tracking.CrashKey
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.CrashValue
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.NonFatal
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.impl.data.SessionRepositoryImpl
import ru.finassist.pf.feature.auth.impl.domain.SignInFlow
import java.io.File
import java.time.OffsetDateTime
import java.util.Collections
import kotlin.io.path.createTempDirectory

/*
 * Fakes shared by the tests that need a real [SessionRepositoryImpl] (it is a concrete class the call and consent
 * view models depend on). Every side effect lands in one ordered [Log], so a test can check what happened before what.
 */

/** Thread-safe ordered record of side effects: the repository works on `Dispatchers.IO`. */
class Log {
    private val entries = Collections.synchronizedList(mutableListOf<String>())
    operator fun plusAssign(entry: String) { entries += entry }
    fun all(): List<String> = synchronized(entries) { entries.toList() }
    fun indexOf(entry: String): Int = all().indexOf(entry)
}

class FakeTokenStore(private val log: Log, initial: TokenStore.Session? = null) : TokenStore {
    val flow = MutableStateFlow(initial)
    /** Called inside [save] before the value is published — lets a test look at other state at that moment. */
    var onSave: (TokenStore.Session) -> Unit = {}
    /** Suspends inside [clear] before the session is dropped — lets a test hold a sign-out half-way. */
    var beforeClear: suspend () -> Unit = {}
    /** Suspends a collector of [session] before it gets a value — lets a test deliver a change late. */
    @Volatile var beforeDeliver: suspend (TokenStore.Session?) -> Unit = {}
    override val session: Flow<TokenStore.Session?> = flow.onEach { beforeDeliver(it) }
    override suspend fun current(): TokenStore.Session? = flow.value
    override suspend fun save(session: TokenStore.Session) {
        onSave(session)
        log += "save:${session.userId}"
        flow.value = session
    }
    override suspend fun updateTokens(expectedRefreshToken: String, tokens: TokenPair): Boolean = error("unused")
    override suspend fun clear() {
        beforeClear()
        log += "clear"
        flow.value = null
    }
}

class FakeProfileApi(private val log: Log) : ProfileApi {
    @Volatile var userId = "u_profile"
    @Volatile var timezone = "Europe/Moscow"
    @Volatile var failure: Throwable? = null
    val updates: MutableList<ProfileUpdate> = Collections.synchronizedList(mutableListOf())

    override suspend fun getProfile(): Profile {
        failure?.let { throw it }
        log += "getProfile"
        return Profile(
            userId = userId, phone = "+79161234567", theme = Theme.SYSTEM, timezone = timezone,
            assistantConsent = AssistantConsent(granted = false, currentVersion = "1"),
            createdAt = OffsetDateTime.parse("2026-10-01T10:00:00+03:00"),
        )
    }
    override suspend fun updateProfile(update: ProfileUpdate): Profile {
        updates += update
        return getProfile()
    }
    override suspend fun deleteAccount() = error("unused")
    override suspend fun grantAssistantConsent(grant: AssistantConsentGrant): Profile = error("unused")
    override suspend fun revokeAssistantConsent() = error("unused")
}

/** Auth API with every call scriptable; unused calls fail loudly. */
open class FakeAuthApi(private val log: Log = Log()) : AuthApi {
    var startPhone: suspend (IdempotencyKey, String) -> PhoneVerification = { _, _ -> error("unused") }
    var stream: (String) -> Flow<PhoneVerificationStatusEvent> = { emptyFlow() }
    var registerCall: suspend (IdempotencyKey, RegisterRequest) -> RegisterResponse = { _, _ -> error("unused") }
    var consent: suspend (ConsentType) -> ConsentDocument = { error("unused") }
    /** Logout fails with this for the given access token (the first `/logout` with an expired token). */
    var logoutFailure: (String) -> Throwable? = { null }
    var refreshed = TokenPair("a_fresh", "r_fresh")

    override suspend fun startPhoneVerification(key: IdempotencyKey, phone: String) = startPhone(key, phone)
    override fun streamPhoneVerification(verificationToken: String) = stream(verificationToken)
    override suspend fun register(key: IdempotencyKey, request: RegisterRequest) = registerCall(key, request)
    override suspend fun refreshTokens(key: IdempotencyKey, refreshToken: String): TokenPair {
        log += "refresh:$refreshToken"
        return refreshed
    }
    override suspend fun logout(accessToken: String, refreshToken: String) {
        log += "logout:$accessToken/$refreshToken"
        logoutFailure(accessToken)?.let { throw it }
    }
    override suspend fun getConsentDocument(type: ConsentType) = consent(type)
}

class RecordingTracker(private val log: Log) : Tracker {
    override fun track(event: Event, params: Map<String, String>) { log += "track:${event.name}" }
    override fun setUserId(userId: String?) { log += "tracker.user:$userId" }
}

class RecordingFlags(private val log: Log, private val values: Map<Flag, Boolean> = emptyMap()) : FeatureFlags {
    override fun isEnabled(flag: Flag): Boolean = values[flag] ?: flag.defaultValue
    override val changes: Flow<Unit> = emptyFlow()
    override val version: String = "test"
    override suspend fun setUser(userId: String?) { log += "flags.user:$userId" }
    override suspend fun refresh() = Unit
}

class RecordingCrashReporter(private val log: Log) : CrashReporter {
    val crumbs: MutableList<Breadcrumb> = Collections.synchronizedList(mutableListOf())
    override fun report(error: NonFatal) = Unit
    override fun breadcrumb(crumb: Breadcrumb) { crumbs += crumb }
    override fun setUserId(userId: String?) { log += "crash.user:$userId" }
    override fun setKey(key: CrashKey, value: CrashValue?) = Unit
}

/** A real [SessionRepositoryImpl] over the fakes above and a DataStore in a temp directory. */
class SessionHarness(
    initial: TokenStore.Session? = null,
    flagValues: Map<Flag, Boolean> = emptyMap(),
) {
    val log = Log()
    val tokens = FakeTokenStore(log, initial)
    val auth = FakeAuthApi(log)
    val profile = FakeProfileApi(log)
    val tracker = RecordingTracker(log)
    val flags = RecordingFlags(log, flagValues)
    val crash = RecordingCrashReporter(log)
    val flow = SignInFlow()
    private val dir: File = createTempDirectory("session").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }
    val repo = SessionRepositoryImpl(tokens, auth, profile, flags, tracker, crash, dataStore, flow)

    fun close() {
        scope.cancel()
        dir.deleteRecursively()
    }
}

/** Waits for work the repository finishes on its own scope (identity, time zone, best-effort `/logout`). */
fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) = runBlocking {
    withTimeout(timeoutMs) { while (!condition()) delay(5) }
}
