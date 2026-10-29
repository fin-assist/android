package ru.finassist.pf.feature.profile.impl.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.finassist.pf.feature.profile.impl.data.ZoneSyncStateStore.Operation.GET
import ru.finassist.pf.feature.profile.impl.data.ZoneSyncStateStore.Operation.PATCH

class ZoneSyncStateStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "zone.preferences_pb") } }
    private val store by lazy { ZoneSyncStateStore(preferences) }
    private val owner = ZoneSyncStateStore.Owner("u1", "login-1")
    private val userKey = stringPreferencesKey("session.identity.user")
    private val epochKey = stringPreferencesKey("session.identity.epoch")

    @After fun close() = scope.cancel()
    private suspend fun login(value: ZoneSyncStateStore.Owner = owner) {
        preferences.edit { it[userKey] = value.userId; it[epochKey] = value.epoch }
    }
    private suspend fun initial(): ZoneSyncStateStore.State { login(); return assertNotNull(store.bind(owner)) }

    @Test fun `unknown session cannot bind or dispatch`() = runBlocking<Unit> { assertNull(store.bind(owner)) }

    @Test fun `in flight survives a new store and never becomes ready`() = runBlocking<Unit> {
        val ticket = assertNotNull(store.begin(initial(), GET, 2, 100))
        val restored = assertNotNull(ZoneSyncStateStore(preferences).current(owner))
        assertEquals(ticket.actionId, restored.get.attempt?.id)
        assertNull(store.begin(restored, GET, 2, Long.MAX_VALUE))
    }

    @Test fun `only one concurrent caller records a write ahead ticket`() = runBlocking<Unit> {
        val state = initial()
        val tickets = coroutineScope { List(24) { async { store.begin(state, GET, 2, 100) } }.map { it.await() } }
        assertEquals(1, tickets.count { it != null })
    }

    @Test fun `GET and PATCH have independent false restrictions`() = runBlocking<Unit> {
        var state = assertNotNull(store.target(initial(), "Asia/Novosibirsk"))
        val get = assertNotNull(store.begin(state, GET, 2, 100))
        state = assertNotNull(store.finish(get, ZoneSyncStateStore.Attempt(get.actionId, ZoneSyncStateStore.Phase.RESTRICTED, forbidden = true)))
        val patch = assertNotNull(store.begin(state, PATCH, 2, 100))
        state = assertNotNull(store.finish(patch, null))
        assertNull(store.begin(state, GET, 2, 100))
        assertNotNull(store.begin(state, PATCH, 2, 100))
    }

    @Test fun `same target retains false and actual target change retires only PATCH`() = runBlocking<Unit> {
        var state = assertNotNull(store.target(initial(), "A"))
        val ticket = assertNotNull(store.begin(state, PATCH, 2, 100))
        state = assertNotNull(store.finish(ticket, ZoneSyncStateStore.Attempt(ticket.actionId, ZoneSyncStateStore.Phase.RESTRICTED, forbidden = true)))
        state = assertNotNull(store.target(state, "A"))
        assertNull(store.begin(state, PATCH, 2, 100))
        state = assertNotNull(store.target(state, "B"))
        assertNotNull(store.begin(state, PATCH, 2, 100))
        assertNull(store.finish(ticket, null), "late result cannot clear new target's in flight state")
    }

    @Test fun `same user new login rejects old completion before new checker binds`() = runBlocking<Unit> {
        val ticket = assertNotNull(store.begin(initial(), GET, 2, 100))
        val next = owner.copy(epoch = "login-2")
        login(next)
        assertNull(store.finish(ticket, null))
        assertNull(store.bind(owner))
        val state = assertNotNull(store.bind(next))
        assertNull(state.get.attempt)
        assertNotNull(store.begin(state, GET, 2, 100))
    }

    @Test fun `logout invalidates an outstanding write even before next bind`() = runBlocking<Unit> {
        val ticket = assertNotNull(store.begin(initial(), GET, 2, 100))
        preferences.edit { it.remove(epochKey); it.remove(userKey) }
        assertNull(store.finish(ticket, null))
    }

    @Test fun `same boot remaining wait survives restart with inclusive expiry`() = runBlocking<Unit> {
        val ticket = assertNotNull(store.begin(initial(), GET, 2, 100))
        store.finish(ticket, ZoneSyncStateStore.Attempt(ticket.actionId, ZoneSyncStateStore.Phase.RESTRICTED, needsWait = true,
            wait = ZoneSyncStateStore.Wait(2, 100, 10_100)))
        val state = assertNotNull(ZoneSyncStateStore(preferences).current(owner))
        assertNull(store.begin(state, GET, 2, 10_099))
        assertNotNull(store.begin(state, GET, 2, 10_100))
    }

    @Test fun `reboot unknown counter and elapsed regression never fulfill saved wait`() {
        val wait = ZoneSyncStateStore.Wait(2, 100, 200)
        assertFalse(wait.fulfilled(3, 100_000))
        assertFalse(wait.fulfilled(null, 100_000))
        assertFalse(wait.fulfilled(2, 99))
        assertTrue(wait.fulfilled(2, 200))
    }

    @Test fun `missing wait anchor never becomes ready after long uptime`() {
        val action = ZoneSyncStateStore.Attempt("a", ZoneSyncStateStore.Phase.RESTRICTED, needsWait = true)
        assertFalse(action.ready(2, Long.MAX_VALUE))
    }

    @Test fun `false remains false after wait expires`() {
        val action = ZoneSyncStateStore.Attempt("a", ZoneSyncStateStore.Phase.RESTRICTED, forbidden = true, needsWait = true,
            wait = ZoneSyncStateStore.Wait(2, 100, 200))
        assertFalse(action.ready(2, 1_000))
    }

    @Test fun `unknown schema and malformed bytes fail closed instead of resetting restrictions`() = runBlocking<Unit> {
        initial()
        for (raw in listOf("{", "{\"version\":2}", "{\"version\":1}", "{\"version\":1,\"owner\":{\"userId\":\"u1\",\"epoch\":\"login-1\"},\"revision\":\"r\"}")) {
            preferences.edit { it[stringPreferencesKey("profile.tz_sync.state")] = raw }
            assertFailsWith<Exception> { store.bind(owner) }
        }
    }

    @Test fun `failed outcome persistence leaves durable in flight instead of permitting replay`() = runBlocking<Unit> {
        login()
        var failWrite = false
        val fault = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = preferences.data
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                if (failWrite) throw java.io.IOException("disk full")
                return preferences.updateData(transform)
            }
        }
        val store = ZoneSyncStateStore(fault)
        val state = assertNotNull(store.bind(owner))
        val ticket = assertNotNull(store.begin(state, GET, 2, 100))
        failWrite = true
        assertFailsWith<java.io.IOException> { store.finish(ticket, null) }
        val restored = assertNotNull(ZoneSyncStateStore(preferences).current(owner))
        assertEquals(ZoneSyncStateStore.Phase.IN_FLIGHT, restored.get.attempt?.phase)
        assertNull(ZoneSyncStateStore(preferences).begin(restored, GET, 2, 1_000_000))
    }

    @Test fun `failed storage never gives a dispatch ticket`() = runBlocking<Unit> {
        assertFailsWith<java.io.IOException> { ZoneSyncStateStore(FailingDataStore()).bind(owner) }
    }
}
