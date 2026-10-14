package ru.finassist.pf.feature.profile.impl.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.profile.impl.domain.TimeZoneRule
import ru.finassist.pf.feature.profile.impl.domain.TimeZoneStep
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One check of [TimeZoneRule] against the stored record; the triggers live in [TimeZoneSyncImpl]. Time comes from
 * the injected [Clock], so the e2e build (pinned clock) counts the threshold the same way as the app.
 *
 * The profile zone is fetched once per process and user and then kept in memory: a return from the background
 * must not cost a request. Before sending, the profile is
 * read again, so a zone that already reached the server another way is not sent twice. Failures are silent and
 * keep the record: the next trigger tries again.
 */
@Singleton
class TimeZoneChecker @Inject constructor(
    private val profileApi: ProfileApi,
    private val session: SessionRepository,
    private val store: PendingZoneStore,
    private val clock: Clock,
    private val deviceZone: DeviceZone,
) {
    private val mutex = Mutex()

    /** `(user id, profile zone)` as last read from or written to the server. */
    private var known: Pair<String, String>? = null

    suspend fun check(): Unit = mutex.withLock {
        val userId = (session.state.first() as? SessionState.SignedIn)?.userId ?: return
        val device = deviceZone.id()
        val profileZone = known?.takeIf { it.first == userId }?.second ?: fetch(userId) ?: return
        when (val step = TimeZoneRule.next(profileZone, device, store.get(userId), clock.now().toInstant())) {
            TimeZoneStep.Clear -> store.clear()
            is TimeZoneStep.Record -> store.set(userId, step.pending)
            TimeZoneStep.Wait -> Unit
            is TimeZoneStep.Send -> send(userId, step.zone)
        }
    }

    /** The record and the remembered zone belong to the user who left. */
    suspend fun signedOut(): Unit = mutex.withLock {
        known = null
        store.clear()
    }

    private suspend fun send(userId: String, zone: String) {
        // Not read, not sent: offline the PATCH would fail as well, and the record waits for the next trigger.
        val fresh = fetch(userId) ?: return
        if (fresh == zone) {
            store.clear()
            return
        }
        val updated = quietly { profileApi.updateProfile(ProfileUpdate(timezone = zone)) } ?: return
        known = userId to updated.timezone
        store.clear()
    }

    private suspend fun fetch(userId: String): String? =
        quietly { profileApi.getProfile() }?.timezone?.also { known = userId to it }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
