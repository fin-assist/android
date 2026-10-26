package ru.finassist.pf.core.storage

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.TokenPair
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

/**
 * FIN-88: a DataStore collector that starts while a write is in flight can miss that write for good. A subscriber
 * of [EncryptedTokenStore.session] that starts during a sign-in save or a sign-out clear (`PfApp` switching graphs,
 * the app lock watching for a sign-out) must still end up at the stored value, every time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EncryptedTokenStoreRaceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val root: File = createTempDirectory("token-race").toFile()
    private val jobs = mutableListOf<Job>()

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        root.deleteRecursively()
    }

    private fun newStore(run: Int): EncryptedTokenStore {
        val job = Job().also { jobs += it }
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) {
            File(root, "run$run.preferences_pb")
        }
        return EncryptedTokenStore(context, dataStore)
    }

    /**
     * Starts a subscriber and [write] together, then waits for the subscriber to see [expected]. Returns the runs in
     * which it did not, with what it saw last.
     */
    private fun raceRuns(prepare: suspend (EncryptedTokenStore) -> Unit, write: suspend (EncryptedTokenStore) -> Unit, expected: TokenStore.Session?): List<String> {
        val stale = mutableListOf<String>()
        repeat(RUNS) { run ->
            val store = newStore(run)
            runBlocking {
                prepare(store)
                val scope = CoroutineScope(Dispatchers.IO + Job().also { jobs += it })
                var last: Any? = "nothing"
                val seen = scope.async {
                    withTimeoutOrNull(WAIT_MS) { store.session.first { last = it; it?.copy(authSessionEpoch = null) == expected }; true } == true
                }
                scope.launch { write(store) }.join()
                assertEquals(expected, store.current()?.copy(authSessionEpoch = null), "run $run: the write did not land")
                if (!seen.await()) stale += "run $run: last=$last"
            }
        }
        return stale
    }

    @Test
    fun `a subscriber starting during a sign-in save sees the session`() {
        val stale = raceRuns(prepare = { it.current() }, write = { it.save(SESSION) }, expected = SESSION)
        assertEquals(emptyList(), stale, "${stale.size} of $RUNS subscribers missed the sign-in")
    }

    @Test
    fun `a subscriber starting during a sign-out clear sees the sign-out`() {
        val stale = raceRuns(prepare = { it.save(SESSION) }, write = { it.clear() }, expected = null)
        assertEquals(emptyList(), stale, "${stale.size} of $RUNS subscribers missed the sign-out")
    }

    private companion object {
        const val RUNS = 200
        const val WAIT_MS = 500L
        val SESSION = TokenStore.Session("user-1", TokenPair("access-1", "refresh-1"))
    }
}
