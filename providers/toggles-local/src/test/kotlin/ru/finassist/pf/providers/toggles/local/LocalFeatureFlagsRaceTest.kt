package ru.finassist.pf.providers.toggles.local

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.finassist.pf.core.toggles.Flag
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

/**
 * FIN-89: a DataStore collector that starts while a write is in flight can miss that write for good (FIN-87). The
 * flags start observing their overrides and, at once, the e2e control link writes one (`pfe2e://config?flag.…`
 * right after the app starts). Whatever the timing, the flags must answer with the written override.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalFeatureFlagsRaceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val root: File = createTempDirectory("flags-race").toFile()
    private val jobs = mutableListOf<Job>()

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        root.deleteRecursively()
    }

    /** An override written by the previous process; the store of this one has not been read yet. */
    private fun leftoverOverride(file: File) = runBlocking {
        val job = Job()
        PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
            .edit { it[booleanPreferencesKey("flags.override.${Flag.ASSISTANT.key}")] = false }
        job.cancel()
        job.join()
    }

    private fun eventually(check: () -> Boolean) = runBlocking {
        withTimeoutOrNull(WAIT_MS) {
            while (!check()) delay(5)
            true
        } ?: false
    }

    @Test
    fun `an override written while the flags start is never lost`() {
        val stale = mutableListOf<String>()
        repeat(RUNS) { run ->
            val file = File(root, "run$run.preferences_pb")
            leftoverOverride(file)
            val job = Job().also { jobs += it }
            val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }

            val flags = LocalFeatureFlags(context, dataStore)
            runBlocking { flags.set(Flag.ASSISTANT, true) }

            if (!eventually { flags.isEnabled(Flag.ASSISTANT) }) stale += "run $run"
            job.cancel()
        }
        assertEquals(emptyList(), stale, "${stale.size} of $RUNS starts kept a stale override")
    }

    private companion object {
        const val RUNS = 1000
        const val WAIT_MS = 500L
    }
}
