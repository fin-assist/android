package ru.finassist.pf.e2e

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.finassist.pf.core.api.*
import ru.finassist.pf.core.api.model.*
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.feature.applock.api.*
import ru.finassist.pf.feature.auth.api.*
import java.time.OffsetDateTime
import kotlin.test.*

class StageBootstrapTest {
    private val moment = OffsetDateTime.parse("2026-10-30T21:30:00Z")
    private val phone = "+70000001234"
    private val fixture = "<OFX><DTSERVER>20261001000000[+3:MSK]</DTSERVER><DTEND>20261001000000[+3:MSK]</DTEND><DTPOSTED>20260930113015[+3:MSK]</DTPOSTED><FITID>stable-id</FITID></OFX>".toByteArray()
    private val api = FakeApis()
    private val session = FakeSession()
    private val lock = FakeLock()
    private val sessionStarts = mutableListOf<RegisterResponse>()

    private fun bootstrap() = StageBootstrap(api, api, api, api, session, lock,
        startSession = {
            sessionStarts += it
            session.state.value = SessionState.SignedIn(it.userId)
        }, fixture = { fixture }, now = { moment }, timeoutMs = 5_000,
    )

    @Test
    fun `seeded setup preserves first registration session and imports before enabling the main UI`() = runTest {
        bootstrap().setup(phone, "seeded")
        assertEquals(1, api.starts.size)
        assertEquals(1, api.registrations.size)
        assertEquals("pd-original", api.starts.single().pdConsentVersion)
        assertEquals("pd-original", api.registrations.single().pdConsentVersion)
        assertTrue(api.starts.single().ageConfirmed)
        assertTrue(api.starts.single().pdConsentAccepted)
        assertEquals(1, sessionStarts.size)
        assertEquals("new-user", sessionStarts.single().userId)
        assertContentEquals(fixture, api.uploaded)
        assertEquals(listOf("1234"), lock.passcodes)
        assertEquals(LockState.Unlocked, lock.state.value)
        assertEquals(listOf("document", "phone", "verify", "register", "profile", "upload", "progress", "analytics", "periods"), api.calls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `cold lock loading waits for real signed-out reset instead of rejecting startup`() = runTest {
        lock.state.value = LockState.Locked
        launch { delay(1); lock.state.value = LockState.NotConfigured }
        bootstrap().setup(phone, "seeded")
        assertEquals(1, api.starts.size)
        assertEquals(1L, testScheduler.currentTime)
    }

    @Test
    fun `existing or unknown candidate never adopts tokens or touches profile and financial data`() = runTest {
        for (isNew in listOf(false, null)) {
            api.verified = PhoneVerificationStatusEvent(VerificationStatus.VERIFIED, isNewUser = isNew, accessToken = "foreign-access", refreshToken = "foreign-refresh")
            assertFailsWith<IllegalStateException> { bootstrap().setup(phone, "seeded") }
        }
        assertTrue(api.registrations.isEmpty())
        assertTrue(sessionStarts.isEmpty())
        assertNull(api.uploaded)
        assertFalse("profile" in api.calls)
        assertTrue(lock.passcodes.isEmpty())
    }

    @Test
    fun `expired verification and invalid initial state do not create a session`() = runTest {
        api.verified = PhoneVerificationStatusEvent(VerificationStatus.EXPIRED)
        assertFailsWith<IllegalStateException> { bootstrap().setup(phone, "seeded") }
        assertTrue(api.registrations.isEmpty())
        session.state.value = SessionState.SignedIn("preexisting")
        val starts = api.starts.size
        assertFailsWith<IllegalStateException> { bootstrap().setup(phone, "seeded") }
        assertEquals(starts, api.starts.size)
        assertTrue(sessionStarts.isEmpty())
    }

    @Test
    fun `needs-code precondition leaves the actual passcode setup for the UI`() = runTest {
        bootstrap().setup(phone, "seeded_needs_code")
        assertEquals(1, sessionStarts.size)
        assertNotNull(api.uploaded)
        assertTrue(lock.passcodes.isEmpty())
        assertEquals(LockState.NotConfigured, lock.state.value)
    }

    @Test
    fun `fresh setup changes only collection timestamps in Moscow calendar time`() = runTest {
        bootstrap().setup(phone, "fresh")
        val changed = requireNotNull(api.uploaded).toString(Charsets.UTF_8)
        assertTrue(changed.contains("<DTSERVER>20261031003000.000[+3:MSK]</DTSERVER>"))
        assertTrue(changed.contains("<DTEND>20261031003000.000[+3:MSK]</DTEND>"))
        fun omitCollection(value: String) = value.replace(Regex("<(DTSERVER|DTEND)>[^<]+</(DTSERVER|DTEND)>"), "")
        assertEquals(omitCollection(fixture.toString(Charsets.UTF_8)), omitCollection(changed))
    }

    @Test
    fun `readiness waits only for real asynchronous analytics and never repeats phone or registration`() = runTest {
        api.readyAfter = 2
        bootstrap().setup(phone, "seeded")
        assertEquals(2, api.analyticsReads)
        assertEquals(500L, testScheduler.currentTime)
        assertEquals(1, api.starts.size)
        assertEquals(1, api.registrations.size)
    }

    @Test
    fun `failed import preserves the owned session for cleanup without claiming seeded readiness`() = runTest {
        api.importFails = true
        assertFailsWith<IllegalStateException> { bootstrap().setup(phone, "seeded") }
        assertEquals(1, sessionStarts.size)
        assertEquals(0, api.analyticsReads)
        assertTrue(lock.passcodes.isEmpty())
    }

    @Test
    fun `analytics readiness timeout does not reauthenticate or unlock an unready client`() = runTest {
        api.readyAfter = Int.MAX_VALUE
        assertFailsWith<TimeoutCancellationException> { bootstrap().setup(phone, "seeded") }
        assertEquals(1, api.starts.size)
        assertEquals(1, api.registrations.size)
        assertTrue(lock.passcodes.isEmpty())
    }

    private inner class FakeApis : AuthApi, ProfileApi, StatementsApi, AnalyticsApi {
        val calls = mutableListOf<String>()
        val starts = mutableListOf<StartPhoneVerificationRequest>()
        val registrations = mutableListOf<RegisterRequest>()
        var verified = PhoneVerificationStatusEvent(VerificationStatus.VERIFIED, isNewUser = true, registrationToken = "registration")
        var uploaded: ByteArray? = null
        var importFails = false
        var analyticsReads = 0
        var readyAfter = 1
        override suspend fun getConsentDocument(type: ConsentType): ConsentDocument {
            calls += "document"
            return ConsentDocument(type, "pd-original", "Synthetic", "https://stage.plainfin.ru/pd")
        }
        override suspend fun startPhoneVerification(key: IdempotencyKey, request: StartPhoneVerificationRequest): PhoneVerification {
            calls += "phone"; starts += request
            return PhoneVerification("verification", "+70000000001", moment.plusMinutes(5), 300)
        }
        override fun streamPhoneVerification(verificationToken: String): Flow<PhoneVerificationStatusEvent> {
            calls += "verify"
            return flowOf(PhoneVerificationStatusEvent(VerificationStatus.PENDING), verified)
        }
        override suspend fun register(key: IdempotencyKey, request: RegisterRequest): RegisterResponse {
            calls += "register"; registrations += request
            return RegisterResponse("first-access", "first-refresh", "new-user")
        }
        override suspend fun refreshTokens(key: IdempotencyKey, refreshToken: String): TokenPair = error("No second session or refresh needed for bootstrap")
        override suspend fun logout(accessToken: String, refreshToken: String) = error("Bootstrap preserves first session")
        override suspend fun getProfile(): Profile {
            calls += "profile"
            return Profile("new-user", phone, Theme.SYSTEM, "Europe/Moscow", createdAt = moment)
        }
        override suspend fun updateProfile(update: ProfileUpdate): Profile = error("unused")
        override suspend fun deleteAccount() = error("unused")
        override suspend fun grantAssistantConsent(grant: AssistantConsentGrant): Profile = error("unused")
        override suspend fun revokeAssistantConsent() = error("unused")
        override suspend fun getImportConfig(): ImportConfig = error("unused")
        override suspend fun uploadStatement(key: IdempotencyKey, file: UploadFile): UploadAccepted {
            calls += "upload"; uploaded = file.open().use { it.readBytes() }
            assertEquals(requireNotNull(uploaded).size.toLong(), file.size)
            return UploadAccepted("upload", file.name)
        }
        override fun streamProgress(uploadId: String): Flow<ImportEvent> {
            calls += "progress"
            return if (importFails) flowOf(ImportEvent.Error("PROCESSING_FAILED", "Synthetic failure")) else flowOf(ImportEvent.Complete(ImportResult(
                operationCount = 1, newCount = 1, duplicateCount = 0, unreadCount = 0, isFirstImport = true,
                accounts = emptyList(), categorizedCount = 1, ownTransferCount = 0, uncategorizedCount = 0,
                coverage = ImportCoverage(1, 0, emptyList(), emptyList(), emptyList()), notices = emptyList(),
            )))
        }
        override suspend fun listStatements(): StatementsList = error("unused")
        override suspend fun deleteStatement(uploadId: String) = error("unused")
        override suspend fun listUnreadLines(uploadId: String): UnreadLinesList = error("unused")
        override suspend fun listUnreadLines(from: OffsetDateTime, to: OffsetDateTime): UnreadLinesList = error("unused")
        override suspend fun getAnalytics(period: PeriodTypeCode?, date: PeriodKey?, transferMode: TransferMode?, supports: String?, exp: String?): Analytics {
            calls += "analytics"; analyticsReads++
            return Analytics(true, state = AnalyticsState(false, dataFrom = moment.minusMonths(1), dataTo = moment, fullMonths = 1,
                unreadLinesCount = 0, calculatedAt = moment, recalculating = analyticsReads < readyAfter))
        }
        override suspend fun listPeriods(period: PeriodTypeCode?): AnalyticsPeriodsList {
            calls += "periods"
            return AnalyticsPeriodsList(listOf(AnalyticsPeriodItem(PeriodKey("2026-10"), DateRange(moment.minusMonths(1), moment), Coverage.COMPLETE)))
        }
        override suspend fun dismissRegularPayment(id: String) = error("unused")
        override suspend fun restoreRegularPayment(id: String) = error("unused")
    }

    private class FakeSession : SessionRepository {
        override val state = MutableStateFlow<SessionState>(SessionState.SignedOut)
        override suspend fun consumeJustRegistered() = false
        override suspend fun signOut(reason: String?) = error("unused")
        override suspend fun clearLocal(reason: String?) = error("unused")
        override fun consumeSignOutReason(): String? = null
    }
    private class FakeLock : AppLock {
        override val state = MutableStateFlow<LockState>(LockState.NotConfigured)
        override val biometricEnabled = MutableStateFlow(false)
        val passcodes = mutableListOf<String>()
        override fun biometricAvailability() = BiometricAvailability.NONE
        override suspend fun setPasscode(code: String) { passcodes += code; state.value = LockState.Unlocked }
        override suspend fun verify(code: String): VerifyResult = error("unused")
        override suspend fun matches(code: String): Boolean = error("unused")
        override suspend fun setBiometricEnabled(enabled: Boolean) = error("unused")
        override fun unlockByBiometric() = error("unused")
        override fun lock() = error("unused")
        override suspend fun forgetAndSignOut() = error("unused")
        override suspend fun reset() = error("unused")
    }
}
