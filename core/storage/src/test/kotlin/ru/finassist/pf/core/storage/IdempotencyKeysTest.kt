package ru.finassist.pf.core.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            val first = firstOwner.keyFor("upload", clock)
            assertTrue(first.replayWindow!!.isOpen(clock))
            assertSame(first, firstOwner.keyFor("upload", clock))
            now += 24 * 60 * 60 * 1000L
            assertFalse(firstOwner.keyFor("upload", clock).replayWindow!!.isOpen(clock))
            val restored = IdempotencyKeys(store).keyFor("upload", clock)
            assertEquals(first.value, restored.value)
            assertNull(restored.replayWindow)
            store.edit { it[stringPreferencesKey("idem.old")] = "fixed-uuid|0" }
            assertEquals("fixed-uuid", firstOwner.keyFor("old", clock).value)
            assertNull(firstOwner.keyFor("old", clock).replayWindow)
            firstOwner.complete("upload")
            val nextAction = firstOwner.keyFor("upload", clock)
            assertNotEquals(first.value, nextAction.value)
            assertTrue(nextAction.replayWindow!!.isOpen(clock))
        } finally { scope.cancel() }
    }
}
