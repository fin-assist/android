package ru.finassist.pf.feature.profile.impl.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.model.Consent
import ru.finassist.pf.core.api.model.ConsentType
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
    /** Whose profile the server returns: the user of the token the request went out with. */
    @Volatile var userId: String = "u1"
    /** Runs inside each GET, before the answer: lets a test switch the session while a request is in flight. */
    @Volatile var onGet: () -> Unit = {}
    /** Runs inside each PATCH, before the answer. */
    @Volatile var onUpdate: () -> Unit = {}
    val gets = java.util.concurrent.atomic.AtomicInteger()
    val updates: MutableList<ProfileUpdate> = Collections.synchronizedList(mutableListOf())

    private fun profile() = Profile(
        userId = userId, phone = "+79161234567", theme = Theme.SYSTEM, timezone = timezone,
        consents = listOf(Consent(ConsentType.ASSISTANT, granted = false, version = null, currentVersion = "ai-1")),
        createdAt = OffsetDateTime.parse("2026-09-01T10:00:00+03:00"),
    )

    override suspend fun getProfile(): Profile {
        gets.incrementAndGet()
        onGet()
        getFailure?.let { throw it }
        return profile()
    }

    override suspend fun updateProfile(update: ProfileUpdate): Profile {
        updates += update
        onUpdate()
        updateFailure?.let { throw it }
        update.timezone?.let { timezone = it }
        return profile()
    }

    override suspend fun deleteAccount() = Unit
    override suspend fun grantAssistantConsent(grant: AssistantConsentGrant): Profile = error("unused")
    override suspend fun revokeAssistantConsent() = Unit
}

internal class FakeSession(initial: SessionState = SessionState.SignedIn("u1", "epoch-1")) : SessionRepository {
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

internal fun tempPendingStore(session: SessionRepository? = null): PendingZoneStore {
    val dir = createTempDirectory("tz-sync").toFile().apply { deleteOnExit() }
    // On the calling thread: a write never resumes a view model on Main after the test reset it.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    return PendingZoneStore(identityPreferences(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }, session))
}

/** Every read and write fails, as with a full disk or a broken preferences file. */
internal class FailingDataStore : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    override val data: kotlinx.coroutines.flow.Flow<androidx.datastore.preferences.core.Preferences> =
        kotlinx.coroutines.flow.flow { throw java.io.IOException("disk full") }

    override suspend fun updateData(
        transform: suspend (t: androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
    ): androidx.datastore.preferences.core.Preferences = throw java.io.IOException("disk full")
}

internal fun tempKnownStore(session: SessionRepository? = null): KnownZoneStore {
    val dir = createTempDirectory("tz-known").toFile().apply { deleteOnExit() }
    // On the calling thread: a write never resumes a view model on Main after the test reset it.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    return KnownZoneStore(identityPreferences(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }, session))
}

/** Auth identity fixture only: production atomically writes these keys with its encrypted session. */
private fun identityPreferences(
    backing: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    session: SessionRepository?,
): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    if (session == null) return backing
    return object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        private suspend fun owner(prefs: androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
            val value = session.state.first() as? SessionState.SignedIn
            return prefs.toMutablePreferences().apply {
                val user = androidx.datastore.preferences.core.stringPreferencesKey("session.identity.user")
                val epoch = androidx.datastore.preferences.core.stringPreferencesKey("session.identity.epoch")
                remove(user); remove(epoch)
                if (value?.authSessionEpoch != null) { this[user] = value.userId; this[epoch] = value.authSessionEpoch!! }
            }
        }
        override val data = backing.data.map { owner(it) }
        override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) =
            backing.updateData { transform(owner(it)) }
    }
}

internal fun tempZoneSyncPreferences(session: SessionRepository): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    val dir = createTempDirectory("tz-retry").toFile().apply { deleteOnExit() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    return identityPreferences(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }, session)
}

internal fun tempZoneSyncStore(session: SessionRepository): ZoneSyncStateStore = ZoneSyncStateStore(tempZoneSyncPreferences(session))
