package ru.finassist.pf.feature.profile.impl.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.model.AssistantConsent
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Collections
import kotlin.io.path.createTempDirectory

internal class FakeProfileApi(@Volatile var timezone: String = "Europe/Moscow") : ProfileApi {
    @Volatile var getFailure: AppError? = null
    @Volatile var updateFailure: AppError? = null
    val gets = java.util.concurrent.atomic.AtomicInteger()
    val updates: MutableList<ProfileUpdate> = Collections.synchronizedList(mutableListOf())

    private fun profile() = Profile(
        userId = "u1", phone = "+79161234567", theme = Theme.SYSTEM, timezone = timezone,
        assistantConsent = AssistantConsent(granted = false, version = null, currentVersion = "ai-1"),
        createdAt = OffsetDateTime.parse("2026-09-01T10:00:00+03:00"),
    )

    override suspend fun getProfile(): Profile {
        gets.incrementAndGet()
        getFailure?.let { throw it }
        return profile()
    }

    override suspend fun updateProfile(update: ProfileUpdate): Profile {
        updates += update
        updateFailure?.let { throw it }
        update.timezone?.let { timezone = it }
        return profile()
    }

    override suspend fun deleteAccount() = Unit
    override suspend fun grantAssistantConsent(grant: AssistantConsentGrant): Profile = error("unused")
    override suspend fun revokeAssistantConsent() = Unit
}

internal class FakeSession(initial: SessionState = SessionState.SignedIn("u1")) : SessionRepository {
    override val state = MutableStateFlow(initial)
    override suspend fun consumeJustRegistered() = false
    override suspend fun signOut(reason: String?) = Unit
    override suspend fun clearLocal(reason: String?) = Unit
    override fun consumeSignOutReason(): String? = null
}

internal class FakeClock(@Volatile var current: OffsetDateTime = OffsetDateTime.parse("2026-10-14T12:00:00+03:00")) : Clock {
    override val zone: ZoneId = Clock.MOSCOW
    override fun now(): OffsetDateTime = current
    fun advance(by: Duration) { current = current.plus(by) }
}

internal class FakeDeviceZone(@Volatile var zone: String = "Europe/Moscow") : DeviceZone {
    /** The zone the system has switched to but this process has not re-read yet (see [DeviceZone.reset]). */
    @Volatile var pending: String? = null

    override fun id(): String = zone

    override fun reset() {
        pending?.let { zone = it }
        pending = null
    }
}

internal fun tempPendingStore(): PendingZoneStore {
    val dir = createTempDirectory("tz-sync").toFile().apply { deleteOnExit() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    return PendingZoneStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") })
}
