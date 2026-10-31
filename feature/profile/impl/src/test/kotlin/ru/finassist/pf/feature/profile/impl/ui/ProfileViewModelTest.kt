package ru.finassist.pf.feature.profile.impl.ui

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.launch
import ru.finassist.pf.feature.assistant.api.AssistantRoutes
import ru.finassist.pf.feature.profile.impl.di.collectAssistantConsentResults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.UploadFile
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AskResponse
import ru.finassist.pf.core.api.model.Consent
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.MessagesList
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.api.model.StatementsList
import ru.finassist.pf.core.api.model.StatementsSummary
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.api.model.UnreadLinesList
import ru.finassist.pf.core.api.model.UploadAccepted
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.ReportZone
import ru.finassist.pf.core.storage.AppPreferences
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.applock.api.BiometricAvailability
import ru.finassist.pf.feature.applock.api.LockState
import ru.finassist.pf.feature.applock.api.VerifyResult
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.profile.impl.data.FakeClock
import ru.finassist.pf.feature.profile.impl.data.FakeDeviceZone
import ru.finassist.pf.feature.profile.impl.data.TimeZoneChecker
import ru.finassist.pf.feature.profile.impl.data.tempZoneSyncStore
import ru.finassist.pf.feature.profile.impl.data.tempKnownStore
import ru.finassist.pf.feature.profile.impl.data.tempPendingStore
import ru.finassist.pf.feature.statements.api.StatementsEvent
import ru.finassist.pf.feature.statements.api.StatementsRepository
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.io.path.createTempDirectory
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AppPreferences] is a final class, so the tests use a real one over a temp-file DataStore.
 * The device time zone is pinned to Moscow, so a profile in another zone differs from the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private class FakeProfileApi : ProfileApi {
        var profile = profile()
        var getFailure: AppError? = null
        var getReply: (suspend () -> Profile)? = null
        var gets = 0
        val updates = mutableListOf<ProfileUpdate>()
        var updateFailure: AppError? = null
        var updateReply: (suspend (ProfileUpdate) -> Profile)? = null
        var revokeFailure: AppError? = null
        var revokes = 0

        override suspend fun getProfile(): Profile {
            gets++
            getReply?.let { return it() }
            getFailure?.let { throw it }
            return profile
        }
        override suspend fun updateProfile(update: ProfileUpdate): Profile {
            updates += update
            updateReply?.let { return it(update) }
            updateFailure?.let { throw it }
            return profile.copy(timezone = update.timezone ?: profile.timezone, theme = update.theme ?: profile.theme)
        }
        override suspend fun deleteAccount() = Unit
        override suspend fun grantAssistantConsent(grant: AssistantConsentGrant): Profile = error("unused")
        override suspend fun revokeAssistantConsent() {
            revokes++
            revokeFailure?.let { throw it }
        }
    }

    private class FakeStatementsApi : StatementsApi {
        var calls = 0
        var failure: AppError? = null
        var summary: StatementsSummary? = summary(2)
        var listReply: (suspend () -> StatementsList)? = null
        override suspend fun listStatements(): StatementsList {
            calls++
            listReply?.let { return it() }
            failure?.let { throw it }
            return StatementsList(summary, emptyList())
        }
        override suspend fun getImportConfig(): ImportConfig = error("unused")
        override suspend fun uploadStatement(key: IdempotencyKey, file: UploadFile): UploadAccepted = error("unused")
        override fun streamProgress(uploadId: String): Flow<ImportEvent> = error("unused")
        override suspend fun deleteStatement(uploadId: String) = Unit
        override suspend fun listUnreadLines(uploadId: String): UnreadLinesList = error("unused")
        override suspend fun listUnreadLines(from: OffsetDateTime, to: OffsetDateTime): UnreadLinesList = error("unused")
    }

    private class FakeAssistant : AssistantApi {
        var calls = 0
        var limit = LIMIT
        var failure: AppError? = null
        override suspend fun getLimit(): AssistantLimit {
            calls++
            failure?.let { throw it }
            return limit
        }
        override suspend fun listMessages(limit: Int?, beforeId: String?): MessagesList = error("unused")
        override suspend fun ask(key: IdempotencyKey, request: AskRequest): AskResponse = error("unused")
        override fun streamAnswer(answerId: String): Flow<AnswerEvent> = error("unused")
        override suspend fun retry(key: IdempotencyKey, answerId: String): RetryResponse = error("unused")
    }

    private class FakeSession : SessionRepository {
        val signOuts = mutableListOf<String?>()
        var gate: CompletableDeferred<Unit>? = null
        override val state: Flow<SessionState> = MutableStateFlow(SessionState.SignedIn("u_1", "epoch-1"))
        override suspend fun consumeJustRegistered() = false
        override suspend fun signOut(reason: String?) {
            signOuts += reason
            gate?.await()
        }
        override suspend fun clearLocal(reason: String?) = Unit
        override fun consumeSignOutReason(): String? = null
    }

    private class FakeStatements : StatementsRepository {
        override suspend fun pendingAnalyticsImport(): Long? = error("unused")
        override suspend fun analyticsImportReady(revision: Long): Unit = error("unused")
        override val events = MutableSharedFlow<StatementsEvent>(extraBufferCapacity = 4)
        override fun lastResult(uploadId: String): ImportResult? = null
        override suspend fun consumeFirstImportHints(): Boolean = false
    }

    private class FakeFlags(private val values: MutableMap<Flag, Boolean> = mutableMapOf()) : FeatureFlags {
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        override fun isEnabled(flag: Flag): Boolean = values[flag] ?: flag.defaultValue
        override val version: String = "test"
        override suspend fun setUser(userId: String?) = Unit
        override suspend fun refresh() = Unit
        fun set(flag: Flag, on: Boolean) {
            values[flag] = on
            changes.tryEmit(Unit)
        }
    }

    private class FakeTracker : Tracker {
        val events = mutableListOf<Pair<Event, Map<String, String>>>()
        override fun track(event: Event, params: Map<String, String>) {
            events += event to params
        }
        override fun setUserId(userId: String?) = Unit
    }

    private class FakeAppLock(private val availability: BiometricAvailability) : AppLock {
        override val state: StateFlow<LockState> = MutableStateFlow(LockState.Unlocked)
        override val biometricEnabled = MutableStateFlow(false)
        override fun biometricAvailability() = availability
        override suspend fun setPasscode(code: String) = Unit
        override suspend fun verify(code: String): VerifyResult = VerifyResult.Ok
        override suspend fun matches(code: String) = true
        override suspend fun setBiometricEnabled(enabled: Boolean) = Unit
        override fun unlockByBiometric() = Unit
        override fun lock() = Unit
        override suspend fun forgetAndSignOut() = Unit
        override suspend fun reset() = Unit
    }

    private val profileApi = FakeProfileApi()
    private val statementsApi = FakeStatementsApi()
    private val assistant = FakeAssistant()
    private val preferences = tempPreferences()
    private val session = FakeSession()
    private val statements = FakeStatements()
    private val flags = FakeFlags()
    private val tracker = FakeTracker()
    private var appLock = FakeAppLock(BiometricAvailability.NONE)
    private lateinit var deviceZone: TimeZone

    @BeforeTest fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        deviceZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(MOSCOW))
    }

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        TimeZone.setDefault(deviceZone)
    }

    private val reportZone = ReportZone { ZoneId.of(MOSCOW) }
    private val zoneChecker by lazy {
        TimeZoneChecker(profileApi, session, tempPendingStore(session), FakeClock(), FakeDeviceZone(MOSCOW), reportZone, tempKnownStore(session), tempZoneSyncStore(session), { 0L }, { 1 })
    }

    private fun vm() = ProfileViewModel(profileApi, statementsApi, assistant, preferences, session, statements, flags, tracker, appLock, zoneChecker, ru.finassist.pf.core.common.time.ElapsedClock { 0L })

    private fun loaded() = vm().apply { load() }

    private fun localTheme(): String? = runBlocking { preferences.theme.first() }

    @Test fun `the profile, the statements summary and the question limit are shown`() {
        val s = loaded().state.value
        assertEquals(profileApi.profile, s.profile)
        assertFalse(s.loading)
        assertFalse(s.offline)
        assertEquals(summary(2), s.statements)
        assertTrue(s.statementsLoaded)
        assertEquals(LIMIT, s.limit)
    }

    @Test fun `without the assistant the limit is not requested`() {
        flags.set(Flag.ASSISTANT, false)
        val s = loaded().state.value
        assertEquals(0, assistant.calls)
        assertNull(s.limit)
        assertFalse(s.flags.assistant)
    }

    @Test fun `a failed limit or statements request leaves its part empty`() {
        assistant.failure = AppError.Offline()
        statementsApi.failure = AppError.Offline()
        val s = loaded().state.value
        assertNull(s.limit)
        assertNull(s.statements)
        assertFalse(s.statementsLoaded)
        assertEquals(profileApi.profile, s.profile)
    }

    @Test fun `no statements yet is a loaded empty summary`() {
        statementsApi.summary = null
        val s = loaded().state.value
        assertNull(s.statements)
        assertTrue(s.statementsLoaded)
    }

    @Test fun `no network on the first load is the offline state, and the next resume recovers`() {
        profileApi.getFailure = AppError.Offline()
        val vm = loaded()
        assertTrue(vm.state.value.offline)
        assertFalse(vm.state.value.loading)

        profileApi.getFailure = null
        vm.load()
        assertFalse(vm.state.value.offline)
        assertEquals(profileApi.profile, vm.state.value.profile)
    }

    @Test fun `canonical theme warning stays visible and its false does not block profile reads`() {
        val vm = loaded()
        val presentation = ru.finassist.pf.core.common.error.ErrorPresentation(
            ru.finassist.pf.core.common.error.UserMessage.from("Тема временно недоступна"),
            ru.finassist.pf.core.common.error.ErrorSeverity.WARNING,
        )
        profileApi.updateFailure = AppError.Api("FUTURE_THEME", 409, response = ru.finassist.pf.core.common.error.ErrorResponseInfo(
            "FUTURE_THEME", 409, presentation = presentation, requestRetryable = false,
        ))
        vm.setTheme(Theme.DARK)
        assertEquals(presentation, vm.state.value.errorPresentation)
        assertEquals(profileApi.profile, vm.state.value.profile)
        vm.setTheme(Theme.DARK)
        assertEquals(1, profileApi.updates.size, "the refused unchanged mutation stays blocked")
        vm.load()
        assertEquals(2, profileApi.gets, "GET is a different request")
        vm.setTheme(Theme.LIGHT)
        assertEquals(2, profileApi.updates.size, "a changed selection is a new action")
    }

    private fun forbiddenProfileRequest() = AppError.Api("FUTURE_ERROR", 400,
        response = ru.finassist.pf.core.common.error.ErrorResponseInfo("FUTURE_ERROR", 400, requestRetryable = false))

    @Test fun `a forbidden profile GET does not block independent summary and limit reads on resume`() {
        val vm = loaded()
        profileApi.getFailure = forbiddenProfileRequest()
        vm.load()
        assertEquals(2, profileApi.gets)
        statementsApi.summary = summary(3)
        assistant.limit = LIMIT.copy(remaining = 1)
        vm.load()
        assertEquals(2, profileApi.gets, "the unchanged profile request remains forbidden")
        assertEquals(3, statementsApi.calls)
        assertEquals(3, assistant.calls)
        assertEquals(summary(3), vm.state.value.statements)
        assertEquals(1, vm.state.value.limit?.remaining)
        assertFalse(vm.state.value.retryReady)
    }

    @Test fun `an older summary failure cannot poison a successful read after an actual statement change`() {
        val vm = loaded()
        var old: kotlin.coroutines.Continuation<StatementsList>? = null
        var replies = 0
        statementsApi.listReply = {
            if (++replies == 1) kotlin.coroutines.suspendCoroutine { old = it }
            else StatementsList(summary(3), emptyList())
        }
        vm.load()
        statements.events.tryEmit(StatementsEvent.UploadDeleted("u1"))
        assertEquals(summary(3), vm.state.value.statements)
        old!!.resumeWith(Result.failure(forbiddenProfileRequest()))
        assertEquals(summary(3), vm.state.value.statements)
        assertNull(vm.state.value.errorPresentation)
        vm.load()
        assertEquals(4, statementsApi.calls, "the stale refusal did not block the current context")
    }

    @Test fun `an older summary success cannot replace or release a newer refusal after a statement change`() {
        val vm = loaded()
        var old: kotlin.coroutines.Continuation<StatementsList>? = null
        var replies = 0
        statementsApi.listReply = {
            if (++replies == 1) kotlin.coroutines.suspendCoroutine { old = it }
            else throw forbiddenProfileRequest()
        }
        vm.load()
        statements.events.tryEmit(StatementsEvent.UploadDeleted("u1"))
        old!!.resumeWith(Result.success(StatementsList(summary(9), emptyList())))
        assertEquals(summary(2), vm.state.value.statements, "the pre-change result stays obsolete")
        vm.load()
        assertEquals(3, statementsApi.calls, "the current summary refusal remains in force")
    }

    @Test fun `repeated resume preserves an unchanged summary refusal`() {
        val vm = loaded()
        statementsApi.failure = forbiddenProfileRequest()
        vm.load()
        vm.load()
        assertEquals(2, statementsApi.calls)
        statements.events.tryEmit(StatementsEvent.UploadDeleted("u1"))
        assertEquals(3, statementsApi.calls, "only an actual statement change releases that context")
    }

    @Test fun `an older theme refusal cannot block the newer successful choice`() {
        val vm = loaded()
        var old: kotlin.coroutines.Continuation<Profile>? = null
        profileApi.updateReply = { update ->
            if (update.theme == Theme.DARK) kotlin.coroutines.suspendCoroutine { old = it }
            else profile(theme = update.theme!!)
        }
        vm.setTheme(Theme.DARK)
        vm.setTheme(Theme.LIGHT)
        old!!.resumeWith(Result.failure(forbiddenProfileRequest()))
        assertNull(vm.state.value.errorPresentation)
        assertEquals("light", localTheme())
        vm.setTheme(Theme.LIGHT)
        assertEquals(3, profileApi.updates.size, "the older theme's false does not belong to LIGHT")
    }

    @Test fun `an older theme failure cannot release the newer choice refusal`() {
        val vm = loaded()
        var old: kotlin.coroutines.Continuation<Profile>? = null
        profileApi.updateReply = { update ->
            if (update.theme == Theme.DARK) kotlin.coroutines.suspendCoroutine { old = it }
            else throw forbiddenProfileRequest()
        }
        vm.setTheme(Theme.DARK)
        vm.setTheme(Theme.LIGHT)
        val currentPresentation = vm.state.value.errorPresentation
        old!!.resumeWith(Result.failure(AppError.Offline()))
        assertEquals(currentPresentation, vm.state.value.errorPresentation)
        vm.setTheme(Theme.LIGHT)
        assertEquals(2, profileApi.updates.size, "a stale legacy failure cannot overwrite the current false")
    }

    @Test fun `a consumed successful consent result refreshes a refused profile and enables a real revoke`() {
        val withoutConsent = profile().withConsent(profile().assistantConsent!!.copy(granted = false))
        profileApi.profile = withoutConsent
        val vm = loaded()
        profileApi.getFailure = forbiddenProfileRequest()
        vm.load()
        vm.load()
        assertEquals(2, profileApi.gets)
        profileApi.getFailure = null
        profileApi.profile = profile()
        val handle = SavedStateHandle()
        val resultJob = CoroutineScope(UnconfinedTestDispatcher()).launch {
            collectAssistantConsentResults(handle, vm::onConsentResult)
        }
        try {
            handle[AssistantRoutes.CONSENT_RESULT] = "true"
            assertNull(handle.get<String>(AssistantRoutes.CONSENT_RESULT))
            assertEquals(3, profileApi.gets)
            assertEquals(true, vm.state.value.profile?.assistantConsent?.granted)
            assertTrue(vm.state.value.retryReady)
            // A read started before successful revoke must not restore its old consent value afterward.
            var old: kotlin.coroutines.Continuation<Profile>? = null
            profileApi.getReply = { kotlin.coroutines.suspendCoroutine { old = it } }
            vm.load()
            vm.revokeConsent()
            old!!.resumeWith(Result.success(profile()))
            assertEquals(1, profileApi.revokes)
            assertEquals(false, vm.state.value.profile?.assistantConsent?.granted)
        } finally { resultJob.cancel() }
    }

    @Test fun `cancelled or false consent results and ordinary resume preserve the same profile refusal`() {
        val vm = loaded()
        profileApi.getFailure = forbiddenProfileRequest()
        vm.load()
        val handle = SavedStateHandle()
        val resultJob = CoroutineScope(UnconfinedTestDispatcher()).launch {
            collectAssistantConsentResults(handle, vm::onConsentResult)
        }
        try {
            vm.onConsentResult(null)
            handle[AssistantRoutes.CONSENT_RESULT] = "false"
            assertNull(handle.get<String>(AssistantRoutes.CONSENT_RESULT))
            vm.load()
            assertEquals(2, profileApi.gets)
            assertFalse(vm.state.value.retryReady)
        } finally { resultJob.cancel() }
    }

    @Test fun `an old profile refusal cannot poison the fresh read after successful consent`() {
        val vm = loaded()
        var old: kotlin.coroutines.Continuation<Profile>? = null
        var replies = 0
        profileApi.getReply = {
            if (++replies == 1) kotlin.coroutines.suspendCoroutine { old = it }
            else profile()
        }
        vm.load()
        vm.onConsentResult("true")
        old!!.resumeWith(Result.failure(forbiddenProfileRequest()))
        assertEquals(profile(), vm.state.value.profile)
        assertNull(vm.state.value.errorPresentation)
        assertTrue(vm.state.value.retryReady)
        vm.load()
        assertEquals(4, profileApi.gets)
    }

    @Test fun `an old profile success cannot replace or release a fresh refusal after successful consent`() {
        val vm = loaded()
        var old: kotlin.coroutines.Continuation<Profile>? = null
        var replies = 0
        profileApi.getReply = {
            if (++replies == 1) kotlin.coroutines.suspendCoroutine { old = it }
            else throw forbiddenProfileRequest()
        }
        vm.load()
        vm.onConsentResult("true")
        old!!.resumeWith(Result.success(profile().withConsent(profile().assistantConsent!!.copy(granted = false))))
        assertEquals(profile(), vm.state.value.profile)
        assertEquals(false, vm.state.value.requestRetryable)
        assertFalse(vm.state.value.retryReady)
        vm.load()
        assertEquals(3, profileApi.gets)
    }

    @Test fun `a failed refresh on resume keeps the shown profile`() {
        val vm = loaded()
        profileApi.getFailure = AppError.Server(500)
        vm.load()
        assertFalse(vm.state.value.offline)
        assertEquals(profileApi.profile, vm.state.value.profile)
    }

    @Test fun `an import or a deleted statement refreshes the summary`() {
        val vm = loaded()
        statementsApi.summary = summary(3)
        statements.events.tryEmit(StatementsEvent.UploadDeleted("u1"))
        assertEquals(summary(3), vm.state.value.statements)
    }

    @Test fun `without a local theme the server's one is adopted`() {
        profileApi.profile = profile(theme = Theme.DARK)
        val vm = loaded()
        assertEquals("dark", localTheme())
        assertEquals(Theme.DARK, vm.state.value.theme)
    }

    @Test fun `the local theme wins over the server's one`() {
        runBlocking { preferences.setTheme("light") }
        profileApi.profile = profile(theme = Theme.DARK)
        val vm = loaded()
        assertEquals("light", localTheme())
        assertEquals(Theme.LIGHT, vm.state.value.theme)
    }

    @Test fun `an unknown local theme is the system one`() {
        runBlocking { preferences.setTheme("sepia") }
        assertEquals(Theme.SYSTEM, vm().state.value.theme)
    }

    @Test fun `opening the profile does not send another device time zone (FIN-91, TimeZoneSync does after 72 h)`() {
        profileApi.profile = profile(timezone = "Asia/Yekaterinburg")
        val vm = loaded()
        assertEquals(emptyList(), profileApi.updates)
        assertEquals("Asia/Yekaterinburg", vm.state.value.profile?.timezone)
    }

    @Test fun `the answer to a theme change carries the profile zone into the report zone (FIN-92)`() {
        val vm = vm()
        profileApi.profile = profile(timezone = "Asia/Vladivostok")
        vm.setTheme(Theme.DARK)
        assertEquals(ZoneId.of("Asia/Vladivostok"), reportZone.current)
    }

    @Test fun `the zone of the opened profile becomes the report zone (FIN-92)`() {
        profileApi.profile = profile(timezone = "Asia/Vladivostok")
        loaded()
        assertEquals(ZoneId.of("Asia/Vladivostok"), reportZone.current)
    }

    @Test fun `a chosen theme closes the sheet, is saved locally and on the server`() {
        val vm = vm()
        vm.openThemeSheet(true)
        assertTrue(vm.state.value.themeSheet)
        vm.setTheme(Theme.DARK)
        assertFalse(vm.state.value.themeSheet)
        assertEquals("dark", localTheme())
        assertEquals(Theme.DARK, vm.state.value.theme)
        assertEquals(listOf(ProfileUpdate(theme = Theme.DARK)), profileApi.updates)
        assertEquals(Events.PROFILE_THEME_CHANGED to mapOf("theme" to "dark"), tracker.events.single())
    }

    @Test fun `a theme the server did not take is still applied locally`() {
        profileApi.updateFailure = AppError.Offline()
        val vm = vm()
        vm.setTheme(Theme.LIGHT)
        assertEquals(Theme.LIGHT, vm.state.value.theme)
    }

    @Test fun `the theme sheet closes without a choice`() {
        val vm = vm()
        vm.openThemeSheet(true)
        vm.openThemeSheet(false)
        assertFalse(vm.state.value.themeSheet)
        assertEquals(emptyList(), profileApi.updates)
    }

    @Test fun `switching the assistant off revokes the consent at once`() {
        val vm = loaded()
        vm.revokeConsent()
        val s = vm.state.value
        assertEquals(1, profileApi.revokes)
        assertFalse(s.consentBusy)
        assertEquals(false, s.profile?.assistantConsent?.granted)
        assertEquals("v2", s.profile?.assistantConsent?.version) // only the switch changes
        assertEquals(Events.ASSISTANT_CONSENT_REVOKED, tracker.events.single().first)
    }

    @Test fun `a consent revoked before the profile loaded leaves nothing to update`() {
        profileApi.getFailure = AppError.Offline()
        val vm = loaded()
        vm.revokeConsent()
        assertNull(vm.state.value.profile)
        assertFalse(vm.state.value.consentBusy)
    }

    @Test fun `a failed revoke keeps the consent and says why`() {
        val vm = loaded()
        profileApi.revokeFailure = AppError.Offline()
        vm.revokeConsent()
        assertEquals("Нет сети — согласие не отозвано", vm.state.value.snackbar)
        assertEquals(true, vm.state.value.profile?.assistantConsent?.granted)
        assertFalse(vm.state.value.consentBusy)

        profileApi.revokeFailure = AppError.Server(500)
        vm.revokeConsent()
        assertEquals("Не получилось. Попробуйте ещё раз", vm.state.value.snackbar)
        assertTrue(tracker.events.none { it.first == Events.ASSISTANT_CONSENT_REVOKED })

        vm.consumeSnackbar()
        assertNull(vm.state.value.snackbar)
    }

    @Test fun `logout asks first and then signs out with its reason`() {
        val vm = vm()
        vm.askLogout(true)
        assertTrue(vm.state.value.askLogout)
        vm.askLogout(false)
        assertFalse(vm.state.value.askLogout)
        assertEquals(emptyList(), session.signOuts)

        session.gate = CompletableDeferred()
        vm.logout()
        assertTrue(vm.state.value.loggingOut)
        assertEquals(listOf<String?>(AuthRoutes.Phone.REASON_LOGGED_OUT), session.signOuts)
        session.gate?.complete(Unit)
    }

    @Test fun `without biometrics on the device there is no biometric switch`() {
        assertNull(vm().state.value.biometric)
    }

    @Test fun `with biometrics the switch follows the app lock setting`() {
        appLock = FakeAppLock(BiometricAvailability.FINGERPRINT)
        val vm = vm()
        assertEquals(false, vm.state.value.biometric)
        appLock.biometricEnabled.value = true
        assertEquals(true, vm.state.value.biometric)
    }

    @Test fun `the flags are read at open and re-read when they change`() {
        flags.set(Flag.PROFILE_DELETE_ACCOUNT, false)
        val vm = vm()
        assertEquals(ProfileFlags(assistant = true, deleteAccount = false, upload = true), vm.state.value.flags)
        flags.set(Flag.STATEMENTS_UPLOAD, false)
        flags.set(Flag.PROFILE_DELETE_ACCOUNT, true)
        assertEquals(ProfileFlags(assistant = true, deleteAccount = true, upload = false), vm.state.value.flags)
    }

    private companion object {
        const val MOSCOW = "Europe/Moscow"
        val LIMIT = AssistantLimit(3, 5, OffsetDateTime.parse("2026-10-14T00:00:00+03:00"))

        fun profile(theme: Theme = Theme.SYSTEM, timezone: String = MOSCOW) = Profile(
            userId = "u_1", phone = "+79161234567", theme = theme, timezone = timezone,
            consents = listOf(Consent(ConsentType.ASSISTANT, granted = true, version = "v2", currentVersion = "v2")),
            createdAt = OffsetDateTime.parse("2026-09-01T10:00:00+03:00"),
        )

        fun summary(uploads: Int) = StatementsSummary(
            uploadCount = uploads, operationCount = 120,
            firstOperationAt = OffsetDateTime.parse("2026-06-01T10:00:00+03:00"),
            lastOperationAt = OffsetDateTime.parse("2026-09-25T10:00:00+03:00"), gaps = emptyList(),
        )

        /**
         * A real [AppPreferences] over a DataStore in a fresh temp directory. Its scope runs on an unconfined
         * test dispatcher, so a write is visible as soon as the handler returns.
         */
        fun tempPreferences(): AppPreferences {
            val dir = createTempDirectory("profile-prefs").toFile().apply { deleteOnExit() }
            val scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
            return AppPreferences(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") })
        }
    }
}
