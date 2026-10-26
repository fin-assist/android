package ru.finassist.pf.core.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.finassist.pf.core.common.time.ElapsedClock
import kotlin.test.*

class IdempotencyKeysTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `fresh same-process key has a window but restored or old records never renew`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = folder.newFolder().resolve("keys.preferences_pb")
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            var now = 1000L
            val clock = ElapsedClock { now }
            val firstOwner = IdempotencyKeys(store)
            val empty = store.data.first()
            assertNull(firstOwner.pendingKey("absent"))
            assertEquals(empty, store.data.first(), "lookup cannot create a pending record")
            val first = firstOwner.keyFor("upload", clock)
            assertSame(first, firstOwner.pendingKey("upload"))
            assertTrue(first.replayWindow!!.isOpen(clock))
            assertSame(first, firstOwner.keyFor("upload", clock))
            now += 24 * 60 * 60 * 1000L
            assertFalse(firstOwner.keyFor("upload", clock).replayWindow!!.isOpen(clock))
            val restoredOwner = IdempotencyKeys(store)
            val lookup = restoredOwner.pendingKey("upload")
            assertEquals(first.value, lookup?.value)
            assertNull(lookup?.replayWindow)
            val restored = restoredOwner.keyFor("upload", clock)
            assertEquals(first.value, restored.value)
            assertNull(restored.replayWindow)
            store.edit { it[stringPreferencesKey("idem.old")] = "fixed-uuid|0" }
            assertEquals("fixed-uuid", firstOwner.keyFor("old", clock).value)
            assertNull(firstOwner.keyFor("old", clock).replayWindow)
            firstOwner.complete("upload")
            assertNull(firstOwner.pendingKey("upload"))
            val nextAction = firstOwner.keyFor("upload", clock)
            assertNotEquals(first.value, nextAction.value)
            assertTrue(nextAction.replayWindow!!.isOpen(clock))
        } finally { scope.cancel() }
    }
}
