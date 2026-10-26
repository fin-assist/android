package ru.finassist.pf.feature.profile.impl.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.RequestOwner
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.ReportZone
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.profile.impl.domain.TimeZoneRule
import ru.finassist.pf.feature.profile.impl.domain.TimeZoneStep
import javax.inject.Inject
import javax.inject.Singleton

/** Silent lifecycle sync. Domain 72h uses Clock; independent GET/PATCH restrictions use boot-bound elapsed time. */
@Singleton
class TimeZoneChecker @Inject constructor(
    private val profileApi: ProfileApi,
    private val session: SessionRepository,
    private val store: PendingZoneStore,
    private val clock: Clock,
    private val deviceZone: DeviceZone,
    private val reportZone: ReportZone,
    private val knownStore: KnownZoneStore,
    private val retryStore: ZoneSyncStateStore,
    private val elapsedClock: ElapsedClock,
    private val bootCounter: BootCounter,
) {
    private val mutex = Mutex()
    private var known: Pair<ZoneSyncStateStore.Owner, String>? = null

    /** Capture before an external profile RPC; observing its response must not recapture a newer login. */
    suspend fun captureOwner(): ZoneSyncStateStore.Owner? = (session.state.first() as? SessionState.SignedIn)?.let {
        val epoch = it.authSessionEpoch?.takeIf(String::isNotBlank) ?: return@let null
        ZoneSyncStateStore.Owner(it.userId, epoch)
    }

    data class Observation(val owner: ZoneSyncStateStore.Owner, val revision: String)

    suspend fun captureObservation(): Observation? = quietly {
        val owner = captureOwner() ?: return@quietly null
        val state = retryStore.bind(owner) ?: return@quietly null
        Observation(owner, state.revision)
    }

    suspend fun check(): Unit = mutex.withLock { quietly {
        val owner = captureOwner() ?: return@quietly
        val device = deviceZone.id()
        val bound = retryStore.bind(owner) ?: return@quietly
        retryStore.target(bound, device) ?: return@quietly
        val profileZone = known?.takeIf { it.first == owner }?.second ?: run {
            quietly { knownStore.get(owner.userId) }?.let(reportZone::set)
            fetch(owner)
        } ?: return@quietly
        if (!isCurrent(owner) || deviceZone.id() != device) return@quietly
        when (val step = TimeZoneRule.next(profileZone, device, store.get(owner.userId), clock.now().toInstant())) {
            TimeZoneStep.Clear -> store.clear(owner)
            is TimeZoneStep.Record -> store.set(owner, step.pending)
            TimeZoneStep.Wait -> Unit
            is TimeZoneStep.Send -> send(owner, step.zone)
        }
    }; Unit }

    suspend fun signedOut(): Unit = mutex.withLock {
        if (session.state.first() is SessionState.SignedIn) return@withLock
        known = null
        reportZone.clear()
        quietly { store.clearSignedOut() }
        quietly { knownStore.clearSignedOut() }
    }

    /** Does not clear either request's restriction; ordinary observation is not a changed request context. */
    suspend fun observed(profile: Profile, origin: Observation?): Unit = mutex.withLock {
        quietly {
            if (origin != null && profile.userId == origin.owner.userId && isCurrent(origin.owner) &&
                retryStore.current(origin.owner)?.revision == origin.revision
            ) remember(origin.owner, profile.timezone)
        }
        Unit
    }

    private suspend fun send(owner: ZoneSyncStateStore.Owner, zone: String) {
        val fresh = fetch(owner) ?: return
        if (!isCurrent(owner) || deviceZone.id() != zone) return
        if (fresh == zone) { store.clear(owner); return }
        val updated = request(owner, ZoneSyncStateStore.Operation.PATCH, zone) {
            profileApi.updateProfile(ProfileUpdate(timezone = zone))
        } ?: return
        remember(owner, updated.timezone)
        store.clear(owner)
    }

    private suspend fun fetch(owner: ZoneSyncStateStore.Owner): String? =
        request(owner, ZoneSyncStateStore.Operation.GET) { profileApi.getProfile() }?.timezone?.also { remember(owner, it) }

    private suspend fun request(
        owner: ZoneSyncStateStore.Owner,
        operation: ZoneSyncStateStore.Operation,
        target: String? = null,
        block: suspend () -> Profile,
    ): Profile? {
        if (!isCurrent(owner)) return null
        val state = retryStore.current(owner) ?: return null
        if (operation == ZoneSyncStateStore.Operation.PATCH && (state.patch.target != target || deviceZone.id() != target)) return null
        val ticket = retryStore.begin(state, operation, bootCounter.current(), elapsedClock.nowMillis()) ?: return null
        if (!isCurrent(owner)) return null // leave uncertain write-ahead marker; never grant another attempt
        return try {
            val profile = withContext(RequestOwner(owner.userId, owner.epoch)) { block() }
            if (!isCurrent(owner) || profile.userId != owner.userId ||
                operation == ZoneSyncStateStore.Operation.PATCH && deviceZone.id() != target
            ) return null
            if (retryStore.finish(ticket, null) == null) return null
            profile
        } catch (e: CancellationException) {
            throw e // unknown outcome stays IN_FLIGHT, including process/lifecycle cancellation
        } catch (e: AppError) {
            val gate = RequestRetryGate(elapsedClock).apply { record(e) }
            val left = gate.remainingMillis()
            val now = elapsedClock.nowMillis()
            val boot = bootCounter.current()
            val wait = if (left != null && left > 0 && boot != null && now >= 0 && left <= Long.MAX_VALUE - now)
                ZoneSyncStateStore.Wait(boot, now, now + left) else null
            retryStore.finish(ticket, ZoneSyncStateStore.Attempt(ticket.actionId, ZoneSyncStateStore.Phase.RESTRICTED,
                gate.isExplicitlyForbidden(), left == null || left > 0, wait))
            null
        }
        // Unexpected failure/storage failure leaves IN_FLIGHT. No new trigger turns uncertainty into permission.
    }

    private suspend fun remember(owner: ZoneSyncStateStore.Owner, zone: String) {
        if (!isCurrent(owner)) return
        known = owner to zone
        if (reportZone.set(zone)) quietly { knownStore.set(owner, zone) }
    }

    private suspend fun isCurrent(owner: ZoneSyncStateStore.Owner): Boolean = captureOwner() == owner

    private suspend fun <T> quietly(block: suspend () -> T): T? = try { block() }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { null }
}
